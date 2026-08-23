package com.ignite.infra;

import com.intellij.openapi.diagnostic.Logger;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.apache.commons.net.telnet.TelnetClient;

/**
 * 负责 Arthas telnet 协议通信：连接、发送命令、读取提示符、重试。
 * 同一 host:port 的连接跨命令复用，出错时丢弃重建。
 */
final class ArthasTelnetClient {

    private static final Logger LOG = Logger.getInstance(ArthasTelnetClient.class);

    private static final Pattern PROMPT_PATTERN = Pattern.compile("\\[arthas@\\d+\\]\\$\\s*$");

    /** 提示符扫描只需检查缓冲区尾部窗口，避免对全量输出反复跑正则 */
    private static final int PROMPT_WINDOW_CHARS = 64;

    // 重试与超时配置
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int RETRY_DELAY_MS = 1000;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int PORT_PROBE_TIMEOUT_MS = 1000;
    private static final int COMMAND_TIMEOUT_MS = 15000;
    private static final int EXECUTION_TIMEOUT_MS = 120000;
    /** 单次阻塞读的 socket 超时，超时未读到数据则轮询 deadline */
    private static final int SOCKET_READ_TIMEOUT_MS = 300;

    private static final String TIMEOUT_NOTE =
        "\n[Ignite] 等待提示符超时，以上为部分输出（方法可能仍在执行或被断点暂停）";

    /**
     * 连接缓存：key 为 "host:port"。同一会话串行使用（synchronized），
     * 异常时从缓存剔除并关闭，下次命令重建连接。
     */
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    /** 每个 host:port 一把锁，避免并发建连时旧连接被覆盖导致 socket 泄漏 */
    private static final Map<String, Object> KEY_LOCKS = new ConcurrentHashMap<>();

    private final String host;
    private final int port;

    ArthasTelnetClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * 通过 telnet 发送命令。awaitResult=false 时以异步命令（尾部加 &）发送且不等待执行结果。
     * awaitResult=true 时失败以字符串形式返回错误（兼容旧调用方），否则抛 RuntimeException。
     */
    String executeCommand(String command, boolean awaitResult) {
        return executeCommand(command, awaitResult, null);
    }

    /**
     * @param cancelCheck 每次读轮询时检查；返回 true 立即中止等待并抛错，用于响应进度条取消
     */
    String executeCommand(String command, boolean awaitResult, java.util.function.BooleanSupplier cancelCheck) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            Session session = null;
            try {
                session = acquireSession();
                synchronized (session) {
                    String commandToSend = awaitResult ? command : toAsyncCommand(command);
                    session.out.println(commandToSend);
                    session.out.flush();

                    ReadResult readResult = readUntilPrompt(
                        session.reader,
                        awaitResult ? EXECUTION_TIMEOUT_MS : COMMAND_TIMEOUT_MS,
                        cancelCheck
                    );
                    return cleanOutput(readResult.text()) + (readResult.timedOut() ? TIMEOUT_NOTE : "");
                }
            } catch (Exception e) {
                lastError = e;
                if (session != null) {
                    closeSession(session);
                }
                if (attempt < MAX_RETRY_ATTEMPTS && isConnectionRefused(e)) {
                    LOG.info("Connection refused, retrying... (" + attempt + "/" + MAX_RETRY_ATTEMPTS + ")");
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                break;
            }
        }

        LOG.warn("Arthas 命令通信失败: " + command, lastError);
        // 统一抛异常：调用方（Executor）负责区分取消/超时并提示用户，
        // 避免把"通信失败"字符串误当执行结果展示或写入参数历史
        throw new RuntimeException("通信失败: " + (lastError != null ? lastError.getMessage() : "unknown"), lastError);
    }

    // ==================== 会话管理 ====================

    private Session acquireSession() throws IOException {
        String key = host + ":" + port;
        Object lock = KEY_LOCKS.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            Session existing = SESSIONS.get(key);
            if (existing != null && existing.client.isConnected()) {
                return existing;
            }
            if (existing != null) {
                closeSession(existing);
            }
            Session created = openSession();
            SESSIONS.put(key, created);
            return created;
        }
    }

    private Session openSession() throws IOException {
        TelnetClient telnet = new TelnetClient();
        telnet.setConnectTimeout(CONNECT_TIMEOUT_MS);
        telnet.connect(host, port);
        // 关键：连接后立即设置 socket 读超时。
        // 否则对端静默（半开连接、服务卡死）时 readUntilPrompt 会永久阻塞，deadline 轮询完全失效
        telnet.setSoTimeout(SOCKET_READ_TIMEOUT_MS);

        PrintStream out = new PrintStream(telnet.getOutputStream(), true, "UTF-8");
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(telnet.getInputStream(), StandardCharsets.UTF_8));

        String initialOutput = readUntilPrompt(reader, COMMAND_TIMEOUT_MS, null).text();
        if (!hasPromptAtEnd(initialOutput)) {
            closeQuietly(telnet);
            throw new IOException("未能获取 Arthas 提示符");
        }
        return new Session(telnet, out, reader);
    }

    private void closeSession(Session session) {
        if (session == null) {
            return;
        }
        SESSIONS.remove(host + ":" + port, session);
        closeQuietly(session.client);
    }

    private static void closeQuietly(TelnetClient telnet) {
        try {
            telnet.disconnect();
        } catch (IOException ignored) {
        }
    }

    // ==================== 输出读取 ====================

    private record ReadResult(String text, boolean timedOut) {}

    /**
     * 阻塞读取直到遇到提示符。利用 socket SO_TIMEOUT 做短阻塞读，
     * 替代旧的 ready()+sleep 忙轮询；提示符只扫描尾部窗口，整体复杂度 O(n)。
     * 每次轮询检查 cancelCheck，保证进度条取消后 ≤300ms 内退出。
     */
    private static ReadResult readUntilPrompt(BufferedReader reader, int timeoutMs,
                                              java.util.function.BooleanSupplier cancelCheck) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[1024];
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            if (cancelCheck != null && cancelCheck.getAsBoolean()) {
                throw new IOException("命令已被用户取消");
            }
            try {
                int read = reader.read(buffer, 0, buffer.length);
                if (read == -1) {
                    break;
                }
                sb.append(buffer, 0, read);
                if (endsWithPrompt(sb)) {
                    return new ReadResult(sb.toString(), false);
                }
            } catch (SocketTimeoutException e) {
                // 本轮无数据，继续等 deadline
            }
        }
        return new ReadResult(sb.toString(), true);
    }

    static boolean endsWithPrompt(CharSequence text) {
        int from = Math.max(0, text.length() - PROMPT_WINDOW_CHARS);
        return PROMPT_PATTERN.matcher(text.subSequence(from, text.length())).find();
    }

    private static boolean hasPromptAtEnd(String output) {
        return output != null && PROMPT_PATTERN.matcher(output).find() && !output.isBlank();
    }

    private static String toAsyncCommand(String command) {
        String trimmed = command.stripTrailing();
        if (trimmed.endsWith("&")) {
            return trimmed;
        }
        return trimmed + " &";
    }

    /**
     * 判断是否是连接被拒绝错误
     */
    private static boolean isConnectionRefused(Exception e) {
        String message = e.getMessage();
        if (e instanceof SocketException) return true;
        if (message == null) return false;
        return message.contains("Connection refused") ||
               message.contains("Connection reset") ||
               message.contains("拒绝连接");
    }

    private static String cleanOutput(String raw) {
        return PROMPT_PATTERN.matcher(raw).replaceAll("").trim();
    }

    static boolean isPortOpen(String host, int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            // 显式连接超时：避免防火墙丢包时阻塞在系统默认超时（可达数分钟）
            socket.connect(new InetSocketAddress(host, port), PORT_PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 可复用的 telnet 会话 */
    private static final class Session {
        final TelnetClient client;
        final PrintStream out;
        final BufferedReader reader;

        Session(TelnetClient client, PrintStream out, BufferedReader reader) {
            this.client = client;
            this.out = out;
            this.reader = reader;
        }
    }
}
