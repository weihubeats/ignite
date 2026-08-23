package com.ignite.infra;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ArthasTelnetClientTest {

    private FakeArthasServer server;
    private ArthasTelnetClient client;

    @Before
    public void setUp() throws IOException {
        server = new FakeArthasServer();
        server.start();
        client = new ArthasTelnetClient("127.0.0.1", server.port());
    }

    @After
    public void tearDown() throws IOException {
        server.close();
    }

    // ==================== 提示符识别 ====================

    @Test
    public void detectsPromptAtEnd() {
        assertTrue(ArthasTelnetClient.endsWithPrompt("[arthas@1234]$ "));
        assertTrue(ArthasTelnetClient.endsWithPrompt("output...\n[arthas@99]$"));
        assertFalse(ArthasTelnetClient.endsWithPrompt("[arthas@1234]$ partial output"));
        assertFalse(ArthasTelnetClient.endsWithPrompt(""));
    }

    @Test
    public void portProbeReturnsFalseForClosedPort() {
        long start = System.currentTimeMillis();
        boolean open = ArthasTelnetClient.isPortOpen("127.0.0.1", server.port() + 1);
        long elapsed = System.currentTimeMillis() - start;
        assertFalse(open);
        assertTrue("probe should fail fast, took " + elapsed + "ms", elapsed < 5000);
    }

    // ==================== 命令通信（本地伪 Arthas 服务） ====================

    @Test
    public void executeCommandReturnsResultAndWaitsForPrompt() throws Exception {
        server.respondWith("cmd-result-1");
        String result = client.executeCommand("vmtool --action getInstances", true);
        assertEquals("cmd-result-1", result);
        assertEquals(1, server.receivedCommands().size());
        assertEquals("vmtool --action getInstances", server.receivedCommands().get(0));
    }

    @Test
    public void reusesConnectionAcrossCommands() throws Exception {
        server.respondWith("first");
        assertEquals("first", client.executeCommand("c1", true));

        server.respondWith("second");
        assertEquals("second", client.executeCommand("c2", true));

        assertEquals("两条命令应复用同一连接", 1, server.connectionCount());
        assertEquals(2, server.receivedCommands().size());
    }

    @Test
    public void asyncCommandAppendsAmpersandAndReturnsFast() throws Exception {
        server.respondWith("");
        long start = System.currentTimeMillis();
        String result = client.executeCommand("watch * * '{params}'", false);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals("", result);
        assertTrue("async command should not wait for execution timeout", elapsed < 10000);
        assertEquals("watch * * '{params}' &", server.receivedCommands().get(0));
    }

    @Test
    public void cancelCheckAbortsSilentWaitQuickly() throws Exception {
        // 服务端收到命令后永不响应：模拟方法卡死/断点暂停
        server.silent = true;

        // 第 4 次轮询（约 0.9s 后）触发取消
        java.util.concurrent.atomic.AtomicInteger polls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.BooleanSupplier cancelCheck = () -> polls.getAndIncrement() >= 3;

        long start = System.currentTimeMillis();
        try {
            client.executeCommand("slowcmd", true, cancelCheck);
            fail("cancel should abort the wait");
        } catch (RuntimeException expected) {
            // 取消以异常形式终止等待
        }
        assertTrue("取消应秒级生效，实际耗时 " + (System.currentTimeMillis() - start) + "ms",
            System.currentTimeMillis() - start < 5000);
    }

    /**
     * 模拟 Arthas telnet 服务端：连接后发送提示符横幅，
     * 之后对每行输入回显结果 + 新提示符。
     */
    private static final class FakeArthasServer implements Runnable {
        private final ServerSocket serverSocket;
        private final CountDownLatch started = new CountDownLatch(1);
        private volatile Socket currentConnection;
        private BufferedReader currentReader;
        private PrintStream currentWriter;
        private volatile String response = "";
        /** 静默模式：收到命令后不回显结果也不输出提示符，模拟目标卡死 */
        volatile boolean silent;
        private final java.util.List<String> commands = new java.util.ArrayList<>();
        private int connections;
        private Thread acceptThread;

        FakeArthasServer() throws IOException {
            serverSocket = new ServerSocket(0, 8, java.net.InetAddress.getLoopbackAddress());
        }

        void start() {
            acceptThread = new Thread(this, "fake-arthas");
            acceptThread.setDaemon(true);
            acceptThread.start();
            try {
                started.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        synchronized void respondWith(String text) {
            response = text;
        }

        synchronized java.util.List<String> receivedCommands() {
            return new java.util.ArrayList<>(commands);
        }

        synchronized int connectionCount() {
            return connections;
        }

        @Override
        public void run() {
            try {
                while (!serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    synchronized (this) {
                        currentConnection = socket;
                        connections++;
                        currentReader = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        currentWriter = new PrintStream(socket.getOutputStream(), true, "UTF-8");
                    }
                    // 连接建立即输出欢迎横幅与提示符
                    currentWriter.print("[arthas@" + (10000 + connections) + "]$ ");
                    handleSession();
                }
            } catch (IOException ignored) {
                // server closed
            }
        }

        private void handleSession() {
            Thread t = new Thread(() -> {
                try {
                    String line;
                    while (currentConnection != null && !currentConnection.isClosed()
                        && (line = currentReader.readLine()) != null) {
                        String cmd = line.trim();
                        if (cmd.isEmpty()) {
                            continue;
                        }
                        synchronized (this) {
                            commands.add(cmd);
                        }
                        String resp;
                        boolean isSilent;
                        synchronized (this) {
                            resp = response;
                            isSilent = silent;
                        }
                        if (isSilent) {
                            continue; // 永不响应
                        }
                        if (!resp.isEmpty()) {
                            currentWriter.println(resp);
                        }
                        // 后续提示符同样必须是数字格式，与真实 Arthas 一致
                        currentWriter.print("[arthas@" + (20000 + connections) + "]$ ");
                    }
                } catch (IOException ignored) {
                }
            }, "fake-arthas-session");
            t.setDaemon(true);
            t.start();
        }

        void close() throws IOException {
            serverSocket.close();
            Socket s = currentConnection;
            if (s != null && !s.isClosed()) {
                s.close();
            }
        }
    }
}
