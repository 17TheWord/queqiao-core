package io.github.theword.queqiao.core.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 最小 WebSocket 对端（仅服务端侧、仅文本/关闭帧）
 *
 * <p>存在的理由：{@code WebSocketClient.reconnect()} 的实现是 {@code reset(); connect();}，
 * 其中 {@code reset()} 会调用无超时的 {@code closeBlocking()}。要让"重连正在执行"这个窗口
 * <b>可控且可重复</b>，必须由对端决定何时回应 close 帧——Java-WebSocket 自带的
 * {@code WebSocketServer} 会立即回应，窗口短到无法稳定观测。
 *
 * <p>因此本类只做三件事：完成握手、记录收到的 close 帧、<b>在测试显式要求时才回应 close</b>。
 * 不解析业务消息、不实现 ping/pong，也不用于任何生产路径。
 */
public final class RawWebSocketPeer implements AutoCloseable {

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int OPCODE_CLOSE = 0x8;

    private final ServerSocket serverSocket;
    private final List<Connection> connections = Collections.synchronizedList(new ArrayList<Connection>());
    private final Thread acceptThread;
    private volatile boolean closed;

    public RawWebSocketPeer() throws IOException {
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        this.acceptThread = new Thread(this::acceptLoop, "RawWsPeer-Accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    public int getConnectionCount() {
        synchronized (connections) {
            return connections.size();
        }
    }

    /**
     * 等待累计建立指定数量的连接
     *
     * @param expected      期望数量
     * @param timeoutMillis 超时
     * @return 是否达到期望数量
     */
    public boolean awaitConnections(int expected, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (getConnectionCount() >= expected) {
                return true;
            }
            Thread.sleep(10L);
        }
        return getConnectionCount() >= expected;
    }

    /**
     * 等待指定连接收到 close 帧（即客户端主动发起关闭）
     *
     * @param index         连接序号（按 accept 顺序，从 0 开始）
     * @param timeoutMillis 超时
     * @return 是否收到 close 帧
     */
    public boolean awaitCloseFrame(int index, long timeoutMillis) throws InterruptedException {
        Connection connection = connectionAt(index);
        return connection != null && connection.closeFrameReceived.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 等待指定连接断开（读循环结束）
     *
     * @param index         连接序号
     * @param timeoutMillis 超时
     * @return 是否已断开
     */
    public boolean awaitDisconnected(int index, long timeoutMillis) throws InterruptedException {
        Connection connection = connectionAt(index);
        return connection != null && connection.disconnected.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 向指定连接发送一条文本帧（服务端帧不掩码）
     *
     * @param index 连接序号
     * @param text  文本内容
     */
    public void sendText(int index, String text) throws IOException {
        Connection connection = connectionAt(index);
        if (connection == null) {
            throw new IOException("连接 " + index + " 尚未建立");
        }
        connection.sendText(text);
    }

    /**
     * 回应指定连接上的 close 帧并结束该连接（模拟对端完成关闭握手）
     *
     * <p>在调用之前，客户端的 {@code closeBlocking()} 会一直等待——这正是测试要利用的窗口。
     *
     * @param index 连接序号
     */
    public void releaseClose(int index) {
        Connection connection = connectionAt(index);
        if (connection != null) {
            connection.releaseClose();
        }
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly(serverSocket);
        synchronized (connections) {
            for (Connection connection : connections) {
                connection.closeQuietly();
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private Connection connectionAt(int index) {
        synchronized (connections) {
            return index < connections.size() ? connections.get(index) : null;
        }
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = serverSocket.accept();
                Connection connection = new Connection(socket);
                synchronized (connections) {
                    connections.add(connection);
                }
                connection.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private static String readHandshake(InputStream input) throws IOException {
        StringBuilder builder = new StringBuilder();
        int value;
        while ((value = input.read()) >= 0) {
            builder.append((char) value);
            int length = builder.length();
            if (length >= 4
                    && builder.charAt(length - 4) == '\r'
                    && builder.charAt(length - 3) == '\n'
                    && builder.charAt(length - 2) == '\r'
                    && builder.charAt(length - 1) == '\n') {
                return builder.toString();
            }
        }
        throw new IOException("握手期间连接被关闭");
    }

    private static String headerValue(String request, String name) {
        for (String line : request.split("\r\n")) {
            int split = line.indexOf(':');
            if (split <= 0) {
                continue;
            }
            if (line.substring(0, split).trim().equalsIgnoreCase(name)) {
                return line.substring(split + 1).trim();
            }
        }
        return "";
    }

    private static byte[] handshakeResponse(String key) throws IOException {
        String accept;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            accept = Base64.getEncoder().encodeToString(
                    digest.digest((key + WS_GUID).getBytes(StandardCharsets.ISO_8859_1)));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("缺少 SHA-1 实现", e);
        }
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        return response.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new IOException("读取帧时连接被关闭");
        }
        return value;
    }

    private static void readFully(InputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = input.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                throw new IOException("读取帧负载时连接被关闭");
            }
            offset += read;
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // 关闭失败不影响测试断言
        }
    }

    /**
     * 单条连接：完成握手后只关心 close 帧
     */
    private final class Connection implements Runnable {

        private final Socket socket;
        private final CountDownLatch closeFrameReceived = new CountDownLatch(1);
        private final CountDownLatch closeReleased = new CountDownLatch(1);
        private final CountDownLatch disconnected = new CountDownLatch(1);

        private volatile OutputStream output;

        private Connection(Socket socket) {
            this.socket = socket;
        }

        private void sendText(String text) throws IOException {
            OutputStream stream = this.output;
            if (stream == null) {
                throw new IOException("握手尚未完成");
            }
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            synchronized (this) {
                stream.write(0x81);
                if (payload.length <= 125) {
                    stream.write(payload.length);
                } else {
                    stream.write(126);
                    stream.write((payload.length >> 8) & 0xFF);
                    stream.write(payload.length & 0xFF);
                }
                stream.write(payload);
                stream.flush();
            }
        }

        private void start() {
            Thread thread = new Thread(this, "RawWsPeer-Connection");
            thread.setDaemon(true);
            thread.start();
        }

        private void releaseClose() {
            closeReleased.countDown();
        }

        private void awaitRelease() {
            try {
                closeReleased.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void closeQuietly() {
            // 先释放等待中的帧循环，再关闭 Socket：Socket 关闭不会唤醒 CountDownLatch，
            // 若测试断言失败后直接 close()，连接线程会永久卡在 awaitRelease() 上。
            closeReleased.countDown();
            RawWebSocketPeer.closeQuietly(socket);
        }

        @Override
        public void run() {
            try {
                InputStream input = socket.getInputStream();
                String request = readHandshake(input);
                OutputStream output = socket.getOutputStream();
                output.write(handshakeResponse(headerValue(request, "Sec-WebSocket-Key")));
                output.flush();
                this.output = output;
                frameLoop(input, output);
            } catch (IOException ignored) {
                // 连接被任一方关闭
            } finally {
                closeQuietly();
                disconnected.countDown();
            }
        }

        private void frameLoop(InputStream input, OutputStream output) throws IOException {
            while (true) {
                int first = input.read();
                if (first < 0) {
                    return;
                }
                int second = readByte(input);
                int opcode = first & 0x0F;
                long length = second & 0x7F;
                if (length == 126) {
                    length = ((long) readByte(input) << 8) | readByte(input);
                } else if (length == 127) {
                    length = 0;
                    for (int i = 0; i < 8; i++) {
                        length = (length << 8) | readByte(input);
                    }
                }
                boolean masked = (second & 0x80) != 0;
                byte[] mask = new byte[4];
                if (masked) {
                    readFully(input, mask);
                }
                byte[] payload = new byte[(int) length];
                readFully(input, payload);

                if (opcode == OPCODE_CLOSE) {
                    closeFrameReceived.countDown();
                    // 刻意不立即回应：由测试决定何时完成关闭握手
                    awaitRelease();
                    output.write(new byte[]{(byte) 0x88, 0x00});
                    output.flush();
                    return;
                }
            }
        }
    }
}
