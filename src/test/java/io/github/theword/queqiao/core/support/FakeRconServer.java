package io.github.theword.queqiao.core.support;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 极简 RCON 测试对端
 *
 * <p>用于 <b>Runtime 级</b>生命周期测试：需要一条真实的 TCP RCON 服务，以便观察
 * "Runtime 是否真的建立了连接、命令发到了哪个实例、旧连接是否被中止"。
 *
 * <p>与 {@code RconClientProtocolIntegrationTest} 内的对端相比，本类不做逐字段的
 * 线级断言，只提供生命周期测试需要的观察点：
 * <ul>
 *     <li>{@link #awaitAuthenticationStarted()} / {@link #releaseAuthentication()}：
 *         让认证过程"卡住"，从而在外部观察启动与重载的中间状态；</li>
 *     <li>{@link #awaitCommand()}：命令是否到达本实例；</li>
 *     <li>{@link #awaitClosedConnections(int)}：对端是否关闭了连接（EOF）。</li>
 * </ul>
 */
public final class FakeRconServer implements AutoCloseable {

    private static final int TYPE_AUTH = 3;
    private static final int TYPE_AUTH_RESPONSE = 2;
    private static final int TYPE_EXEC_COMMAND = 2;
    private static final int TYPE_COMMAND_RESPONSE = 0;

    private static final int MAX_PACKET_LENGTH = 16 * 1024;
    private static final long WAIT_SECONDS = 5L;

    private final ServerSocket serverSocket;
    private final String response;
    private final boolean holdAuthentication;
    private final boolean stallCommands;

    private final CountDownLatch authenticationStarted = new CountDownLatch(1);
    private final CountDownLatch authenticationReleased = new CountDownLatch(1);
    private final BlockingQueue<String> receivedCommands = new LinkedBlockingQueue<>();
    private final AtomicInteger commandCount = new AtomicInteger();
    private final AtomicInteger closedConnections = new AtomicInteger();
    private final AtomicReference<Socket> activeSocket = new AtomicReference<>();
    private final Thread serverThread;

    /**
     * 在 {@code 127.0.0.1} 上监听
     *
     * @param response           命令响应文本
     * @param holdAuthentication true 表示收到 AUTH 后阻塞，直到 {@link #releaseAuthentication()}
     * @param stallCommands      true 表示收到命令后不返回响应，只等对端关闭连接（或被关闭）
     */
    public FakeRconServer(String response, boolean holdAuthentication, boolean stallCommands)
            throws IOException {
        this("127.0.0.1", response, holdAuthentication, stallCommands);
    }

    /**
     * @param bindHost           监听地址（用于验证"连接用的是哪个地址"）
     * @param response           命令响应文本
     * @param holdAuthentication true 表示收到 AUTH 后阻塞，直到 {@link #releaseAuthentication()}
     * @param stallCommands      true 表示收到命令后不返回响应，只等对端关闭连接（或被关闭）
     */
    public FakeRconServer(String bindHost, String response, boolean holdAuthentication, boolean stallCommands)
            throws IOException {
        this.response = response;
        this.holdAuthentication = holdAuthentication;
        this.stallCommands = stallCommands;
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getByName(bindHost));
        this.serverThread = new Thread(this::acceptLoop, "FakeRconServer");
        this.serverThread.setDaemon(true);
        this.serverThread.start();
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    public boolean awaitAuthenticationStarted() throws InterruptedException {
        return authenticationStarted.await(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * @return 累计收到的命令条数（用于断言"某段时间内没有新命令到达"）
     */
    public int getCommandCount() {
        return commandCount.get();
    }

    public void releaseAuthentication() {
        authenticationReleased.countDown();
    }

    /**
     * 等待下一条命令到达
     *
     * @return 命令文本；超时返回 null
     */
    public String awaitCommand() throws InterruptedException {
        return receivedCommands.poll(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 等待对端关闭至少 {@code expected} 条连接
     *
     * @param expected 期望的已关闭连接数
     * @return 是否在超时前达到
     */
    public boolean awaitClosedConnections(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (closedConnections.get() >= expected) {
                return true;
            }
            Thread.sleep(10L);
        }
        return closedConnections.get() >= expected;
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                return;
            }
            activeSocket.set(socket);
            try {
                serve(socket);
            } catch (IOException ignored) {
                // 对端关闭、读超时等都是测试中的正常路径
            } finally {
                activeSocket.compareAndSet(socket, null);
                closedConnections.incrementAndGet();
                closeQuietly(socket);
            }
        }
    }

    private void serve(Socket socket) throws IOException {
        socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(30));
        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();

        Packet authentication = readPacket(input);
        if (authentication == null) {
            return;
        }
        authenticationStarted.countDown();
        if (holdAuthentication) {
            try {
                authenticationReleased.await(2 * WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (authentication.type != TYPE_AUTH) {
            writePacket(output, -1, TYPE_AUTH_RESPONSE, "");
            return;
        }
        writePacket(output, authentication.requestId, TYPE_AUTH_RESPONSE, "");

        while (true) {
            Packet command = readPacket(input);
            if (command == null) {
                return;
            }
            receivedCommands.add(command.payload);
            commandCount.incrementAndGet();
            if (stallCommands) {
                // 不返回响应，但继续监视对端是否关闭：这样"连接被中止"能在毫秒级被观察到。
                awaitPeerClose(input);
                return;
            }
            writePacket(output, command.requestId, TYPE_COMMAND_RESPONSE, response);
        }
    }

    /**
     * 读取一个数据包
     *
     * @return 数据包；对端在包开始前关闭连接时返回 null
     */
    private static Packet readPacket(InputStream input) throws IOException {
        byte[] lengthBytes = new byte[4];
        if (!readFully(input, lengthBytes)) {
            return null;
        }
        int length = readIntLittleEndian(lengthBytes, 0);
        if (length < 10 || length > MAX_PACKET_LENGTH) {
            throw new IOException("Invalid RCON packet length: " + length);
        }

        byte[] body = new byte[length];
        if (!readFully(input, body)) {
            return null;
        }
        int requestId = readIntLittleEndian(body, 0);
        int type = readIntLittleEndian(body, 4);
        int payloadEnd = 8;
        while (payloadEnd < body.length && body[payloadEnd] != 0) {
            payloadEnd++;
        }
        String payload = new String(body, 8, Math.max(0, payloadEnd - 8), StandardCharsets.UTF_8);
        return new Packet(requestId, type, payload);
    }

    /**
     * @return false 表示流在读取到任何字节前就结束了
     */
    private static boolean readFully(InputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int count = input.read(buffer, offset, buffer.length - offset);
            if (count < 0) {
                if (offset == 0) {
                    return false;
                }
                throw new EOFException("RCON peer closed in the middle of a packet");
            }
            offset += count;
        }
        return true;
    }

    private static void writePacket(OutputStream output, int requestId, int type, String payload)
            throws IOException {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        int packetLength = 10 + payloadBytes.length;
        ByteArrayOutputStream packet = new ByteArrayOutputStream(packetLength + 4);
        writeIntLittleEndian(packet, packetLength);
        writeIntLittleEndian(packet, requestId);
        writeIntLittleEndian(packet, type);
        packet.write(payloadBytes);
        packet.write(0);
        packet.write(0);
        output.write(packet.toByteArray());
        output.flush();
    }

    private static int readIntLittleEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static void writeIntLittleEndian(OutputStream output, int value) throws IOException {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 24) & 0xFF);
    }

    /**
     * 等待对端关闭连接（不再返回任何数据）
     *
     * <p>用于模拟"命令已下发、服务端不返回响应"：
     * 对端一旦中止，read 立即返回 -1，而不需要等 socket 超时。
     */
    private static void awaitPeerClose(InputStream input) throws IOException {
        byte[] sink = new byte[256];
        while (input.read(sink) >= 0) {
            // 丢弃：本场景只关心连接是否仍然打开
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 关闭失败不影响测试结论
        }
    }

    @Override
    public void close() throws Exception {
        authenticationReleased.countDown();
        serverSocket.close();
        Socket socket = activeSocket.getAndSet(null);
        if (socket != null) {
            closeQuietly(socket);
        }
        serverThread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
    }

    private static final class Packet {

        private final int requestId;
        private final int type;
        private final String payload;

        private Packet(int requestId, int type, String payload) {
            this.requestId = requestId;
            this.type = type;
            this.payload = payload;
        }
    }
}
