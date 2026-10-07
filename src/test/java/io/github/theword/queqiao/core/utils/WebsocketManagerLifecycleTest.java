package io.github.theword.queqiao.core.utils;

import io.github.theword.queqiao.core.config.ConfigKeys;
import io.github.theword.queqiao.core.config.schema.ConfigRegistry;
import io.github.theword.queqiao.core.config.Config;
import io.github.theword.queqiao.core.handle.HandleProtocolMessage;
import io.github.theword.queqiao.core.support.PlatformStubs;
import io.github.theword.queqiao.core.websocket.WsClient;
import com.google.gson.Gson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

}
