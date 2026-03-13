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
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.commons.net.telnet.TelnetClient;

public class ArthasController {

    private static final String ARTHAS_JAR_NAME = "arthas-boot.jar";

    private static final int PORT = 3658;

    private static final Pattern PROMPT_PATTERN = Pattern.compile("\\[arthas@\\d+\\]\\$\\s*$");

    // 重试配置
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int RETRY_DELAY_MS = 1000;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int COMMAND_TIMEOUT_MS = 15000;

    private static File getArthasJar() throws IOException {
        File tempFile = new File(System.getProperty("java.io.tmpdir"), ARTHAS_JAR_NAME);
        try (InputStream inputStream = ArthasController.class.getResourceAsStream("/bin/" + ARTHAS_JAR_NAME)) {
            if (inputStream == null)
                throw new FileNotFoundException("未找到 " + ARTHAS_JAR_NAME);
            Files.copy(inputStream, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return tempFile;
    }

    /**
     * 检查 Arthas 是否已连接
     */
    public static boolean isArthasConnected() {
        return isPortOpen(PORT) && testConnection();
    }

    /**
     * 测试连接是否可用
     */
    private static boolean testConnection() {
        TelnetClient telnet = new TelnetClient();
        try {
            telnet.setConnectTimeout(2000);
            telnet.connect("127.0.0.1", PORT);
            telnet.disconnect();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static void attach(String pid) throws Exception {
        // 如果已经连接，直接返回
        if (isArthasConnected()) {
            return;
        }

        File jar = getArthasJar();

        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
        if (System.getProperty("os.name").toLowerCase().contains("win"))
            javaBin += ".exe";
        if (!new File(javaBin).exists())
            javaBin = "java";

        ProcessBuilder pb = new ProcessBuilder(
            javaBin, "-jar", jar.getAbsolutePath(),
            "--select", pid,
            "--telnet-port", String.valueOf(PORT),
            "--attach-only",
            "--repo-mirror", "aliyun"
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // 改进的等待逻辑：等待端口开放且连接可用
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            // 检查是否已连接成功
            if (isArthasConnected()) {
                return;
            }

            // 检查进程是否异常退出
            if (!process.isAlive()) {
                // 进程已结束，检查退出码
                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    // 读取错误输出
                    String errorOutput = readProcessOutput(process);
                    throw new RuntimeException("Attach 进程异常退出 (code: " + exitCode + "): " + errorOutput);
                }
                // 进程正常退出，继续等待连接建立
            }

            Thread.sleep(100);
        }

        // 超时
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        throw new RuntimeException("Attach 超时 (60秒)，无法连接到 Arthas");
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

    private static String sendCommandWithRetry(String command, int remainingAttempts) {
        TelnetClient telnet = new TelnetClient();
        try {
            // 确保连接超时设置
            telnet.setConnectTimeout(CONNECT_TIMEOUT_MS);
            telnet.connect("127.0.0.1", PORT);

            PrintStream out = new PrintStream(telnet.getOutputStream(), true, "UTF-8");
            InputStream in = telnet.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

            // 等待提示符出现
            String initialOutput = readUntilPrompt(reader);
            if (!PROMPT_PATTERN.matcher(initialOutput).find()) {
                throw new IOException("未能获取 Arthas 提示符");
            }

            out.println(command);
            out.flush();

            String rawResult = readUntilPrompt(reader);

            telnet.disconnect();

            return cleanOutput(rawResult);

        } catch (Exception e) {
            // 如果是连接被拒绝且还有重试次数，则重试
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
        }
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
        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[1024];
        long deadline = System.currentTimeMillis() + COMMAND_TIMEOUT_MS;

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
        // 移除最后一行提示符 [arthas@PID]$
        return raw.replaceAll("\\[arthas@\\d+\\]\\$\\s*$", "").trim();
    }

    private static boolean isPortOpen(int port) {
        try (java.net.Socket ignored = new java.net.Socket("127.0.0.1", port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
