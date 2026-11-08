package io.github.theword.queqiao.core.runtime;

import io.github.theword.queqiao.core.platform.AbstractPlatformContext;
import io.github.theword.queqiao.core.rcon.RconClient;
import io.github.theword.queqiao.core.rcon.RconTestConnections;
import io.github.theword.queqiao.core.support.FakeRconServer;
import io.github.theword.queqiao.core.support.PlatformStubs;
import io.github.theword.queqiao.core.support.RuntimeConfigFixture;
import io.github.theword.queqiao.core.utils.WebsocketManager;
import io.github.theword.queqiao.core.websocket.WsServer;
import com.google.gson.Gson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime 关闭流程的可靠性与失败可见性
 *
 * <p>关闭流程的目标不是"一定要把所有资源停掉"，而是：
 * <ol>
 *     <li>某一项失败不阻止其它项继续停止；</li>
 *     <li>失败必须能被调用方发现（ERROR 日志 + {@code getShutdownFailures()}）；</li>
 *     <li>{@code STOPPED} 只表示流程执行完毕，不冒充"全部资源已释放"。</li>
 * </ol>
 *
 * <p>失败通过替换 {@code websocketManager} 字段注入：Manager 由 Runtime 自己创建，
 * 没有注入点，而"停止失败"这条路径需要一个会抛异常 / 会报告失败的 Manager。
 */
class QueQiaoRuntimeShutdownTest {

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(QueQiaoRuntimeShutdownTest.class);
    private static final Gson GSON = new Gson();
    private static final AbstractPlatformContext<?, ?, ?> PLATFORM_CONTEXT = PlatformStubs.noopPlatformContext();
    private static final String RCON_PASSWORD = "test-password";

    private QueQiaoRuntime runtime;

    @AfterEach
    void tearDown() {
        QueQiaoRuntime current = runtime;
        runtime = null;
        if (current != null) {
            current.shutdown();
        }
    }

    @Test
    @DisplayName("Manager 停止抛异常时，RCON 仍会被关闭且失败被记录")
    void shutdownContinuesAfterManagerStopThrows() throws Exception {
        try (FakeRconServer rconServer = new FakeRconServer("ok", false, false);
             RuntimeConfigFixture fixture = new RuntimeConfigFixture(RuntimeConfigFixture.yaml(
                     false, findFreePort(), true, "127.0.0.1", rconServer.getPort(), RCON_PASSWORD))) {

            runtime = QueQiaoRuntime.create(null, PLATFORM_CONTEXT, null);
            runtime.start();
            assertEquals("ok", runtime.sendRconCommand("list"), "启动后 RCON 应可用");

            // 先把真实 Manager 正常停掉（避免遗留调度器），再换成会抛异常的替身
            WebsocketManager real = runtime.getWebsocketManager();
            assertNotNull(real);
            real.stop(1000, "handover");
            WebsocketManager throwing = new ThrowingManager();
            setField(runtime, "websocketManager", throwing);

            runtime.shutdown();

            assertEquals(RuntimeState.STOPPED, runtime.getState(), "关闭流程仍应执行完毕");
            assertSame(
                    throwing,
                    runtime.getWebsocketManager(),
                    "停止过程抛异常时必须保留 Manager 引用（内部可能仍有未终止的 Server）");
            assertTrue(
                    runtime.getShutdownFailures().stream().anyMatch(item -> item.contains("WebsocketManager")),
                    "应记录 Manager 停止失败，实际=" + runtime.getShutdownFailures());
            assertTrue(rconServer.awaitClosedConnections(1), "Manager 失败不得阻止 RCON 关闭");
        }
    }

    @Test
    @DisplayName("Server 停止未确认时，Runtime 关闭结果中明确记录该失败")
    void shutdownRecordsUnconfirmedServerStop() throws Exception {
        try (RuntimeConfigFixture fixture = new RuntimeConfigFixture(RuntimeConfigFixture.yaml(
                false, findFreePort(), false, "127.0.0.1", 25575, RCON_PASSWORD))) {

            runtime = QueQiaoRuntime.create(null, PLATFORM_CONTEXT, null);
            runtime.start();

            WebsocketManager real = runtime.getWebsocketManager();
            assertNotNull(real);
            real.stop(1000, "handover");

            int wsPort = findFreePort();
            CountDownLatch selectorStarted = new CountDownLatch(1);
            CountDownLatch releaseSelector = new CountDownLatch(1);
            WebsocketManager manager = newManager();
            // 忽略中断：模拟 selector 线程卡在无法打断的回调里，使 stopBounded 无法确认终止
            WsServer stuck = new WsServer(
                    new InetSocketAddress("127.0.0.1", wsPort),
                    LOGGER,
                    PlatformStubs.newDispatcher(LOGGER, GSON),
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
                setField(manager, "wsServer", stuck);
                setField(runtime, "websocketManager", manager);

                runtime.shutdown();

                assertEquals(RuntimeState.STOPPED, runtime.getState(), "关闭流程仍应执行完毕");
                assertSame(
                        manager,
                        runtime.getWebsocketManager(),
                        "Server 停止未确认时必须保留 Manager 引用，否则未终止的 Server 会失联");
                assertTrue(
                        runtime.getShutdownFailures().stream().anyMatch(item -> item.contains("WebSocket Server")),
                        "应记录 Server 停止失败，实际=" + runtime.getShutdownFailures());
            } finally {
                releaseSelector.countDown();
                manager.stop(1000, "test cleanup");
            }
        }
    }

    @Test
    @DisplayName("全部资源正常停止时关闭失败列表为空")
    void shutdownWithoutFailuresReportsEmptyList() throws Exception {
        try (RuntimeConfigFixture fixture = new RuntimeConfigFixture(RuntimeConfigFixture.yaml(
                false, findFreePort(), false, "127.0.0.1", 25575, RCON_PASSWORD))) {

            runtime = QueQiaoRuntime.create(null, PLATFORM_CONTEXT, null);
            runtime.start();

            runtime.shutdown();

            assertEquals(RuntimeState.STOPPED, runtime.getState());
            assertTrue(runtime.getShutdownFailures().isEmpty(), "正常关闭不应产生失败记录，实际=" + runtime.getShutdownFailures());
        }
    }

    @Test
    @DisplayName("RCON abort 失败（stop 未抛异常）也会进入关闭失败列表")
    void shutdownRecordsRconReleaseFailure() throws Exception {
        try (RuntimeConfigFixture fixture = new RuntimeConfigFixture(RuntimeConfigFixture.yaml(
                false, findFreePort(), false, "127.0.0.1", 25575, RCON_PASSWORD))) {

            runtime = QueQiaoRuntime.create(null, PLATFORM_CONTEXT, null);
            runtime.start();

            RconClient failing = RconTestConnections.clientWithFailingAbort(
                    LOGGER, "127.0.0.1", 25575, RCON_PASSWORD);
            assertTrue(failing.isConnected(), "替身应处于\"持有连接引用\"状态");
            setRconState(runtime, failing);

            runtime.shutdown();

            assertEquals(RuntimeState.STOPPED, runtime.getState());
            assertTrue(
                    runtime.getShutdownFailures().stream().anyMatch(item -> item.contains("RconClient")),
                    "abort 失败必须进入关闭失败列表，实际=" + runtime.getShutdownFailures());
            assertTrue(failing.isConnected(), "abort 失败后连接引用应被保留，便于再次尝试");
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static void setRconState(QueQiaoRuntime runtime, RconClient client) throws Exception {
        Class<?> stateClass = Class.forName(QueQiaoRuntime.class.getName() + "$RconState");
        Constructor<?> constructor = stateClass.getDeclaredConstructor(boolean.class, RconClient.class);
        constructor.setAccessible(true);
        Object state = constructor.newInstance(true, client);
        Field field = QueQiaoRuntime.class.getDeclaredField("rconState");
        field.setAccessible(true);
        field.set(runtime, state);
    }

    private static WebsocketManager newManager() {
        return new WebsocketManager(
                LOGGER,
                GSON,
                PlatformStubs.newDispatcher(LOGGER, GSON),
                PlatformStubs.defaultConfig(),
                PlatformStubs.newRuntimeUtils(LOGGER));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    /**
     * 忽略中断地等待，模拟"卡在不可打断的协议回调里"
     */
    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 停止时抛异常的 Manager：用于验证 Runtime 的步骤隔离
     */
    private static final class ThrowingManager extends WebsocketManager {

        private ThrowingManager() {
            super(
                    LOGGER,
                    GSON,
                    PlatformStubs.newDispatcher(LOGGER, GSON),
                    PlatformStubs.defaultConfig(),
                    PlatformStubs.newRuntimeUtils(LOGGER));
        }

        @Override
        public StopResult stopWithResult(int code, String reason) {
            throw new IllegalStateException("injected stop failure");
        }
    }
}
