package com.ignite.infra;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;
import org.apache.commons.net.telnet.TelnetClient;

public class ArthasController {

    private static final String ARTHAS_JAR_NAME = "arthas-boot.jar";
    private static final int PORT = 3658;
    // 匹配 Arthas 的命令提示符 [arthas@12345]$
    private static final Pattern PROMPT_PATTERN = Pattern.compile("\\[arthas@\\d+\\]\\$\\s*$");

    // ... getArthasJar 和 attach 方法保持不变 (参考上一次回答) ...
    // 为了节省篇幅，这里只展示修改后的 sendCommand 和 helper 方法

    // 请保留 getArthasJar 和 attach 的代码 ...
    private static File getArthasJar() throws IOException {
        File tempFile = new File(System.getProperty("java.io.tmpdir"), ARTHAS_JAR_NAME);
        try (InputStream inputStream = ArthasController.class.getResourceAsStream("/bin/" + ARTHAS_JAR_NAME)) {
            if (inputStream == null) throw new FileNotFoundException("未找到 " + ARTHAS_JAR_NAME);
            Files.copy(inputStream, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return tempFile;
    }

    public static void attach(String pid) throws Exception {
        if (isPortOpen(PORT)) return;
        File jar = getArthasJar();

        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
        if (System.getProperty("os.name").toLowerCase().contains("win")) javaBin += ".exe";
        if (!new File(javaBin).exists()) javaBin = "java";

        ProcessBuilder pb = new ProcessBuilder(
            javaBin, "-jar", jar.getAbsolutePath(),
            "--select", pid,
            "--telnet-port", String.valueOf(PORT),
            "--attach-only",
            "--repo-mirror", "aliyun"
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // 简单的等待逻辑
        long deadline = System.currentTimeMillis() + 60_000;
        while (process.isAlive()) {
            if (System.currentTimeMillis() > deadline) {
                process.destroy();
                throw new RuntimeException("Attach 超时");
            }
            if (isPortOpen(PORT)) return; // 端口开了就是成功了
            Thread.sleep(100);
        }
    }


    /**
     * 发送命令逻辑重构
     */
    public static String sendCommand(String command) {
        TelnetClient telnet = new TelnetClient();
        try {
            telnet.setConnectTimeout(3000);
            telnet.connect("127.0.0.1", PORT);

            PrintStream out = new PrintStream(telnet.getOutputStream(), true, "UTF-8");
            InputStream in = telnet.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

            // [核心逻辑修复]
            // 1. 先读取并丢弃连接成功后的 Welcome Banner，直到看到提示符
            readUntilPrompt(reader);

            // 2. 通道干净了，现在发送命令
            out.println(command);
            out.flush();

            // 3. 读取命令执行的真正结果
            String rawResult = readUntilPrompt(reader);

            telnet.disconnect();

            return cleanOutput(rawResult);

        } catch (Exception e) {
            e.printStackTrace();
            return "通信失败: " + e.getMessage();
        }
    }

    /**
     * 阻塞读取，直到遇到 Arthas 的命令提示符
     */
    private static String readUntilPrompt(BufferedReader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[1024];
        long deadline = System.currentTimeMillis() + 10_000; // 10秒超时

        while (System.currentTimeMillis() < deadline) {
            if (reader.ready()) {
                int read = reader.read(buffer, 0, buffer.length);
                if (read == -1) break;
                sb.append(buffer, 0, read);

                // 检查缓冲区末尾是否包含提示符
                if (PROMPT_PATTERN.matcher(sb.toString()).find()) {
                    return sb.toString();
                }
            } else {
                try { Thread.sleep(50); } catch (InterruptedException e) {}
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
