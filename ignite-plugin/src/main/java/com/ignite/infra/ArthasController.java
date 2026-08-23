package com.ignite.infra;

import com.intellij.openapi.diagnostic.Logger;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Arthas 连接生命周期入口：连接状态缓存与 attach 流程。
 * 发行包管理见 {@link ArthasInstaller}，telnet 通信见 {@link ArthasTelnetClient}。
 */
public class ArthasController {

    private static final Logger LOG = Logger.getInstance(ArthasController.class);

    private static final int PORT = 3658;
    private static final String TARGET_IP = "127.0.0.1";

    private static final DateTimeFormatter LOG_TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // 超时与轮询配置
    private static final int ATTACH_PHASE1_DEADLINE_MS = 180_000;
    private static final int ATTACH_PHASE3_DEADLINE_MS = 30_000;
    private static final int PHASE1_SLEEP_MS = 100;
    private static final int PHASE3_SLEEP_MS = 500;
    private static final int PHASE1_CONNECT_CHECK_INTERVAL = 10;
    private static final int PHASE1_LOG_INTERVAL = 100;
    private static final int PHASE3_LOG_INTERVAL = 5;

    /**
     * 连接状态快照：不可变对象整体替换，保证多线程读到的是一致的 (connected, pid, checkedAt) 组合。
     */
    private static final class ConnectionState {
        static final ConnectionState DISCONNECTED = new ConnectionState(false, null, 0);

        final boolean connected;
        final String pid;
        final long checkedAt;

        ConnectionState(boolean connected, String pid, long checkedAt) {
            this.connected = connected;
            this.pid = pid;
            this.checkedAt = checkedAt;
        }
    }

    private static final AtomicReference<ConnectionState> connectionState =
        new AtomicReference<>(ConnectionState.DISCONNECTED);
    private static final long CONNECTION_CACHE_TTL_MS = 5000; // 缓存 5 秒

    /**
     * 获取/预解压 Arthas JAR 到本地目录。
     * 在 IDE 启动阶段异步调用，避免首次用户运行时等待解压。
     */
    public static String getArthasJarPath() throws IOException {
        return ArthasInstaller.getArthasJar().getAbsolutePath();
    }

    public static String getArthasLibPath() {
        return ArthasInstaller.getLibPath();
    }

    /**
     * 检查 Arthas 是否已连接（带缓存）
     */
    public static boolean isArthasConnected() {
        return isArthasConnected(null);
    }

    /**
     * 检查 Arthas 是否已连接到指定 PID（带缓存）
     */
    public static boolean isArthasConnected(String expectedPid) {
        ConnectionState state = connectionState.get();
        long now = System.currentTimeMillis();

        // 检查缓存是否有效
        if (now - state.checkedAt < CONNECTION_CACHE_TTL_MS) {
            // 如果指定了 PID，检查是否匹配
            if (expectedPid != null && !expectedPid.equals(state.pid)) {
                return false;
            }
            return state.connected;
        }

        // 缓存过期，重新检测；并发时多个线程可能同时探测端口，结果幂等无副作用
        boolean isConnected = ArthasTelnetClient.isPortOpen(TARGET_IP, PORT);
        String pid = isConnected ? (state.pid != null ? state.pid : expectedPid) : null;
        connectionState.set(new ConnectionState(isConnected, pid, now));

        return isConnected;
    }

    private static void markConnected(String pid) {
        connectionState.set(new ConnectionState(true, pid, System.currentTimeMillis()));
    }

    public static void attach(String pid, IgniteConsoleService console) throws Exception {
        if (isArthasConnected(pid)) {
            log(console, "Arthas 已连接到 PID " + pid + "，跳过 attach");
            return;
        }

        log(console, "开始 attach 到 PID: " + pid);

        File jar = ArthasInstaller.getArthasJar();
        log(console, "Arthas jar 路径: " + jar.getAbsolutePath());

        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
        if (System.getProperty("os.name").toLowerCase().contains("win"))
            javaBin += ".exe";
        if (!new File(javaBin).exists())
            javaBin = "java";

        log(console, "使用 Java: " + javaBin);

        File arthasHome = ArthasInstaller.getArthasHomeDir();
        log(console, "Arthas 主目录: " + arthasHome.getAbsolutePath());

        File libDir = new File(arthasHome, "lib");
        String libraryPath = libDir.exists() ? libDir.getAbsolutePath() : "";

        boolean hasLocalJars = ArthasInstaller.getCoreJar().exists()
            && ArthasInstaller.getAgentJar().exists()
            && ArthasInstaller.getSpyJar().exists();

        ProcessBuilder pb;
        if (hasLocalJars) {
            log(console, "使用本地 Arthas jar 文件: " + ArthasInstaller.getCoreJar().getAbsolutePath());
            pb = new ProcessBuilder(
                javaBin,
                "-Djava.library.path=" + libraryPath,
                "-jar", jar.getAbsolutePath(),
                "--select", pid,
                "--telnet-port", String.valueOf(PORT),
                "--target-ip", TARGET_IP,
                "--attach-only",
                "--arthas-home", arthasHome.getAbsolutePath()
            );
        } else {
            log(console, "本地 Arthas jar 不存在，需要下载...");
            pb = new ProcessBuilder(
                javaBin,
                "-Djava.library.path=" + libraryPath,
                "-jar", jar.getAbsolutePath(),
                "--select", pid,
                "--telnet-port", String.valueOf(PORT),
                "--target-ip", TARGET_IP,
                "--attach-only"
            );
        }
        pb.environment().put("ARTHAS_HOME", arthasHome.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // 持续消费进程输出：避免输出缓冲区写满导致进程阻塞，同时留存输出用于错误诊断
        ProcessOutputGobbler gobbler = new ProcessOutputGobbler(process, console);
        gobbler.start();

        log(console, "Arthas attach 进程已启动，等待连接...");

        // 记录各阶段开始时间，用于打印已等待时长
        long phase1Start = System.currentTimeMillis();
        long deadline = System.currentTimeMillis() + ATTACH_PHASE1_DEADLINE_MS;
        int checkCount = 0;

        // Phase 1: 进程未退出时，仅偶发检查连接，主要等待进程完成
        while (System.currentTimeMillis() < deadline && process.isAlive()
                && !Thread.currentThread().isInterrupted()) {
            checkCount++;
            if (checkCount % PHASE1_CONNECT_CHECK_INTERVAL == 0) {
                if (ArthasTelnetClient.isPortOpen(TARGET_IP, PORT)) {
                    markConnected(pid);
                    log(console, "Arthas 连接成功 (Phase 1, 尝试 " + checkCount + " 次)");
                    return;
                }
                if (checkCount % PHASE1_LOG_INTERVAL == 0) {
                    long elapsed = (System.currentTimeMillis() - phase1Start) / 1000;
                    log(console, "Arthas 下载/启动中... (已等待 " + elapsed + " 秒)");
                }
            }
            Thread.sleep(PHASE1_SLEEP_MS);
        }
        if (Thread.currentThread().isInterrupted()) {
            process.destroyForcibly();
            throw new InterruptedException("Attach cancelled");
        }

        // Phase 2: 进程退出，读取状态
        if (!process.isAlive()) {
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String errorOutput = gobbler.awaitOutput();
                log(console, "Attach 进程异常退出 (code: " + exitCode + "): " + errorOutput);
                throw new RuntimeException("Attach 进程异常退出 (code: " + exitCode + "): " + errorOutput);
            }
            log(console, "Arthas attach 进程已退出，检测服务是否就绪...");
        }

        // Phase 3: 进程退出后，集中检测端口是否开放（最多 30s）
        long postDeadline = System.currentTimeMillis() + ATTACH_PHASE3_DEADLINE_MS;
        long phase3Start = System.currentTimeMillis();
        int postCheckCount = 0;
        while (System.currentTimeMillis() < postDeadline && !Thread.currentThread().isInterrupted()) {
            postCheckCount++;
            if (ArthasTelnetClient.isPortOpen(TARGET_IP, PORT)) {
                markConnected(pid);
                log(console, "Arthas 连接成功 (Phase 3, 尝试 " + postCheckCount + " 次, 总计 " + (checkCount + postCheckCount) + " 次)");
                return;
            }
            if (postCheckCount % PHASE3_LOG_INTERVAL == 0) {
                long elapsed = (System.currentTimeMillis() - phase3Start) / 1000;
                log(console, "等待 Arthas 服务就绪... (已等待 " + elapsed + " 秒)");
            }
            Thread.sleep(PHASE3_SLEEP_MS);
        }
        if (Thread.currentThread().isInterrupted()) {
            process.destroyForcibly();
            throw new InterruptedException("Attach cancelled");
        }

        if (process.isAlive()) {
            process.destroyForcibly();
        }
        log(console, "Attach 超时，共检测 " + (checkCount + postCheckCount) + " 次");
        throw new RuntimeException("Attach 超时，无法连接到 Arthas。可能是首次下载 Arthas 较慢，请检查网络或手动下载 Arthas");
    }

    /**
     * 获取当前连接的 PID
     */
    public static String getConnectedPid() {
        return connectionState.get().pid;
    }

    /**
     * 清除连接缓存（用于强制重新检测）
     */
    public static void invalidateConnectionCache() {
        connectionState.set(ConnectionState.DISCONNECTED);
    }

    /**
     * 兼容旧版本，不带日志
     */
    public static void attach(String pid) throws Exception {
        attach(pid, null);
    }

    /**
     * 发送命令，带重试机制
     */
    public static String sendCommand(String command) {
        return telnetClient().executeCommand(command, true);
    }

    /**
     * 发送命令但不等待方法返回结果，避免在 debug 断点场景下阻塞。
     */
    public static void sendCommandWithoutResult(String command) {
        telnetClient().executeCommand(command, false);
    }

    private static ArthasTelnetClient telnetClient() {
        return new ArthasTelnetClient(TARGET_IP, PORT);
    }

    /**
     * 日志输出辅助方法
     */
    private static void log(IgniteConsoleService console, String message) {
        String timestamp = LOG_TS.format(LocalDateTime.now());
        String logMessage = "[" + timestamp + "] [Arthas] " + message;
        LOG.info(message);
        if (console != null) {
            console.printLog(logMessage);
        }
    }

    /**
     * 后台线程持续读取 attach 进程输出，防止缓冲区写满阻塞进程。
     */
    private static final class ProcessOutputGobbler extends Thread {
        private static final int MAX_CAPTURED_CHARS = 2000;

        private final Process process;
        private final IgniteConsoleService console;
        private final StringBuilder captured = new StringBuilder();

        ProcessOutputGobbler(Process process, IgniteConsoleService console) {
            super("Ignite-Arthas-Output");
            setDaemon(true);
            this.process = process;
            this.console = console;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (console != null) {
                        console.printLog("[Arthas] " + line);
                    }
                    if (captured.length() < MAX_CAPTURED_CHARS) {
                        captured.append(line, 0, Math.min(line.length(), MAX_CAPTURED_CHARS - captured.length()))
                            .append('\n');
                    }
                }
            } catch (IOException e) {
                LOG.info("读取 attach 进程输出中断: " + e.getMessage());
            }
        }

        /** 等待输出线程结束（最多 2 秒）并返回捕获的输出 */
        String awaitOutput() {
            try {
                join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return captured.toString().trim();
        }
    }
}
