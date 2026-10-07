package io.github.theword.queqiao.core.rcon;

import io.github.theword.queqiao.core.exception.rcon.RconException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wire-level tests against a small local TCP peer that speaks Minecraft RCON. */
class RconClientProtocolIntegrationTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(RconClientProtocolIntegrationTest.class);
    private static final String PASSWORD = "test-rcon-password";
    private static final long WAIT_SECONDS = 5L;

    @Test
    @DisplayName("真实 RCON TCP 握手认证并执行命令，返回服务端响应")
    void authenticatesAndExecutesCommandOverTcp() throws Exception {
        try (FakeRconServer server = new FakeRconServer(PASSWORD, "There are 3 players online", false)) {
            RconClient client = new RconClient(LOGGER, server.getPort(), PASSWORD);
            try {
                client.connect();

                assertTrue(client.isConnected(), "认证成功后客户端应处于连接状态");
                assertTrue(server.awaitAuthenticationRequest(), "测试服务端应收到认证数据包");
                assertTrue(server.awaitAuthentication(), "测试服务端应接受正确密码");
                assertEquals(3, server.getAuthenticationPacketType(), "客户端应发送 AUTH 数据包");
                assertEquals(PASSWORD, server.getAuthenticationPayload(), "认证包应携带配置密码");

                assertEquals("There are 3 players online", client.sendCommand("list"));
                assertTrue(server.awaitCommand(), "测试服务端应收到命令数据包");
                assertEquals(2, server.getCommandPacketType(), "客户端应发送 EXEC_COMMAND 数据包");
                assertEquals("list", server.getLastCommand());
                assertEquals(
                        server.getLastCommandRequestId(), server.getLastResponseId(),
                        "服务端响应应使用命令请求的 ID");
            } finally {
                client.stop();
            }
        }
    }

    @Test
    @DisplayName("RCON 认证失败时客户端不发布连接，后续命令明确失败")
    void wrongPasswordLeavesClientDisconnected() throws Exception {
        try (FakeRconServer server = new FakeRconServer("expected-password", "unused", false)) {
            RconClient client = new RconClient(LOGGER, server.getPort(), "wrong-password");
            try {
                client.connect();

                assertTrue(server.awaitAuthenticationRequest(), "测试服务端应收到认证请求");
                assertTrue(server.awaitAuthenticationRejected(), "测试服务端应拒绝错误密码");
                assertFalse(client.isConnected(), "认证失败后客户端不能保留连接");
                RconException failure = assertThrows(RconException.class, () -> client.sendCommand("list"));
                assertEquals(RconException.Kind.DISCONNECTED, failure.getKind());
            } finally {
                client.stop();
            }
        }
    }

    @Test
    @DisplayName("命令过程中 TCP 对端断开时清理失效连接并映射为 COMMAND_FAILED")
    void commandDisconnectInvalidatesConnection() throws Exception {
        try (FakeRconServer server = new FakeRconServer(PASSWORD, "unused", true)) {
            RconClient client = new RconClient(LOGGER, server.getPort(), PASSWORD);
            try {
                client.connect();
                assertTrue(client.isConnected(), "测试前应先建立连接");

                RconException failure = assertThrows(RconException.class, () -> client.sendCommand("list"));
                assertEquals(RconException.Kind.COMMAND_FAILED, failure.getKind());
                assertTrue(server.awaitCommand(), "测试服务端应先收到命令再断开");
                assertFalse(client.isConnected(), "I/O 失败后客户端不应继续报告已连接");

                RconException subsequent = assertThrows(RconException.class, () -> client.sendCommand("list"));
                assertEquals(RconException.Kind.DISCONNECTED, subsequent.getKind());
            } finally {
                client.stop();
            }
        }
    }

    @Test
    @DisplayName("1446 字节的 tellraw 命令可完整发送，超长命令在发送前拒绝")
    void commandLengthUsesMinecraftRconByteLimit() throws Exception {
        String prefix = "/tellraw @a {\"text\":\"";
        String suffix = "\"}";
        // Java 8：String.repeat 是 Java 11+ API，此处手工构造填充串
        int fillerLength = 1446 - prefix.length() - suffix.length();
        StringBuilder filler = new StringBuilder(fillerLength);
        for (int i = 0; i < fillerLength; i++) {
            filler.append('x');
        }
        String maxCommand = prefix + filler + suffix;
        assertEquals(1446, maxCommand.getBytes(StandardCharsets.UTF_8).length);
        try (FakeRconServer server = new FakeRconServer(PASSWORD, "ok", false)) {
            RconClient client = new RconClient(LOGGER, server.getPort(), PASSWORD);
            try {
                client.connect();
                assertEquals("ok", client.sendCommand(maxCommand));
                assertTrue(server.awaitCommand(), "服务端应收到最大长度命令");
                assertEquals(maxCommand, server.getLastCommand(), "命令应按原字节内容完整传输");

                String tooLongCommand = maxCommand + "x";
                RconException error = assertThrows(RconException.class, () -> client.sendCommand(tooLongCommand));
                assertEquals(RconException.Kind.INVALID_COMMAND, error.getKind());
                assertTrue(client.isConnected(), "本地长度校验不应破坏现有连接");
            } finally {
                client.stop();
            }
        }
    }

    @Test
    @DisplayName("服务端不响应时读超时映射为 COMMAND_FAILED 并清理连接")
    void responseTimeoutInvalidatesConnection() throws Exception {
        try (FakeRconServer server = new FakeRconServer(PASSWORD, "unused", FakeRconServer.CommandMode.STALL)) {
            RconClient client = new RconClient(LOGGER, server.getPort(), PASSWORD);
            try {
                client.connect();
                RconException error = assertThrows(RconException.class, () -> client.sendCommand("list"));
                assertEquals(RconException.Kind.COMMAND_FAILED, error.getKind());
                assertTrue(server.awaitCommand(), "服务端应先收到命令，然后保持不响应");
                assertFalse(client.isConnected(), "读超时后客户端应清理连接");
            } finally {
                client.stop();
            }
        }
    }

    @Test
    @DisplayName("收到畸形响应包时映射为 COMMAND_FAILED 并清理连接")
    void malformedResponseInvalidatesConnection() throws Exception {
        try (FakeRconServer server = new FakeRconServer(PASSWORD, "unused", FakeRconServer.CommandMode.MALFORMED)) {
            RconClient client = new RconClient(LOGGER, server.getPort(), PASSWORD);
            try {
                client.connect();
                RconException error = assertThrows(RconException.class, () -> client.sendCommand("list"));
                assertEquals(RconException.Kind.COMMAND_FAILED, error.getKind());
                assertFalse(client.isConnected(), "畸形响应后客户端应清理连接");
            } finally {
                client.stop();
            }
        }
    }

    /** Minimal RCON peer for testing the production client's real TCP protocol path. */
    private static final class FakeRconServer implements AutoCloseable {
        private static final int MAX_PACKET_LENGTH = 16 * 1024;
        private static final int TYPE_AUTH = 3;
        private static final int TYPE_AUTH_RESPONSE = 2;
        private static final int TYPE_EXEC_COMMAND = 2;
        private static final int TYPE_COMMAND_RESPONSE = 0;

        private final ServerSocket serverSocket;
        private final String expectedPassword;
        private final String commandResponse;
        private final CommandMode commandMode;
        private final CountDownLatch authenticationRequest = new CountDownLatch(1);
        private final CountDownLatch authenticated = new CountDownLatch(1);
        private final CountDownLatch authenticationRejected = new CountDownLatch(1);
        private final CountDownLatch commandReceived = new CountDownLatch(1);
        private final CountDownLatch responseReleased = new CountDownLatch(1);
        private final AtomicReference<Socket> activeSocket = new AtomicReference<>();
        private final AtomicReference<String> authenticationPayload = new AtomicReference<>();
        private final AtomicReference<String> lastCommand = new AtomicReference<>();
        private final AtomicInteger authenticationPacketType = new AtomicInteger(Integer.MIN_VALUE);
        private final AtomicInteger commandPacketType = new AtomicInteger(Integer.MIN_VALUE);
        private final AtomicInteger lastCommandRequestId = new AtomicInteger(Integer.MIN_VALUE);
        private final AtomicInteger lastResponseId = new AtomicInteger(Integer.MIN_VALUE);
        private final Thread serverThread;

        private FakeRconServer(String expectedPassword, String commandResponse, boolean disconnectOnCommand)
                throws IOException {
            this(expectedPassword, commandResponse,
                    disconnectOnCommand ? CommandMode.DISCONNECT : CommandMode.RESPOND);
        }

        private FakeRconServer(String expectedPassword, String commandResponse, CommandMode commandMode)
                throws IOException {
            this.serverSocket = new ServerSocket(0);
            this.expectedPassword = expectedPassword;
            this.commandResponse = commandResponse;
            this.commandMode = commandMode;
            this.serverThread = new Thread(this::serve, "RconClientProtocolIntegrationTest-Server");
            this.serverThread.setDaemon(true);
            this.serverThread.start();
        }

        private int getPort() {
            return serverSocket.getLocalPort();
        }

        private boolean awaitAuthenticationRequest() throws InterruptedException {
            return authenticationRequest.await(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        private boolean awaitAuthentication() throws InterruptedException {
            return authenticated.await(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        private boolean awaitAuthenticationRejected() throws InterruptedException {
            return authenticationRejected.await(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        private boolean awaitCommand() throws InterruptedException {
            return commandReceived.await(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        private int getAuthenticationPacketType() {
            return authenticationPacketType.get();
        }

        private String getAuthenticationPayload() {
            return authenticationPayload.get();
        }

        private int getCommandPacketType() {
            return commandPacketType.get();
        }

        private String getLastCommand() {
            return lastCommand.get();
        }

        private int getLastResponseId() {
            return lastResponseId.get();
        }

        private int getLastCommandRequestId() {
            return lastCommandRequestId.get();
        }

        private void serve() {
            try (Socket socket = serverSocket.accept()) {
                activeSocket.set(socket);
                socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(20));
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();

                Packet authentication = readPacket(input);
                authenticationPacketType.set(authentication.type);
                authenticationPayload.set(authentication.payload);
                authenticationRequest.countDown();

                if (authentication.type != TYPE_AUTH || !expectedPassword.equals(authentication.payload)) {
                    writePacket(output, -1, TYPE_AUTH_RESPONSE, "");
                    authenticationRejected.countDown();
                    return;
                }

                writePacket(output, authentication.requestId, TYPE_AUTH_RESPONSE, "");
                authenticated.countDown();

                Packet command = readPacket(input);
                commandPacketType.set(command.type);
                lastCommand.set(command.payload);
                lastCommandRequestId.set(command.requestId);
                commandReceived.countDown();
                if (command.type != TYPE_EXEC_COMMAND || commandMode == CommandMode.DISCONNECT) {
                    return;
                }

                if (commandMode == CommandMode.STALL) {
                    try {
                        responseReleased.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("test response wait interrupted", e);
                    }
                }
                if (commandMode == CommandMode.MALFORMED) {
                    writeIntLittleEndian(output, 1);
                    output.flush();
                    return;
                }

                lastResponseId.set(command.requestId);
                writePacket(output, command.requestId, TYPE_COMMAND_RESPONSE, commandResponse);
            } catch (IOException ignored) {
                // Socket closure and deliberate peer disconnect are expected test paths.
            } finally {
                activeSocket.set(null);
            }
        }

        private static Packet readPacket(InputStream input) throws IOException {
            byte[] lengthBytes = new byte[4];
            readFully(input, lengthBytes);
            int length = readIntLittleEndian(lengthBytes, 0);
            if (length < 10 || length > MAX_PACKET_LENGTH) {
                throw new IOException("Invalid RCON packet length: " + length);
            }

            byte[] body = new byte[length];
            readFully(input, body);
            int requestId = readIntLittleEndian(body, 0);
            int type = readIntLittleEndian(body, 4);
            int payloadEnd = 8;
            while (payloadEnd < body.length && body[payloadEnd] != 0) {
                payloadEnd++;
            }
            if (payloadEnd >= body.length || body[body.length - 1] != 0) {
                throw new IOException("Malformed RCON string terminator");
            }
            String payload = new String(body, 8, payloadEnd - 8, StandardCharsets.UTF_8);
            return new Packet(requestId, type, payload);
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
            packet.write(0); // NUL-terminated payload
            packet.write(0); // RCON's trailing empty-string padding
            output.write(packet.toByteArray());
            output.flush();
        }

        private static void readFully(InputStream input, byte[] buffer) throws IOException {
            int offset = 0;
            while (offset < buffer.length) {
                int count = input.read(buffer, offset, buffer.length - offset);
                if (count < 0) {
                    throw new EOFException("RCON peer closed while reading a packet");
                }
                offset += count;
            }
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

        @Override
        public void close() throws Exception {
            responseReleased.countDown();
            Socket socket = activeSocket.getAndSet(null);
            if (socket != null) {
                socket.close();
            }
            serverSocket.close();
            serverThread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            assertFalse(serverThread.isAlive(), "测试 RCON Server 线程应退出");
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

        private enum CommandMode {
            RESPOND,
            DISCONNECT,
            STALL,
            MALFORMED
        }
    }
}
