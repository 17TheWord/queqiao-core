package io.github.theword.queqiao.core.websocket;

import io.github.theword.queqiao.core.constant.WebsocketConstantMessage;
import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.handle.HandleProtocolMessage;
import io.github.theword.queqiao.core.response.Response;
import io.github.theword.queqiao.core.support.PlatformStubs;
import com.google.gson.Gson;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WsServer 握手认证测试
 *
 * <p>覆盖 WS-A3（Authorization 日志脱敏）与既有握手校验行为不被破坏。
 */
class WsServerHandshakeAuthTest {

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(WsServerHandshakeAuthTest.class);
    private static final Gson GSON = new Gson();

    /**
     * 与生产一致：Server 与 Client 共用同一个协议分发入口
     */
    private static final HandleProtocolMessage HANDLE_PROTOCOL_MESSAGE = PlatformStubs.newDispatcher(LOGGER, GSON);

    private static final String SERVER_NAME = "TestServer";
    private static final String ACCESS_TOKEN = "s3cr3t-token";
    private static final int CLOSE_CODE_POLICY_VIOLATION = 1008;

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int index = text.indexOf(token);
        while (index >= 0) {
            count++;
            index = text.indexOf(token, index + token.length());
        }
        return count;
    }

    /**
     * WS-A3 回归：认证失败日志模板只为"客户端地址"保留占位符，绝不为 token 预留占位符
     *
     * <p>修复前模板为 {@code "连接身份验证码：{} 失败"}，调用方会把客户端提交的
     * Authorization 值原样填进去，导致 token 泄漏到日志。
     */
    @Test
    @DisplayName("认证失败日志模板不含 token 占位符（WS-A3 回归）")
    void invalidAccessTokenLogTemplateHasNoTokenPlaceholder() {
        String template = WebsocketConstantMessage.Server.INVALID_ACCESS_TOKEN_HEADER;

        assertEquals(
                1, countOccurrences(template, "{}"),
                "认证失败日志只应保留客户端地址这一个占位符，不得为 token 预留占位符，实际模板=" + template);
    }

    /**
     * 握手认证行为：错误 token 被拒绝（1008），正确 token 被接受
     */
    @Test
    @DisplayName("错误 token 以 1008 被拒绝，正确 token 被接受")
    void rejectsWrongTokenAndAcceptsCorrectToken() throws Exception {
        int port = findFreePort();
        WsServer server = new WsServer(
                new InetSocketAddress("127.0.0.1", port), LOGGER, HANDLE_PROTOCOL_MESSAGE, SERVER_NAME, ACCESS_TOKEN, true);
        server.start();

        ProbeClient wrongTokenClient = new ProbeClient(port, SERVER_NAME, "Bearer wrong-token");
        ProbeClient correctTokenClient = new ProbeClient(port, SERVER_NAME, "Bearer " + ACCESS_TOKEN);
        try {
            wrongTokenClient.connect();
            assertTrue(wrongTokenClient.awaitClosed(10_000L), "携带错误 token 的连接应被服务端关闭");
            assertEquals(CLOSE_CODE_POLICY_VIOLATION, wrongTokenClient.getCloseCode(), "应以 1008 关闭");

            correctTokenClient.connect();
            assertTrue(correctTokenClient.awaitOpen(10_000L), "携带正确 token 的连接应通过协议层握手");
            correctTokenClient.expectAccepted("correct-token");
        } finally {
            wrongTokenClient.close();
            correctTokenClient.close();
            server.stop(1000);
        }
    }

    @Test
    @DisplayName("真实 WebSocket 请求经过 WsServer 分发到平台上下文")
    void websocketRequestReachesPlatformContext() throws Exception {
        int port = findFreePort();
        PlatformStubs.RecordingPlatformContext platformContext = PlatformStubs.recordingPlatformContext();
        HandleProtocolMessage dispatcher = PlatformStubs.newDispatcher(
                LOGGER, GSON, platformContext, PlatformStubs.rconExecutorReturning(""));
        WsServer server = new WsServer(
                new InetSocketAddress("127.0.0.1", port), LOGGER, dispatcher, SERVER_NAME, ACCESS_TOKEN, true);
        server.start();

        ProbeClient client = new ProbeClient(port, SERVER_NAME, "Bearer " + ACCESS_TOKEN);
        try {
            client.connect();
            assertTrue(client.awaitOpen(10_000L), "测试客户端应完成 WebSocket 握手");

            client.send(
                    "{\"api\":\"broadcast\",\"data\":{\"message\":{\"text\":\"from websocket\"}},"
                            + "\"echo\":\"ws-server-api\"}");
            assertTrue(client.awaitMessage(10_000L), "服务端应返回协议响应");

            Response response = GSON.fromJson(client.getLastMessage(), Response.class);
            assertEquals(ProtocolConstants.Status.SUCCESS, response.getCode().intValue());
            assertEquals(ProtocolConstants.Api.BROADCAST, response.getApi());
            assertEquals("ws-server-api", response.getEcho());
            assertEquals(1, platformContext.getBroadcasts().size());
            assertEquals("{\"text\":\"from websocket\"}", platformContext.getBroadcasts().get(0));
        } finally {
            client.close();
            server.stop(1000);
        }
    }

    /**
     * 缺失 x-self-name 的连接被拒绝
     */
    @Test
    @DisplayName("缺失 x-self-name 的连接以 1008 被拒绝")
    void rejectsMissingServerNameHeader() throws Exception {
        int port = findFreePort();
        WsServer server = new WsServer(
                new InetSocketAddress("127.0.0.1", port), LOGGER, HANDLE_PROTOCOL_MESSAGE, SERVER_NAME, "", true);
        server.start();

        ProbeClient clientWithoutName = new ProbeClient(port, null, null);
        try {
            clientWithoutName.connect();
            assertTrue(clientWithoutName.awaitClosed(10_000L), "缺失 x-self-name 的连接应被服务端关闭");
            assertEquals(CLOSE_CODE_POLICY_VIOLATION, clientWithoutName.getCloseCode(), "应以 1008 关闭");
        } finally {
            clientWithoutName.close();
            server.stop(1000);
        }
    }

    /**
     * C2 回归：`onOpen` 绝不允许异常逃出
     *
     * <p>Java-WebSocket 的 {@code WebSocketImpl.open()} 会吞掉 {@code onOpen} 抛出的
     * {@code RuntimeException} 并让连接保持 {@code OPEN}，导致未鉴权连接仍可用。
     * 因此本方法必须自己兜底——传入会触发内部 NPE 的输入，断言异常不外泄。
     *
     * <p>这里刻意传 {@code (null, null)}：{@code getHeaderOrQueryParam(null, ...)} 会在
     * {@code clientHandshake.getFieldValue(name)} 处抛 NPE。修复前该异常会直接逃出 {@code onOpen}。
     */
    @Test
    @DisplayName("onOpen 绝不让异常逃出（C2 回归）")
    void onOpenNeverLetsExceptionEscape() {
        WsServer server = new WsServer(
                new InetSocketAddress("127.0.0.1", 1), LOGGER, HANDLE_PROTOCOL_MESSAGE, SERVER_NAME, ACCESS_TOKEN, true);

        server.onOpen(null, null);
    }

    /**
     * C1 回归：服务端级致命错误会以 {@code onError(null, e)} 回调，此时不得 NPE
     */
    @Test
    @DisplayName("onError 收到 null 连接时不抛异常（C1 回归）")
    void onErrorWithNullConnectionIsSafe() {
        WsServer server = new WsServer(
                new InetSocketAddress("127.0.0.1", 1), LOGGER, HANDLE_PROTOCOL_MESSAGE, SERVER_NAME, ACCESS_TOKEN, true);

        server.onError(null, new IllegalStateException("selector fatal error"));
        // 异常消息为 null 时也应安全
        server.onError(null, new IllegalStateException());
    }

    /**
     * C3 回归：显式设置连接丢失检测周期，不依赖库默认值
     */
    @Test
    @DisplayName("connectionLostTimeout 显式设置为 60 秒（C3 回归）")
    void connectionLostTimeoutIsExplicit() {
        WsServer server = new WsServer(
                new InetSocketAddress("127.0.0.1", 1), LOGGER, HANDLE_PROTOCOL_MESSAGE, SERVER_NAME, ACCESS_TOKEN, true);

        assertEquals(60, server.getConnectionLostTimeout(), "应显式设置 connectionLostTimeout");
    }

    /**
     * C2 配套：构造器把 null 归一化为空串，避免握手阶段 NPE
     *
     * <p>{@code accessToken} 为空表示不鉴权，因此该连接应被**接受**而不是因 NPE 被保留为异常状态。
     */
    @Test
    @DisplayName("accessToken 为 null 时归一化为不鉴权，连接被正常接受")
    void nullAccessTokenMeansNoAuthRequired() throws Exception {
        int port = findFreePort();
        WsServer server = new WsServer(
                new InetSocketAddress("127.0.0.1", port), LOGGER, HANDLE_PROTOCOL_MESSAGE, SERVER_NAME, null, true);
        server.start();

        ProbeClient client = new ProbeClient(port, SERVER_NAME, null);
        try {
            client.connect();
            assertTrue(client.awaitOpen(10_000L), "应通过协议层握手");
            client.expectAccepted("no-auth-configured");
        } finally {
            client.close();
            server.stop(1000);
        }
    }

    // ------------------------------------------------------------------
    // D1：query 兜底（浏览器客户端无法设置自定义请求头）
    // ------------------------------------------------------------------

    /**
     * 浏览器的 WebSocket API 无法设置自定义请求头，只能把凭据放进 URL query，
     * 因此 query 兜底必须保留。
     */
    @Test
    @DisplayName("通过 URL query 传递 Authorization 仍被接受（浏览器兼容）")
    void authorizationViaQueryIsAccepted() throws Exception {
        int port = findFreePort();
        WsServer server = new WsServer(
                new InetSocketAddress("127.0.0.1", port), LOGGER, HANDLE_PROTOCOL_MESSAGE, SERVER_NAME, ACCESS_TOKEN, true);
        server.start();

        ProbeClient client = new ProbeClient(port, SERVER_NAME, "Bearer " + ACCESS_TOKEN, true);
        try {
            client.connect();
            assertTrue(client.awaitOpen(10_000L), "应通过协议层握手");
            client.expectAccepted("query-token");
        } finally {
            client.close();
            server.stop(1000);
        }
    }

    @Test
    @DisplayName("已配置 token 但连接未携带凭据时被拒绝（1008）")
    void missingCredentialIsRejected() throws Exception {
        int port = findFreePort();
        WsServer server = new WsServer(
                new InetSocketAddress("127.0.0.1", port), LOGGER, HANDLE_PROTOCOL_MESSAGE, SERVER_NAME, ACCESS_TOKEN, true);
        server.start();

        ProbeClient client = new ProbeClient(port, SERVER_NAME, null);
        try {
            client.connect();
            assertTrue(client.awaitClosed(10_000L), "未携带凭据的连接应被关闭");
            assertEquals(CLOSE_CODE_POLICY_VIOLATION, client.getCloseCode(), "应以 1008 关闭");
        } finally {
            client.close();
            server.stop(1000);
        }
    }

    // ------------------------------------------------------------------
    // D2：解析拆分后，decode 恰好一次
    // ------------------------------------------------------------------

    /**
     * D2 回归：query 来源的服务器名此前被**解码两次**。
     *
     * <p>名字里含 {@code %} 时，第二次解码会因非法转义而抛 {@code IllegalArgumentException}，
     * 连接被以"解码失败"拒绝——本用例在修复前会失败。
     */
    @Test
    @DisplayName("query 来源的服务器名只解码一次，含 % 的名字可正常匹配（D2 回归）")
    void queryServerNameIsDecodedExactlyOnce() throws Exception {
        int port = findFreePort();
        String serverNameWithPercent = "Test%Server";

        WsServer server = new WsServer(
                new InetSocketAddress("127.0.0.1", port), LOGGER, HANDLE_PROTOCOL_MESSAGE, serverNameWithPercent, "", true);
        server.start();

        // 传原始名字，由 ProbeClient 统一编码一次（此前误传已编码值导致双重编码）
        ProbeClient client = new ProbeClient(port, serverNameWithPercent, null, true);
        try {
            client.connect();
            assertTrue(client.awaitOpen(10_000L), "应通过协议层握手");
            client.expectAccepted("percent-name");
        } finally {
            client.close();
            server.stop(1000);
        }
    }

    @Test
    @DisplayName("query 来源的服务器名仍可正常匹配（兼容保留）")
    void queryServerNameStillWorks() throws Exception {
        int port = findFreePort();
        WsServer server = new WsServer(
                new InetSocketAddress("127.0.0.1", port), LOGGER, HANDLE_PROTOCOL_MESSAGE, SERVER_NAME, "", true);
        server.start();

        ProbeClient client = new ProbeClient(port, SERVER_NAME, null, true);
        try {
            client.connect();
            assertTrue(client.awaitOpen(10_000L), "应通过协议层握手");
            client.expectAccepted("query-server-name");
        } finally {
            client.close();
            server.stop(1000);
        }
    }

    // ------------------------------------------------------------------
    // D3：非回环 + 空 token 的判定
    // ------------------------------------------------------------------

    @Test
    @DisplayName("回环地址判定：回环为 true，其余按非回环处理（D3）")
    void loopbackAddressDetection() {
        assertTrue(WsServer.isLoopbackAddress(new InetSocketAddress("127.0.0.1", 8080)), "127.0.0.1 是回环");
        assertTrue(WsServer.isLoopbackAddress(new InetSocketAddress("localhost", 8080)), "localhost 是回环");
        assertFalse(WsServer.isLoopbackAddress(new InetSocketAddress("0.0.0.0", 8080)), "0.0.0.0 不是回环");
        assertFalse(WsServer.isLoopbackAddress(null), "null 按非回环处理");
        assertFalse(
                WsServer.isLoopbackAddress(InetSocketAddress.createUnresolved("example.com", 8080)),
                "未解析地址按非回环处理（宁可多告警，也不漏报）");
    }

    /**
     * 探测用客户端：可自定义 x-self-name 与 Authorization
     */
    private static final class ProbeClient extends WebSocketClient {

        private final CountDownLatch openLatch = new CountDownLatch(1);
        private final CountDownLatch closeLatch = new CountDownLatch(1);
        private final CountDownLatch messageLatch = new CountDownLatch(1);
        private volatile int closeCode = Integer.MIN_VALUE;
        private volatile String lastMessage;

        private ProbeClient(int port, String selfName, String authorization) throws Exception {
            this(port, selfName, authorization, false);
        }

        /**
         * @param viaQuery true 表示把字段放进 URL query（模拟无法设置请求头的浏览器客户端）
         */
        private ProbeClient(int port, String selfName, String authorization, boolean viaQuery) throws Exception {
            super(new URI("ws://127.0.0.1:" + port + "/minecraft/ws" + buildQuery(viaQuery, selfName, authorization)));
            if (!viaQuery) {
                if (selfName != null) {
                    addHeader("x-self-name", selfName);
                }
                addHeader("x-client-origin", "test-probe");
                if (authorization != null) {
                    addHeader("Authorization", authorization);
                }
            }
        }

        private static String buildQuery(boolean viaQuery, String selfName, String authorization) throws UnsupportedEncodingException {
            if (!viaQuery) {
                return "";
            }
            StringBuilder query = new StringBuilder("?");
            if (selfName != null) {
                query.append("x-self-name=").append(URLEncoder.encode(selfName, StandardCharsets.UTF_8.name()));
            }
            query.append("&x-client-origin=test-probe");
            if (authorization != null) {
                query.append("&Authorization=").append(URLEncoder.encode(authorization, StandardCharsets.UTF_8.name()));
            }
            return query.toString();
        }

        @Override
        public void onOpen(ServerHandshake serverHandshake) {
            openLatch.countDown();
        }

        @Override
        public void onMessage(String message) {
            this.lastMessage = message;
            messageLatch.countDown();
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            this.closeCode = code;
            closeLatch.countDown();
        }

        @Override
        public void onError(Exception exception) {
        }

        private boolean awaitOpen(long timeoutMillis) throws InterruptedException {
            return openLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        /**
         * 断言连接被服务端**真正接受**且可用
         *
         * <p><b>不能用 {@link #awaitOpen} 判断接受与否</b>：客户端完成 WebSocket 协议层握手
         * 就会触发 {@code onOpen}，而服务端的字段校验发生在**之后**——
         * 校验失败时服务端随即关闭连接，但客户端早已收到过 {@code onOpen}。
         * 因此"收到 onOpen"并不等于"被接受"。
         *
         * <p>这里改为发送一条请求并等待响应：只有连接被接受且 {@code onMessage} 正常工作，
         * 才可能收到响应（未知 api 会返回 404 响应，无需平台实现）。
         *
         * @param marker 用于回显校验的标记
         */
        private void expectAccepted(String marker) throws InterruptedException {
            send("{\"api\":\"no_such_api\",\"echo\":\"" + marker + "\"}");
            assertTrue(awaitMessage(10_000L), "连接应被接受并可处理消息（marker=" + marker + "）");
            assertTrue(getLastMessage().contains(marker), "响应应回传 echo，实际=" + getLastMessage());
        }

        private boolean awaitClosed(long timeoutMillis) throws InterruptedException {
            return closeLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        private boolean awaitMessage(long timeoutMillis) throws InterruptedException {
            return messageLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        private int getCloseCode() {
            return closeCode;
        }

        private String getLastMessage() {
            return lastMessage;
        }
    }
}
