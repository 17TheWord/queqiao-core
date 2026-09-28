package io.github.theword.queqiao.core.websocket;

import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.handle.HandleProtocolMessage;
import io.github.theword.queqiao.core.protocol.RconCommandExecutor;
import io.github.theword.queqiao.core.support.PlatformStubs;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产 WsClient 对本地远端端点的连接集成测试。
 *
 * <p>测试端点只负责记录握手、消息和连接事件，不运行生产 WsServer。
 * 这模拟了另一个 QueQiao 实例：生产客户端发送的 {@code minecraft} 来源标识
 * 在生产 WsServer 上会被自连保护拒绝，因此两种生产端点不能直接配对做成功连接测试。
 */
class WsClientPeerIntegrationTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(WsClientPeerIntegrationTest.class);
    private static final Gson GSON = new Gson();
    private static final long WAIT_SECONDS = 5L;
    private static final String SERVER_NAME = "TestClient";
    private static final String CLIENT_RESOURCE = "/minecraft/ws";

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static ScheduledThreadPoolExecutor newScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "WsClientPeerIntegrationTest-Reconnect");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    private static HandleProtocolMessage newDispatcher(PlatformStubs.RecordingPlatformContext platformContext) {
        RconCommandExecutor rconExecutor = PlatformStubs.rconExecutorReturning("test-result");
        return PlatformStubs.newDispatcher(LOGGER, GSON, platformContext, rconExecutor);
    }

    private static TestWsServer startServer(int port, int expectedConnections) throws Exception {
        TestWsServer server = new TestWsServer(port, expectedConnections);
        server.start();
        assertTrue(server.awaitStarted(), "测试 WebSocket Server 应在限定时间内启动");
        return server;
    }

    private static TestWsClient newClient(
            int port,
            ScheduledThreadPoolExecutor scheduler,
            HandleProtocolMessage dispatcher,
            int expectedConnections) throws Exception {
        URI uri = new URI("ws://127.0.0.1:" + port + CLIENT_RESOURCE);
        return new TestWsClient(uri, scheduler, dispatcher, expectedConnections);
    }

    @Test
    @DisplayName("真实 WsClient 与测试对端双向收发，并通过 HandleApi 分发")
    void connectsExchangesMessagesAndDispatchesApi() throws Exception {
        int port = findFreePort();
        ScheduledThreadPoolExecutor scheduler = newScheduler();
        PlatformStubs.RecordingPlatformContext platformContext = PlatformStubs.recordingPlatformContext();
        TestWsServer server = startServer(port, 1);
        TestWsClient client = newClient(port, scheduler, newDispatcher(platformContext), 1);
        try {
            client.connect();

            assertTrue(client.awaitFirstConnection(), "生产 WsClient 应成功连接测试对端");
            assertTrue(server.awaitFirstConnection(), "测试对端应观察到客户端连接");
            assertEquals(SERVER_NAME, server.getLastSelfName(), "客户端应发送服务器名握手字段");
            assertEquals("minecraft", server.getLastClientOrigin(), "客户端应声明 minecraft 来源");
            assertEquals(CLIENT_RESOURCE, server.getLastResourceDescriptor(), "客户端应请求指定 WebSocket 路由");

            String apiRequest = "{\"api\":\"broadcast\",\"data\":{" 
                    + "\"message\":{\"text\":\"hello from peer\"}},\"echo\":\"ws-test-1\"}";
            server.sendToClient(apiRequest);

            String apiResponse = server.awaitMessage();
            JsonObject response = new JsonParser().parse(apiResponse).getAsJsonObject();
            assertEquals(ProtocolConstants.Api.BROADCAST, response.get("api").getAsString());
            assertEquals(ProtocolConstants.Status.SUCCESS, response.get("code").getAsInt());
            assertEquals("ws-test-1", response.get("echo").getAsString());
            assertEquals(1, platformContext.getBroadcasts().size());
            assertEquals("{\"text\":\"hello from peer\"}", platformContext.getBroadcasts().get(0));

            String clientEvent = "{\"event\":\"player_join\",\"player\":\"Alex\"}";
            client.send(clientEvent);
            assertEquals(clientEvent, server.awaitMessage(), "测试对端应收到生产客户端发送的事件");
        } finally {
            client.stopWithoutReconnect(1000, "test cleanup");
            server.stop(1000);
            scheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("远端关闭连接后生产 WsClient 自动重新连接")
    void reconnectsAfterPeerClosesConnection() throws Exception {
        int port = findFreePort();
        ScheduledThreadPoolExecutor scheduler = newScheduler();
        TestWsServer server = startServer(port, 2);
        TestWsClient client = newClient(
                port,
                scheduler,
                newDispatcher(PlatformStubs.recordingPlatformContext()),
                2);
        try {
            client.connect();
            assertTrue(client.awaitFirstConnection(), "首次 WebSocket 连接应成功");
            assertTrue(server.awaitFirstConnection(), "测试对端应接受首次连接");

            server.closeLatestConnection();
            assertTrue(client.awaitClosed(), "客户端应观察到对端关闭");
            assertTrue(client.awaitAdditionalConnections(), "连接仍在监听的对端后，客户端应自动重连");
            assertTrue(server.awaitAdditionalConnections(), "测试对端应观察到重连");
            assertTrue(client.isOpen(), "自动重连完成后客户端应处于打开状态");
        } finally {
            client.stopWithoutReconnect(1000, "test cleanup");
            server.stop(1000);
            scheduler.shutdownNow();
        }
    }

    private static final class TestWsClient extends WsClient {
        private final CountDownLatch firstConnection = new CountDownLatch(1);
        private final CountDownLatch additionalConnections;
        private final AtomicInteger openCount = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);

        private TestWsClient(
                URI uri,
                ScheduledThreadPoolExecutor scheduler,
                HandleProtocolMessage dispatcher,
                int expectedConnections) {
            super(uri, LOGGER, scheduler, new ReconnectPolicy(1, 5), dispatcher, SERVER_NAME, "", true,
                    PlatformStubs.newRuntimeUtils(LOGGER));
            this.additionalConnections = new CountDownLatch(Math.max(0, expectedConnections - 1));
        }

        @Override
        public void onOpen(org.java_websocket.handshake.ServerHandshake handshake) {
            super.onOpen(handshake);
            if (openCount.incrementAndGet() == 1) {
                firstConnection.countDown();
            } else {
                additionalConnections.countDown();
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            super.onClose(code, reason, remote);
            closed.countDown();
        }

        private boolean awaitFirstConnection() throws InterruptedException {
            return firstConnection.await(WAIT_SECONDS * 3, TimeUnit.SECONDS);
        }

        private boolean awaitAdditionalConnections() throws InterruptedException {
            return additionalConnections.await(WAIT_SECONDS * 3, TimeUnit.SECONDS);
        }

        private boolean awaitClosed() throws InterruptedException {
            return closed.await(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private static final class TestWsServer extends WebSocketServer {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch firstConnection = new CountDownLatch(1);
        private final CountDownLatch additionalConnections;
        private final AtomicInteger openCount = new AtomicInteger();
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final AtomicReference<WebSocket> latestConnection = new AtomicReference<>();
        private volatile String lastSelfName;
        private volatile String lastClientOrigin;
        private volatile String lastResourceDescriptor;

        private TestWsServer(int port, int expectedConnections) {
            super(new InetSocketAddress("127.0.0.1", port));
            this.additionalConnections = new CountDownLatch(Math.max(0, expectedConnections - 1));
            setReuseAddr(true);
        }

        @Override
        public void onOpen(WebSocket connection, ClientHandshake handshake) {
            latestConnection.set(connection);
            lastSelfName = handshake.getFieldValue("x-self-name");
            lastClientOrigin = handshake.getFieldValue("x-client-origin");
            lastResourceDescriptor = handshake.getResourceDescriptor();
            if (openCount.incrementAndGet() == 1) {
                firstConnection.countDown();
            } else {
                additionalConnections.countDown();
            }
        }

        @Override
        public void onClose(WebSocket connection, int code, String reason, boolean remote) {
            latestConnection.compareAndSet(connection, null);
        }

        @Override
        public void onMessage(WebSocket connection, String message) {
            messages.offer(message);
        }

        @Override
        public void onError(WebSocket connection, Exception exception) {
            // Individual connection failures are observed through client callbacks and assertions.
        }

        @Override
        public void onStart() {
            started.countDown();
        }

        private boolean awaitStarted() throws InterruptedException {
            return started.await(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        private boolean awaitFirstConnection() throws InterruptedException {
            return firstConnection.await(WAIT_SECONDS * 3, TimeUnit.SECONDS);
        }

        private boolean awaitAdditionalConnections() throws InterruptedException {
            return additionalConnections.await(WAIT_SECONDS * 3, TimeUnit.SECONDS);
        }

        private void sendToClient(String message) {
            WebSocket connection = latestConnection.get();
            assertNotNull(connection, "测试对端应有活动连接");
            connection.send(message);
        }

        private void closeLatestConnection() {
            WebSocket connection = latestConnection.get();
            assertNotNull(connection, "测试对端应有活动连接");
            connection.close(1000, "test reconnect");
        }

        private String awaitMessage() throws InterruptedException {
            String message = messages.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(message, "应在限定时间内收到 WebSocket 消息");
            return message;
        }

        private String getLastSelfName() {
            return lastSelfName;
        }

        private String getLastClientOrigin() {
            return lastClientOrigin;
        }

        private String getLastResourceDescriptor() {
            return lastResourceDescriptor;
        }
    }
}
