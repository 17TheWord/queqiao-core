package io.github.theword.queqiao.core.utils;

import io.github.theword.queqiao.core.config.ConfigKeys;
import io.github.theword.queqiao.core.config.schema.ConfigRegistry;
import io.github.theword.queqiao.core.config.Config;
import io.github.theword.queqiao.core.handle.HandleProtocolMessage;
import io.github.theword.queqiao.core.support.PlatformStubs;
import io.github.theword.queqiao.core.websocket.WsClient;
import io.github.theword.queqiao.core.websocket.WsServer;
import com.google.gson.Gson;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebsocketManager 生命周期与启动健壮性测试
 *
 * <p>WS-F 之后 {@code WebsocketManager} 通过构造器接收 {@code Config}，
 * 因此可以用<b>内存构造的配置</b>直接实例化，无需写配置文件、无需任何全局上下文。
 * 这也让此前只能靠代码审查的两个验收项得以自动化：
 * <ul>
 *     <li><b>WS-A #14</b>：{@code start()} 幂等</li>
 *     <li><b>WS-A #23</b>：单个 endpoint 启动失败不阻断其它 endpoint</li>
 * </ul>
 */
class WebsocketManagerLifecycleTest {

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(WebsocketManagerLifecycleTest.class);
    private static final Gson GSON = new Gson();

    /**
     * 与生产一致：协议分发入口由上层创建后注入 Manager
     */
    private static final HandleProtocolMessage HANDLE_PROTOCOL_MESSAGE = PlatformStubs.newDispatcher(LOGGER, GSON);

    /**
     * 基础配置：全部传输层禁用——不绑端口、不建连接
     */
    private static Config disabledTransportsConfig() {
        return runtimeOf("websocket_server:\n  enable: false\nwebsocket_client:\n  enable: false\n");
    }

    /**
     * 启用 Client，并指定 URL 列表
     */
    private static Config clientConfig(List<String> urlList) {
        StringBuilder yaml = new StringBuilder(
                "websocket_server:\n  enable: false\nwebsocket_client:\n  enable: true\n  url_list:\n");
        for (String url : urlList) {
            yaml.append("    - \"").append(url).append("\"\n");
        }
        return runtimeOf(yaml.toString());
    }

    /**
     * 用 YAML 构造一个已加载的运行时（Schema 来自 ConfigKeys）
     */
    private static Config runtimeOf(String yaml) {
        ConfigRegistry registry = new ConfigRegistry();
        ConfigKeys.registerAll(registry);
        Config runtime = new Config(registry);
        runtime.load(parse(yaml));
        return runtime;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String yaml) {
        return (Map<String, Object>) new Yaml().load(yaml);
    }

    /**
     * 启用 WebSocket Server（Client 关闭）
     */
    private static Config serverConfig(int port) {
        return runtimeOf("websocket_server:\n  enable: true\n  host: \"127.0.0.1\"\n  port: " + port
                + "\nwebsocket_client:\n  enable: false\n");
    }

    private static WebsocketManager newManager(Config config) {
        return new WebsocketManager(
                LOGGER,
                GSON,
                HANDLE_PROTOCOL_MESSAGE,
                config,
                PlatformStubs.newRuntimeUtils(LOGGER));
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    // ------------------------------------------------------------------
    // 调度器所有权
    // ------------------------------------------------------------------

    @Test
    @DisplayName("构造阶段即创建共享调度器，且未提交任务")
    void schedulerIsCreatedInConstructor() {
        WebsocketManager manager = newManager(disabledTransportsConfig());
        try {
            ScheduledThreadPoolExecutor scheduler = manager.reconnectSchedulerForTest();
            assertNotNull(scheduler, "构造后应已持有共享调度器");
            assertFalse(scheduler.isShutdown(), "构造后调度器应可用");
            assertEquals(2, scheduler.getCorePoolSize(), "corePoolSize 应为 2");
            assertTrue(scheduler.getRemoveOnCancelPolicy(), "应启用 removeOnCancelPolicy");
            assertFalse(
                    scheduler.getExecuteExistingDelayedTasksAfterShutdownPolicy(),
                    "关闭后不应继续执行已排队的延迟任务");
            assertTrue(scheduler.getQueue().isEmpty(), "构造后不应有排队任务");
        } finally {
            manager.stop(1000, "test cleanup");
        }
    }

    @Test
    @DisplayName("stop() 释放共享调度器，且重复调用幂等")
    void stopShutsDownSchedulerAndIsIdempotent() {
        WebsocketManager manager = newManager(disabledTransportsConfig());
        ScheduledThreadPoolExecutor scheduler = manager.reconnectSchedulerForTest();
        assertFalse(scheduler.isShutdown(), "初始状态不应为已关闭");

        manager.stop(1000, "test");
        assertTrue(scheduler.isShutdown(), "stop() 应释放共享调度器");

        // 幂等：重复调用不得抛异常，状态保持已关闭
        manager.stop(1000, "test");
        assertTrue(scheduler.isShutdown(), "重复 stop() 后调度器仍应处于已关闭状态");
    }

    @Test
    @DisplayName("stop() 之后 restart() 不抛异常（已销毁保护）")
    void restartAfterStopIsSafe() {
        WebsocketManager manager = newManager(disabledTransportsConfig());
        manager.stop(1000, "test");

        // 已销毁后重载应被安全忽略，而不是复用已关闭的 scheduler
        manager.restart(disabledTransportsConfig());
        assertTrue(manager.reconnectSchedulerForTest().isShutdown(), "已销毁后不应复活调度器");
    }

    @Test
    @DisplayName("restart() 用新配置重建连接，且保留共享调度器")
    void restartKeepsSchedulerAndAppliesNewConfig() throws Exception {
        int deadPort = findFreePort();
        WebsocketManager manager = newManager(disabledTransportsConfig());
        try {
            ScheduledThreadPoolExecutor scheduler = manager.reconnectSchedulerForTest();
            assertTrue(manager.getWsClientList().isEmpty(), "初始不应有客户端");

            manager.restart(clientConfig(Collections.singletonList("ws://127.0.0.1:" + deadPort + "/a")));

            assertEquals(1, manager.getWsClientList().size(), "重载后应按新配置创建 1 个客户端");
            assertFalse(scheduler.isShutdown(), "restart 不得销毁共享调度器");
        } finally {
            manager.stop(1000, "test cleanup");
        }
    }

    // ------------------------------------------------------------------
    // 临时暂停（reload 协调）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("suspend() 停止 Client 与 Server，但保留共享调度器，且可重复调用")
    void suspendStopsEndpointsButKeepsScheduler() throws Exception {
        int port = findFreePort();
        WebsocketManager manager = newManager(serverConfig(port));
        try {
            manager.start();
            WsServer server = manager.getWsServer();
            assertNotNull(server, "启动后应存在 WebSocket Server");
            ScheduledThreadPoolExecutor scheduler = manager.reconnectSchedulerForTest();

            manager.suspend();

            assertNull(manager.getWsServer(), "暂停后不应再持有 Server");
            assertTrue(manager.getWsClientList().isEmpty(), "暂停后不应残留 Client");
            assertFalse(scheduler.isShutdown(), "暂停不得销毁共享重连调度器");
            assertTrue(awaitTerminated(server, 5_000L), "正常暂停应确认 selector 线程退出");

            // 幂等：重复暂停不抛异常，也不会销毁调度器
            manager.suspend();
            assertFalse(scheduler.isShutdown(), "重复暂停仍不得销毁调度器");

            // 恢复：restart 使用同一调度器重新启动 endpoint
            manager.restart(serverConfig(port));
            assertNotNull(manager.getWsServer(), "恢复后应重新启动 Server");
            assertFalse(scheduler.isShutdown(), "恢复不得销毁调度器");
        } finally {
            manager.stop(1000, "test cleanup");
        }
    }

    @Test
    @DisplayName("已销毁的 Manager 上调用 suspend() 为安全 no-op")
    void suspendAfterStopIsNoOp() {
        WebsocketManager manager = newManager(disabledTransportsConfig());
        manager.stop(1000, "test");

        assertTrue(manager.suspend().isEmpty(), "已销毁后暂停应被安全忽略");
        assertTrue(manager.reconnectSchedulerForTest().isShutdown(), "已销毁后不得复活调度器");
    }

    @Test
    @DisplayName("Server 停止未确认时不启动第二个 Server，旧实例仍可再次停止")
    void serverStopTimeoutKeepsOldServerAndSkipsRestart() throws Exception {
        int port = findFreePort();
        WebsocketManager manager = newManager(serverConfig(port));
        CountDownLatch selectorStarted = new CountDownLatch(1);
        CountDownLatch releaseSelector = new CountDownLatch(1);

        // 故意忽略中断：模拟"selector 线程卡在无法打断的回调里"，使 stopBounded 无法确认终止
        WsServer stuck = new WsServer(
                new InetSocketAddress("127.0.0.1", port),
                LOGGER,
                HANDLE_PROTOCOL_MESSAGE,
                "Server",
                "",
                true) {

            @Override
            public void onStart() {
                super.onStart();
                selectorStarted.countDown();
                awaitUninterruptibly(releaseSelector);
            }
        };

        try {
            stuck.start();
            assertTrue(selectorStarted.await(5, TimeUnit.SECONDS), "Server 应已开始监听");
            setWsServer(manager, stuck);

            List<String> messages = manager.restart(serverConfig(port));

            assertSame(stuck, manager.getWsServer(), "停止未确认时不得启动第二个 Server");
            assertTrue(
                    messages.stream().anyMatch(message -> message.contains("未在")),
                    "应明确报告停止失败，实际=" + messages);
            assertTrue(
                    messages.stream().anyMatch(message -> message.contains("不启动新的 Server")),
                    "应明确报告跳过启动，实际=" + messages);

            // 释放后旧实例仍然"可被找到并停止"：引用没有被丢弃
            releaseSelector.countDown();
            assertTrue(awaitTerminated(stuck, 5_000L), "释放后 selector 线程应退出");

            // 第二次 restart：这次能确认终止 → 清理引用并启动新 Server
            manager.restart(serverConfig(port));
            WsServer replacement = manager.getWsServer();
            assertNotNull(replacement, "确认终止后应能启动新的 Server");
            assertNotSame(stuck, replacement, "应替换为新的 Server 实例");
            assertTrue(awaitPortAccepting(port), "新 Server 应成功监听同一端口");
        } finally {
            releaseSelector.countDown();
            manager.stop(1000, "test cleanup");
        }
    }

    @Test
    @DisplayName("服务端级异常回调不清空引用：restart 会先确认终止再重建")
    void serverFailureKeepsReferenceUntilConfirmedTerminated() throws Exception {
        int port = findFreePort();
        WebsocketManager manager = newManager(serverConfig(port));
        try {
            manager.start();
            WsServer original = manager.getWsServer();
            assertNotNull(original, "启动后应存在 Server");
            assertTrue(awaitPortAccepting(port), "启动后端口应可连");

            // 模拟服务端级异常回调（bind / selector 级错误）：回调不等于线程已退出
            invokeServerFailure(manager, original);

            assertSame(original, manager.getWsServer(), "异常回调不得提前释放 Server 引用");

            // restart：先确认旧实例终止，再创建新实例；端口必须仍然可用（不能重复 bind 失败）
            manager.restart(serverConfig(port));

            WsServer replacement = manager.getWsServer();
            assertNotNull(replacement, "确认终止后应创建新 Server");
            assertNotSame(original, replacement, "应替换为新的 Server 实例");
            assertTrue(original.isTerminated(), "旧实例应已终止");
            assertTrue(awaitPortAccepting(port), "新 Server 应成功监听同一端口");

            ProbeClient probe = new ProbeClient(port, "Server");
            try {
                probe.connect();
                assertTrue(probe.awaitOpen(10_000L), "新 Server 应能完成真实握手");
            } finally {
                probe.closeQuietly();
            }
        } finally {
            manager.stop(1000, "test cleanup");
        }
    }

    @Test
    @DisplayName("suspend() 关闭分发门控，restart() 重新开放")
    void suspendClosesDispatchGateAndRestartReopensIt() {
        AtomicInteger invocations = new AtomicInteger();
        HandleProtocolMessage dispatcher = PlatformStubs.newDispatcher(
                LOGGER,
                GSON,
                PlatformStubs.noopPlatformContext(),
                command -> {
                    invocations.incrementAndGet();
                    return "ok";
                });
        WebsocketManager manager = new WebsocketManager(
                LOGGER, GSON, dispatcher, disabledTransportsConfig(), PlatformStubs.newRuntimeUtils(LOGGER));
        String request = "{\"api\":\"send_rcon_command\",\"data\":{\"command\":\"list\"}}";
        try {
            assertNotNull(dispatcher.handleHttpJson(request), "暂停前应正常分发");
            assertEquals(1, invocations.get());

            manager.suspend();

            assertNull(dispatcher.handleHttpJson(request), "暂停后报文不得进入协议 Handler");
            assertEquals(1, invocations.get(), "门控关闭后不得再调用协议 Handler");

            manager.restart(disabledTransportsConfig());

            assertNotNull(dispatcher.handleHttpJson(request), "恢复后应重新开放分发");
            assertEquals(2, invocations.get());
        } finally {
            manager.stop(1000, "test cleanup");
        }
    }

    @Test
    @DisplayName("restart() 后端点真实可用：端口可连且能完成 WebSocket 握手")
    void restartMakesEndpointReachableAgain() throws Exception {
        int port = findFreePort();
        WebsocketManager manager = newManager(serverConfig(port));
        ProbeClient probe = null;
        try {
            manager.start();
            assertTrue(awaitPortAccepting(port), "启动后端口应可连");

            manager.suspend();
            assertTrue(awaitPortClosed(port), "暂停后端口应关闭");

            manager.restart(serverConfig(port));
            assertTrue(awaitPortAccepting(port), "重启后端口应重新进入监听");

            probe = new ProbeClient(port, "Server");
            probe.connect();
            assertTrue(probe.awaitOpen(10_000L), "重启后应能完成真实的 WebSocket 握手");
        } finally {
            if (probe != null) {
                probe.closeQuietly();
            }
            manager.stop(1000, "test cleanup");
        }
    }

    // ------------------------------------------------------------------
    // WS-A #14：start() 幂等
    // ------------------------------------------------------------------

    @Test
    @DisplayName("start() 幂等：重复调用不会创建第二套 Client（WS-A #14）")
    void startIsIdempotent() throws Exception {
        int deadPort = findFreePort();
        WebsocketManager manager = newManager(clientConfig(Collections.singletonList("ws://127.0.0.1:" + deadPort + "/a")));
        try {
            manager.start();
            int afterFirstStart = manager.getWsClientList().size();
            assertEquals(1, afterFirstStart, "首次 start 应创建 1 个客户端");

            manager.start();

            assertEquals(afterFirstStart, manager.getWsClientList().size(), "重复 start 不得追加重复客户端");
        } finally {
            manager.stop(1000, "test cleanup");
        }
    }

    // ------------------------------------------------------------------
    // WS-A #23：单个 endpoint 失败不阻断其它 endpoint
    // ------------------------------------------------------------------

    @Test
    @DisplayName("单个 endpoint 启动失败不阻断其它 endpoint（WS-A #23）")
    void failedEndpointDoesNotBlockOthers() throws Exception {
        int deadPort = findFreePort();
        // 第一个 URL 能通过 scheme 校验，但无法被解析为合法 URI → 触发 startClient 的异常隔离分支；
        // 第二个 URL 合法，应被正常创建
        Config config = clientConfig(Arrays.asList("ws://[invalid", "ws://127.0.0.1:" + deadPort + "/ok"));
        WebsocketManager manager = newManager(config);
        try {
            manager.start();

            List<WsClient> clients = manager.getWsClientList();
            assertEquals(1, clients.size(), "合法 endpoint 应被创建，非法 endpoint 不应阻断它");
            assertTrue(
                    clients.get(0).getURI().toString().contains("/ok"),
                    "被创建的应是合法 endpoint，实际=" + clients.get(0).getURI());
        } finally {
            manager.stop(1000, "test cleanup");
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 等待 selector 线程退出
     *
     * <p>端口是否释放由 {@code WsServerStopTest} 用确定性场景覆盖：本类的端口来自
     * {@code findFreePort()}，与真正的 bind 之间存在竞态，不适合作为断言依据。
     */
    private static boolean awaitTerminated(WsServer server, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (server.isTerminated()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return server.isTerminated();
    }

    /**
     * 把测试构造的 WsServer 放进 Manager
     *
     * <p>Manager 自己创建 WsServer，无法注入；而"停止超时"这一失败路径必须有一个
     * 卡住的 selector 线程才能触发，因此这里通过反射替换引用。
     */
    private static void setWsServer(WebsocketManager manager, WsServer server) throws Exception {
        Field field = WebsocketManager.class.getDeclaredField("wsServer");
        field.setAccessible(true);
        field.set(manager, server);
    }

    /**
     * 触发 Manager 的服务端级异常回调（等价于 {@code onError(null, ...)}）
     */
    private static void invokeServerFailure(WebsocketManager manager, WsServer server) throws Exception {
        Method method = WebsocketManager.class.getDeclaredMethod("onServerFailure", WsServer.class);
        method.setAccessible(true);
        method.invoke(manager, server);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean awaitPortAccepting(int port) throws InterruptedException {
        return awaitPortState(port, true);
    }

    private static boolean awaitPortClosed(int port) throws InterruptedException {
        return awaitPortState(port, false);
    }

    private static boolean awaitPortState(int port, boolean accepting) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (isPortAccepting(port) == accepting) {
                return true;
            }
            Thread.sleep(10L);
        }
        return isPortAccepting(port) == accepting;
    }

    private static boolean isPortAccepting(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 最小 WebSocket 客户端：用于验证端点真的能完成握手
     */
    private static final class ProbeClient extends WebSocketClient {

        private final CountDownLatch openLatch = new CountDownLatch(1);

        private ProbeClient(int port, String serverName) throws Exception {
            super(new URI("ws://127.0.0.1:" + port + "/minecraft/ws"));
            // x-self-name 必须与服务端配置的 server_name 一致，否则握手会被 1008 拒绝
            addHeader("x-self-name", serverName);
            addHeader("x-client-origin", "test");
        }

        private boolean awaitOpen(long timeoutMillis) throws InterruptedException {
            return openLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        private void closeQuietly() {
            try {
                closeConnection(1000, "test cleanup");
            } catch (RuntimeException ignored) {
                // 已被服务端关闭：清理阶段忽略
            }
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            openLatch.countDown();
        }

        @Override
        public void onMessage(String message) {
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
        }

        @Override
        public void onError(Exception exception) {
        }
    }

}
