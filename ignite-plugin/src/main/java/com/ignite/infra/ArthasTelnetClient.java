package com.ignite.infra;

import com.intellij.openapi.diagnostic.Logger;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.apache.commons.net.telnet.TelnetClient;

/**
 * 负责 Arthas telnet 协议通信：连接、发送命令、读取提示符、重试。
 */
final class ArthasTelnetClient {

    private static final Logger LOG = Logger.getInstance(ArthasTelnetClient.class);

    private static final Pattern PROMPT_PATTERN = Pattern.compile("\\[arthas@\\d+\\]\\$\\s*$");

    // 重试配置
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int RETRY_DELAY_MS = 1000;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int COMMAND_TIMEOUT_MS = 15000;
    private static final int EXECUTION_TIMEOUT_MS = 120000;

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
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            TelnetClient telnet = new TelnetClient();
            try {
                telnet.setConnectTimeout(CONNECT_TIMEOUT_MS);
                telnet.connect(host, port);

                PrintStream out = new PrintStream(telnet.getOutputStream(), true, "UTF-8");
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(telnet.getInputStream(), StandardCharsets.UTF_8));

                String initialOutput = readUntilPrompt(reader);
                if (!PROMPT_PATTERN.matcher(initialOutput).find()) {
                    throw new IOException("未能获取 Arthas 提示符");
                }

                String commandToSend = awaitResult ? command : toAsyncCommand(command);
                out.println(commandToSend);
                out.flush();

                String rawResult = readUntilPrompt(reader, awaitResult ? EXECUTION_TIMEOUT_MS : COMMAND_TIMEOUT_MS);
                return cleanOutput(rawResult);

            } catch (Exception e) {
                lastError = e;
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
            } finally {
                try {
                    telnet.disconnect();
                } catch (IOException ignored) {
                }
            }
        }

        LOG.warn("Arthas 命令通信失败: " + command, lastError);
        if (awaitResult) {
            // 兼容旧调用方：错误以字符串形式返回
            return "通信失败: " + (lastError != null ? lastError.getMessage() : "unknown");
        }
        throw new RuntimeException("通信失败: " + (lastError != null ? lastError.getMessage() : "unknown"), lastError);
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

    /**
     * 阻塞读取，直到遇到 Arthas 的命令提示符
     */
    private static String readUntilPrompt(BufferedReader reader) throws IOException {
        return readUntilPrompt(reader, COMMAND_TIMEOUT_MS);
    }

    private static String readUntilPrompt(BufferedReader reader, int timeoutMs) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[1024];
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            if (reader.ready()) {
                int read = reader.read(buffer, 0, buffer.length);
                if (read == -1)
                    break;
                sb.append(buffer, 0, read);

                // 检查缓冲区末尾是否包含提示符
                if (PROMPT_PATTERN.matcher(sb.toString()).find()) {
                    return sb.toString();
                }
            } else {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return sb.toString();
    }

    private static String cleanOutput(String raw) {
        return PROMPT_PATTERN.matcher(raw).replaceAll("").trim();
    }

    static boolean isPortOpen(String host, int port) {
        try (java.net.Socket ignored = new java.net.Socket(host, port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
