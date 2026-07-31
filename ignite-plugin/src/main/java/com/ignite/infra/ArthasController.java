package com.ignite.infra;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;
import org.apache.commons.net.telnet.TelnetClient;

public class ArthasController {

    private static final String ARTHAS_BOOT_JAR = "arthas-boot.jar";
    private static final String ARTHAS_CORE_JAR = "arthas-core.jar";
    private static final String ARTHAS_AGENT_JAR = "arthas-agent.jar";
    private static final String ARTHAS_SPY_JAR = "arthas-spy.jar";
    private static final String ARTHAS_VERSION = "4.1.8";

    private static final int PORT = 3658;

    private static final Pattern PROMPT_PATTERN = Pattern.compile("\\[arthas@\\d+\\]\\$\\s*$");
    private static final DateTimeFormatter LOG_TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // 重试配置
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int RETRY_DELAY_MS = 1000;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int COMMAND_TIMEOUT_MS = 15000;
    private static final int EXECUTION_TIMEOUT_MS = 120000;

    // 连接状态缓存
    private static volatile boolean cachedConnectionStatus = false;
    private static volatile long lastConnectionCheckTime = 0;
    private static final long CONNECTION_CACHE_TTL_MS = 5000; // 缓存 5 秒
    private static volatile String connectedPid = null;

    // 超时与轮询配置
    private static final int ATTACH_PHASE1_DEADLINE_MS = 180_000;
    private static final int ATTACH_PHASE3_DEADLINE_MS = 30_000;
    private static final int PHASE1_SLEEP_MS = 100;
    private static final int PHASE3_SLEEP_MS = 500;
    private static final int PHASE1_CONNECT_CHECK_INTERVAL = 10;
    private static final int PHASE1_LOG_INTERVAL = 100;
    private static final int PHASE3_LOG_INTERVAL = 5;

    private static File getArthasJar() throws IOException {
        // 使用固定的插件目录，避免每次重启都重新解压
        File pluginDir = getArthasHomeDir();
        File arthasJar = new File(pluginDir, ARTHAS_BOOT_JAR);

        // 检查是否需要更新（通过检查 version 文件）
        File versionFile = new File(pluginDir, ".version");
        boolean needExtract = !arthasJar.exists() ||
                              !versionFile.exists() ||
                              !ARTHAS_VERSION.equals(readVersionFile(versionFile));

        if (!needExtract) {
            return arthasJar;
        }

        // 需要解压或更新版本
        System.out.println("[Ignite] 解压 Arthas " + ARTHAS_VERSION + " 到 " + pluginDir);
        pluginDir.mkdirs();
        extractJarFromResources(ARTHAS_BOOT_JAR, arthasJar);
        extractJarFromResources(ARTHAS_CORE_JAR, new File(pluginDir, ARTHAS_CORE_JAR));
        extractJarFromResources(ARTHAS_AGENT_JAR, new File(pluginDir, ARTHAS_AGENT_JAR));
        extractJarFromResources(ARTHAS_SPY_JAR, new File(pluginDir, ARTHAS_SPY_JAR));

        // 解压 lib 目录下的 native library（vmtool 需要）
        extractNativeLib(pluginDir);

        writeVersionFile(versionFile, ARTHAS_VERSION);

        return arthasJar;
    }

    private static String readVersionFile(File versionFile) {
        try {
            return new String(Files.readAllBytes(versionFile.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeVersionFile(File versionFile, String version) {
        try {
            Files.write(versionFile.toPath(), version.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[Ignite] 无法写入版本文件: " + e.getMessage());
        }
    }

    /**
     * 解压 native library（vmtool 需要）
     */
    private static void extractNativeLib(File pluginDir) {
        File libDir = new File(pluginDir, "lib");
        libDir.mkdirs();

        // 建议把所有支持的架构库全量释出，让 Arthas 自行匹配加载
        // 包含无后缀的 dylib（resources 中实际文件名），确保 macOS 能解压
        String[] libFiles = {
            "libArthasJniLibrary.dylib",
            "libArthasJniLibrary-x86_64.dylib",
            "libArthasJniLibrary-aarch64.dylib",
            "libArthasJniLibrary-x64.dll",
            "libArthasJniLibrary-x86.dll",
            "libArthasJniLibrary-x64.so",
            "libArthasJniLibrary-aarch64.so",
            "libArthasJniLibrary-arm.so"
        };

        for (String libFileName : libFiles) {
            File targetLib = new File(libDir, libFileName);
            try {
                // 请确保你的 IDEA 插件 resources/bin/lib/ 目录下确实包含了这些文件
                extractJarFromResources("lib/" + libFileName, targetLib);
            } catch (FileNotFoundException e) {
                System.out.println("[Ignite] Native lib not found in resources, skip: " + libFileName);
            } catch (IOException e) {
                System.err.println("[Ignite] 无法解压 native lib " + libFileName + ": " + e.getMessage());
            }
        }
        System.out.println("[Ignite] Native libs 解压完成: " + libDir.getAbsolutePath());
    }

    private static void extractJarFromResources(String jarName, File targetFile) throws IOException {
        // 如果文件已存在，先删除
        if (targetFile.exists()) {
            targetFile.delete();
        }

        try (InputStream inputStream = ArthasController.class.getResourceAsStream("/bin/" + jarName)) {
            if (inputStream == null)
                throw new FileNotFoundException("未找到 " + jarName);
            Files.copy(inputStream, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        // 设置可执行权限（Unix/Linux/Mac）
        targetFile.setExecutable(true, false);
        targetFile.setReadable(true, false);
        targetFile.setWritable(true, true);
    }

    /**
     * 获取 Arthas 主目录（固定路径，避免重复下载）。
     * 使用 user.home 而非 java.io.tmpdir，确保插件进程与目标 JVM 进程（Run/Debug）
     * 看到同一路径，目标进程才能通过 --libPath 访问已解压的 native 库。
     */
    private static File getArthasHomeDir() {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isEmpty()) {
            userHome = System.getProperty("java.io.tmpdir");
        }
        File arthasDir = new File(userHome, ".ignite-arthas-" + ARTHAS_VERSION);

        // 确保目录存在且有权限
        if (!arthasDir.exists()) {
            arthasDir.mkdirs();
            arthasDir.setReadable(true, false);
            arthasDir.setWritable(true, true);
            arthasDir.setExecutable(true, false);
        }

        return arthasDir;
    }

    /**
     * 获取/预解压 Arthas JAR 到本地目录。
     * 在 IDE 启动阶段异步调用，避免首次用户运行时等待解压。
     */
    public static String getArthasJarPath() throws IOException {
        return getArthasJar().getAbsolutePath();
    }

    public static String getArthasLibPath() {
        File libDir = new File(getArthasHomeDir(), "lib");
        String libFileName = getNativeLibFileName();
        return new File(libDir, libFileName).getAbsolutePath();
    }

    /**
     * 按当前 OS/arch 返回已解压的 native 库文件名（与 extractNativeLib 中一致）。
     */
    private static String getNativeLibFileName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (os.contains("mac")) {
            return "libArthasJniLibrary.dylib";
        }
        if (os.contains("win")) {
            return (arch.contains("aarch64") || arch.contains("64")) ? "libArthasJniLibrary-x64.dll" : "libArthasJniLibrary-x86.dll";
        }
        if (arch.contains("aarch64") || arch.contains("arm")) {
            return "libArthasJniLibrary-aarch64.so";
        }
        return "libArthasJniLibrary-x64.so";
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
        long now = System.currentTimeMillis();

        // 检查缓存是否有效
        if (now - lastConnectionCheckTime < CONNECTION_CACHE_TTL_MS) {
            // 如果指定了 PID，检查是否匹配
            if (expectedPid != null && !expectedPid.equals(connectedPid)) {
                return false;
            }
            return cachedConnectionStatus;
        }

        // 缓存过期，重新检测
        boolean isConnected = checkActualConnection();
        cachedConnectionStatus = isConnected;
        lastConnectionCheckTime = now;

        // 如果断开连接，清除 PID 记录
        if (!isConnected) {
            connectedPid = null;
        }

        return isConnected;
    }

    /**
     * 实际检测连接状态
     */
    private static boolean checkActualConnection() {
        return isPortOpen(PORT);
    }

    public static void attach(String pid, IgniteConsoleService console) throws Exception {
        if (isArthasConnected(pid)) {
            log(console, "Arthas 已连接到 PID " + pid + "，跳过 attach");
            return;
        }

        log(console, "开始 attach 到 PID: " + pid);

        File jar = getArthasJar();
        log(console, "Arthas jar 路径: " + jar.getAbsolutePath());

        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
        if (System.getProperty("os.name").toLowerCase().contains("win"))
            javaBin += ".exe";
        if (!new File(javaBin).exists())
            javaBin = "java";

        log(console, "使用 Java: " + javaBin);

        File arthasHome = getArthasHomeDir();
        log(console, "Arthas 主目录: " + arthasHome.getAbsolutePath());

        File coreJar = new File(arthasHome, ARTHAS_CORE_JAR);
        File agentJar = new File(arthasHome, ARTHAS_AGENT_JAR);
        File spyJar = new File(arthasHome, ARTHAS_SPY_JAR);

        File libDir = new File(arthasHome, "lib");
        String libraryPath = libDir.exists() ? libDir.getAbsolutePath() : "";

        ProcessBuilder pb;
        if (coreJar.exists() && agentJar.exists() && spyJar.exists()) {
            log(console, "使用本地 Arthas jar 文件: " + coreJar.getAbsolutePath());
            pb = new ProcessBuilder(
                javaBin,
                "-Djava.library.path=" + libraryPath,
                "-jar", jar.getAbsolutePath(),
                "--select", pid,
                "--telnet-port", String.valueOf(PORT),
                "--target-ip", "127.0.0.1",
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
                "--target-ip", "127.0.0.1",
                "--attach-only"
            );
        }
        pb.environment().put("ARTHAS_HOME", arthasHome.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process process = pb.start();

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
                if (checkActualConnection()) {
                    connectedPid = pid;
                    cachedConnectionStatus = true;
                    lastConnectionCheckTime = System.currentTimeMillis();
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
            try {
                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    String errorOutput = readProcessOutput(process);
                    log(console, "Attach 进程异常退出 (code: " + exitCode + "): " + errorOutput);
                    throw new RuntimeException("Attach 进程异常退出 (code: " + exitCode + "): " + errorOutput);
                }
                log(console, "Arthas attach 进程已退出，检测服务是否就绪...");
            } catch (IllegalThreadStateException e) {
                throw new RuntimeException("Attach 进程状态异常: " + e.getMessage(), e);
            }
        }

        // Phase 3: 进程退出后，集中检测端口是否开放（最多 30s）
        long postDeadline = System.currentTimeMillis() + ATTACH_PHASE3_DEADLINE_MS;
        long phase3Start = System.currentTimeMillis();
        int postCheckCount = 0;
        while (System.currentTimeMillis() < postDeadline && !Thread.currentThread().isInterrupted()) {
            postCheckCount++;
            if (checkActualConnection()) {
                connectedPid = pid;
                cachedConnectionStatus = true;
                lastConnectionCheckTime = System.currentTimeMillis();
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
        return connectedPid;
    }

    /**
     * 清除连接缓存（用于强制重新检测）
     */
    public static void invalidateConnectionCache() {
        cachedConnectionStatus = false;
        lastConnectionCheckTime = 0;
        connectedPid = null;
    }


    /**
     * 兼容旧版本，不带日志
     */
    public static void attach(String pid) throws Exception {
        attach(pid, null);
    }

    /**
     * 日志输出辅助方法
     */
    private static void log(IgniteConsoleService console, String message) {
        String timestamp = LOG_TS.format(LocalDateTime.now());
        String logMessage = "[" + timestamp + "] [Arthas] " + message;
        System.out.println(logMessage);
        if (console != null) {
            console.printLog(logMessage);
        }
    }

    /**
     * 读取进程的错误输出
     */
    private static String readProcessOutput(Process process) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && sb.length() < 1000) {
                sb.append(line).append("\n");
            }
            return sb.toString().trim();
        } catch (IOException e) {
            return "无法读取错误输出: " + e.getMessage();
        }
    }

    /**
     * 发送命令，带重试机制
     */
    public static String sendCommand(String command) {
        return sendCommandWithRetry(command, MAX_RETRY_ATTEMPTS);
    }

    /**
     * 发送命令但不等待方法返回结果，避免在 debug 断点场景下阻塞。
     */
    public static void sendCommandWithoutResult(String command) {
        sendCommandWithoutResultWithRetry(command, MAX_RETRY_ATTEMPTS);
    }

    private static String sendCommandWithRetry(String command, int remainingAttempts) {
        TelnetClient telnet = new TelnetClient();
        try {
            telnet.setConnectTimeout(CONNECT_TIMEOUT_MS);
            telnet.connect("127.0.0.1", PORT);

            PrintStream out = new PrintStream(telnet.getOutputStream(), true, "UTF-8");
            InputStream in = telnet.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

            String initialOutput = readUntilPrompt(reader);
            if (!PROMPT_PATTERN.matcher(initialOutput).find()) {
                throw new IOException("未能获取 Arthas 提示符");
            }

            out.println(command);
            out.flush();

            String rawResult = readUntilPrompt(reader, EXECUTION_TIMEOUT_MS);

            return cleanOutput(rawResult);

        } catch (Exception e) {
            if (remainingAttempts > 1 && isConnectionRefused(e)) {
                System.out.println("[Ignite] Connection refused, retrying... (" + (MAX_RETRY_ATTEMPTS - remainingAttempts + 1) + "/" + MAX_RETRY_ATTEMPTS + ")");
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return sendCommandWithRetry(command, remainingAttempts - 1);
            }
            e.printStackTrace();
            return "通信失败: " + e.getMessage();
        } finally {
            try {
                telnet.disconnect();
            } catch (IOException ignored) {
            }
        }
    }

    private static void sendCommandWithoutResultWithRetry(String command, int remainingAttempts) {
        TelnetClient telnet = new TelnetClient();
        try {
            telnet.setConnectTimeout(CONNECT_TIMEOUT_MS);
            telnet.connect("127.0.0.1", PORT);

            PrintStream out = new PrintStream(telnet.getOutputStream(), true, "UTF-8");
            InputStream in = telnet.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

            String initialOutput = readUntilPrompt(reader);
            if (!PROMPT_PATTERN.matcher(initialOutput).find()) {
                throw new IOException("未能获取 Arthas 提示符");
            }

            String asyncCommand = toAsyncCommand(command);
            out.println(asyncCommand);
            out.flush();

            readUntilPrompt(reader);

        } catch (Exception e) {
            if (remainingAttempts > 1 && isConnectionRefused(e)) {
                System.out.println("[Ignite] Connection refused, retrying... (" + (MAX_RETRY_ATTEMPTS - remainingAttempts + 1) + "/" + MAX_RETRY_ATTEMPTS + ")");
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                sendCommandWithoutResultWithRetry(command, remainingAttempts - 1);
                return;
            }
            throw new RuntimeException("通信失败: " + e.getMessage(), e);
        } finally {
            try {
                telnet.disconnect();
            } catch (IOException ignored) {
            }
        }
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
        if (message == null) return false;
        return message.contains("Connection refused") ||
               message.contains("Connection reset") ||
               message.contains("拒绝连接") ||
               e instanceof SocketException;
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

    private static boolean isPortOpen(int port) {
        try (java.net.Socket ignored = new java.net.Socket("127.0.0.1", port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
