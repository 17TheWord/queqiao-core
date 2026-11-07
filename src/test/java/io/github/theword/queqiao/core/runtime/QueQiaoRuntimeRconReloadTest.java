package io.github.theword.queqiao.core.runtime;

import io.github.theword.queqiao.core.exception.rcon.RconException;
import io.github.theword.queqiao.core.platform.AbstractPlatformContext;
import io.github.theword.queqiao.core.support.FakeRconServer;
import io.github.theword.queqiao.core.support.PlatformStubs;
import io.github.theword.queqiao.core.support.RuntimeConfigFixture;
import io.github.theword.queqiao.core.utils.WebsocketManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime 与 RCON 的生命周期协调测试
 *
 * <p>覆盖三件只在真实环境里才会暴露的问题：
 * <ol>
 *     <li><b>启动顺序</b>：RCON 必须先于 WebSocket 就绪，否则存在"WebSocket 已在收请求、
 *         而 RCON 还没连上"的窗口；</li>
 *     <li><b>Reload 时序</b>：切换 RCON 期间 WebSocket 必须暂停接入，切换完成后按新配置恢复，
 *         且共享重连调度器不能被销毁；</li>
 *     <li><b>关闭不阻塞</b>：存在在途 RCON 命令时，shutdown 不得无限等待。</li>
 * </ol>
 *
 * <p>断言依赖真实 TCP 行为（RCON 认证握手、对端 EOF、端口是否可连），
 * 因此使用 {@link FakeRconServer} 而不是纯替身。
 *
 * <p><b>端口竞态说明</b>：{@code findFreePort()} 释放端口与 Java-WebSocket 真正 bind
 * 之间有两个噪声源——同一 JVM 内的其它 socket 可能抢占该端口；bind 发生在 selector
 * 线程里，{@code start()} 返回时未必已完成。因此这里"等待端口可连 + 失败换端口重试"，
 * 不把环境竞态当成功能缺陷。
 */
class QueQiaoRuntimeRconReloadTest {

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(QueQiaoRuntimeRconReloadTest.class);
    private static final String RCON_PASSWORD = "test-rcon-password";

    /** 端口竞争时的最大重试次数 */
    private static final int PORT_ATTEMPTS = 3;

    /** 等待端口状态变化的上限 */
    private static final long PORT_WAIT_MILLIS = 2_000L;

    private static final AbstractPlatformContext<?, ?, ?> PLATFORM_CONTEXT =
            PlatformStubs.noopPlatformContext();

    private QueQiaoRuntime runtime;

    /** 当前用例实际使用的 WebSocket 端口（由 {@link #startRuntime} 选定） */
    private int webSocketPort;

    @AfterEach
    void tearDown() {
        QueQiaoRuntime current = runtime;
        runtime = null;
        if (current != null) {
            current.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // 启动顺序
    // ------------------------------------------------------------------

    @Test
    @DisplayName("启动时先初始化 RCON，再创建 WebSocket 端点")
    void startsRconBeforeOpeningWebSocket() throws Exception {
        int port = findFreePort();
        try (FakeRconServer rcon = new FakeRconServer("ok", true, false);
             RuntimeConfigFixture ignored = new RuntimeConfigFixture(
                     RuntimeConfigFixture.yaml(true, port, true, "127.0.0.1", rcon.getPort(), RCON_PASSWORD))) {

            QueQiaoRuntime created = newRuntime();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> starting = executor.submit(() -> created.start());

                assertTrue(rcon.awaitAuthenticationStarted(), "启动阶段应已开始 RCON 认证");
                assertNull(
                        created.getWebsocketManager(),
                        "RCON 尚未就绪时不应已经创建 WebSocket 端点");

                rcon.releaseAuthentication();
                starting.get(10, TimeUnit.SECONDS);

                assertEquals(RuntimeState.RUNNING, created.getState());
                assertNotNull(created.getWebsocketManager(), "启动完成后应已创建 WebSocket 端点");
                assertTrue(awaitPortAccepting(port), "启动完成后 WebSocket 端口应可连");
                assertEquals("ok", created.sendRconCommand("list"), "RCON 应在启动阶段完成连接");
                webSocketPort = port;
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("RCON 连接失败不影响 Runtime 与 WebSocket 启动，RCON 请求得到明确不可用结果")
    void rconConnectionFailureDoesNotBlockStartup() throws Exception {
        int unreachableRconPort = findFreePort();
        try (RuntimeConfigFixture fixture = new RuntimeConfigFixture("")) {
            QueQiaoRuntime created = startRuntime(fixture, true, "127.0.0.1", unreachableRconPort);

            assertEquals(RuntimeState.RUNNING, created.getState(), "RCON 连不上不应导致启动失败");
            assertTrue(isPortAccepting(webSocketPort), "WebSocket 仍应正常监听");

            RconException failure = assertThrows(RconException.class, () -> created.sendRconCommand("list"));
            assertEquals(RconException.Kind.DISCONNECTED, failure.getKind());
        }
    }

    // ------------------------------------------------------------------
    // Reload 时序
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Reload 先暂停 WebSocket 接入，RCON 切换完成后按新配置恢复，且不销毁共享调度器")
    void reloadSuspendsEndpointsWhileSwitchingRcon() throws Exception {
        try (FakeRconServer first = new FakeRconServer("from-A", false, false);
             FakeRconServer second = new FakeRconServer("from-B", true, false);
             RuntimeConfigFixture fixture = new RuntimeConfigFixture("")) {

            QueQiaoRuntime created = startRuntime(fixture, true, "127.0.0.1", first.getPort());
            assertEquals("from-A", created.sendRconCommand("list"), "启动后应连到旧 RCON");

            ScheduledThreadPoolExecutor scheduler = readReconnectScheduler(created.getWebsocketManager());

            fixture.write(RuntimeConfigFixture.yaml(
                    true, webSocketPort, true, "127.0.0.1", second.getPort(), RCON_PASSWORD));

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> reloading = executor.submit(() -> created.reload());
                assertTrue(second.awaitAuthenticationStarted(), "重载阶段应已开始连接新 RCON");

                assertTrue(
                        awaitPortClosed(webSocketPort),
                        "RCON 切换期间 WebSocket 必须暂停接入，不能继续接收请求");

                second.releaseAuthentication();
                reloading.get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }

            assertEquals(RuntimeState.RUNNING, created.getState());
            assertTrue(awaitPortAccepting(webSocketPort), "重载完成后 WebSocket 应按新配置恢复监听");
            assertEquals("from-B", created.sendRconCommand("list"), "重载后应使用新配置的 RCON");
            assertTrue(first.awaitClosedConnections(1), "旧 RCON 连接必须被中止");
            assertFalse(scheduler.isShutdown(), "重载不得销毁共享重连调度器");
        }
    }

    @Test
    @DisplayName("Reload 不等待在途 RCON 命令：中止旧连接、旧命令失败，新请求由新 Server 处理")
    void reloadAbortsInFlightRconCommandAndSwitchesToNewServer() throws Exception {
        // 旧 Server 收到命令后不返回响应：模拟"命令仍在等 Minecraft 主线程处理"
        try (FakeRconServer oldServer = new FakeRconServer("unused", false, true);
             FakeRconServer newServer = new FakeRconServer("from-new", false, false);
             RuntimeConfigFixture fixture = new RuntimeConfigFixture("")) {

            QueQiaoRuntime created = startRuntime(fixture, true, "127.0.0.1", oldServer.getPort());

            // 两个线程：一个被在途命令占住，一个执行 reload（两者都必须有界等待，避免测试自身挂死）
            ExecutorService executor = Executors.newFixedThreadPool(2);
            Future<String> command = executor.submit(() -> created.sendRconCommand("stalled"));
            try {
                assertNotNull(oldServer.awaitCommand(), "旧 Server 应已收到命令（客户端正在等待响应）");

                fixture.write(RuntimeConfigFixture.yaml(
                        true, webSocketPort, true, "127.0.0.1", newServer.getPort(), RCON_PASSWORD));

                Future<Long> reloading = executor.submit(() -> {
                    long startedAt = System.nanoTime();
                    created.reload();
                    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                });
                long elapsedMillis = reloading.get(5, TimeUnit.SECONDS);

                assertTrue(
                        elapsedMillis < 2000L,
                        "reload 不应等待在途 RCON 命令自然返回，实际耗时 " + elapsedMillis + "ms");

                // 旧命令因连接被中止而失败，且不会被自动重放
                Throwable failure = assertThrows(Exception.class, () -> command.get(5, TimeUnit.SECONDS))
                        .getCause();
                assertTrue(failure instanceof RconException, "旧命令应以 RconException 结束：" + failure);
                assertEquals(
                        RconException.Kind.COMMAND_FAILED,
                        ((RconException) failure).getKind(),
                        "连接被中止应映射为 COMMAND_FAILED");

                assertTrue(oldServer.awaitClosedConnections(1), "旧连接应收到 Socket 中止");
                assertTrue(awaitPortAccepting(webSocketPort), "Reload 完成后 WebSocket 应恢复监听");

                assertEquals("from-new", created.sendRconCommand("list"), "新请求应由新 Server 处理");
                assertEquals("list", newServer.awaitCommand(), "新 Server 应收到该命令");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("RCON 切换期间命令不会发往旧 Host，而是返回 DISCONNECTED")
    void commandDuringRconSwitchIsRejectedInsteadOfReachingOldHost() throws Exception {
        try (FakeRconServer oldServer = new FakeRconServer("from-old", false, false);
             // 卡住认证：让"旧客户端已摘除、新客户端未发布"的窗口稳定可观察
             FakeRconServer newServer = new FakeRconServer("from-new", true, false);
             RuntimeConfigFixture fixture = new RuntimeConfigFixture("")) {

            QueQiaoRuntime created = startRuntime(fixture, true, "127.0.0.1", oldServer.getPort());
            assertEquals("from-old", created.sendRconCommand("warmup"));
            assertEquals(1, oldServer.getCommandCount(), "前置命令应已到达旧 Server");

            fixture.write(RuntimeConfigFixture.yaml(
                    true, webSocketPort, true, "127.0.0.1", newServer.getPort(), RCON_PASSWORD));

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> reloading = executor.submit(() -> created.reload());
                assertTrue(newServer.awaitAuthenticationStarted(), "重载应已开始连接新 RCON");

                // 切换窗口：旧客户端已被摘除并停止，新客户端尚未发布
                RconException failure = assertThrows(
                        RconException.class, () -> created.sendRconCommand("during-switch"));
                assertEquals(
                        RconException.Kind.DISCONNECTED,
                        failure.getKind(),
                        "切换期间必须返回不可用，而不是把命令打到旧主机");
                assertEquals(1, oldServer.getCommandCount(), "切换期间不得把命令发往旧 Host");

                newServer.releaseAuthentication();
                reloading.get(10, TimeUnit.SECONDS);

                assertEquals("from-new", created.sendRconCommand("after-switch"), "切换完成后应使用新客户端");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("新 RCON 连不上时保持不可用，不回退到旧客户端")
    void reloadWithUnreachableRconKeepsUnavailable() throws Exception {
        int closedPort = findFreePort();
        try (FakeRconServer oldServer = new FakeRconServer("from-old", false, false);
             RuntimeConfigFixture fixture = new RuntimeConfigFixture("")) {

            QueQiaoRuntime created = startRuntime(fixture, true, "127.0.0.1", oldServer.getPort());
            assertEquals("from-old", created.sendRconCommand("warmup"));

            fixture.write(RuntimeConfigFixture.yaml(
                    true, webSocketPort, true, "127.0.0.1", closedPort, RCON_PASSWORD));

            created.reload();

            RconException failure = assertThrows(RconException.class, () -> created.sendRconCommand("list"));
            assertEquals(
                    RconException.Kind.DISCONNECTED,
                    failure.getKind(),
                    "新地址连不上时必须保持不可用，不得回退旧客户端");
            assertEquals(1, oldServer.getCommandCount(), "旧客户端已停止，不应再收到命令");
            assertTrue(oldServer.awaitClosedConnections(1), "旧连接必须被中止");
            assertTrue(awaitPortAccepting(webSocketPort), "RCON 失败不应影响 WebSocket");
        }
    }

    @Test
    @DisplayName("配置非法时 Reload 失败，但 WebSocket 按原配置恢复且旧 RCON 保持可用")
    void reloadWithInvalidConfigRestoresEndpoints() throws Exception {
        try (FakeRconServer rcon = new FakeRconServer("from-A", false, false);
             RuntimeConfigFixture fixture = new RuntimeConfigFixture("")) {

            QueQiaoRuntime created = startRuntime(fixture, true, "127.0.0.1", rcon.getPort());
            assertEquals("from-A", created.sendRconCommand("list"));

            // 端口越界 → 配置校验失败；此时 Runtime 状态与配置都必须保持原样
            fixture.write(RuntimeConfigFixture.yaml(
                    true, 99999, true, "127.0.0.1", rcon.getPort(), RCON_PASSWORD));

            assertThrows(RuntimeException.class, created::reload);

            assertEquals(RuntimeState.RUNNING, created.getState(), "reload 失败不应改变生命周期状态");
            assertTrue(awaitPortAccepting(webSocketPort), "Endpoint 必须按原配置恢复");
            assertEquals("from-A", created.sendRconCommand("list"), "配置未生效时旧 RCON 应继续可用");
            assertFalse(readReconnectScheduler(created.getWebsocketManager()).isShutdown());
        }
    }

    @Test
    @DisplayName("修改 rcon.host 后 Reload 使用新地址，旧地址不再收到命令")
    void reloadUsesNewRconHost() throws Exception {
        // 两个服务端监听不同的回环地址：只有真正使用了配置里的 host 才能连到新实例
        try (FakeRconServer oldServer = new FakeRconServer("127.0.0.1", "from-old", false, false);
             FakeRconServer newServer = new FakeRconServer("127.0.0.2", "from-new", false, false);
             RuntimeConfigFixture fixture = new RuntimeConfigFixture("")) {

            QueQiaoRuntime created = startRuntime(fixture, true, "127.0.0.1", oldServer.getPort());
            assertEquals("from-old", created.sendRconCommand("list"));

            fixture.write(RuntimeConfigFixture.yaml(
                    true, webSocketPort, true, "127.0.0.2", newServer.getPort(), RCON_PASSWORD));

            created.reload();

            assertEquals(
                    "from-new",
                    created.sendRconCommand("list"),
                    "重载后必须按新 Host 建立连接（沿用旧地址会连不上）");
            assertTrue(oldServer.awaitClosedConnections(1), "旧地址的连接必须被中止");
            assertTrue(awaitPortAccepting(webSocketPort), "RCON 切换不应影响 WebSocket");
        }
    }

    @Test
    @DisplayName("禁用 RCON 后 Reload 会中止旧连接，请求返回明确的不可用结果")
    void reloadDisablesRconAndAbortsOldConnection() throws Exception {
        try (FakeRconServer rcon = new FakeRconServer("from-A", false, false);
             RuntimeConfigFixture fixture = new RuntimeConfigFixture("")) {

            QueQiaoRuntime created = startRuntime(fixture, true, "127.0.0.1", rcon.getPort());
            assertEquals("from-A", created.sendRconCommand("list"));

            fixture.write(RuntimeConfigFixture.yaml(
                    true, webSocketPort, false, "127.0.0.1", rcon.getPort(), RCON_PASSWORD));

            created.reload();

            RconException failure = assertThrows(RconException.class, () -> created.sendRconCommand("list"));
            assertEquals(RconException.Kind.DISABLED, failure.getKind());
            assertTrue(rcon.awaitClosedConnections(1), "禁用 RCON 后旧连接必须被中止");
        }
    }

    // ------------------------------------------------------------------
    // Shutdown
    // ------------------------------------------------------------------

    @Test
    @DisplayName("存在在途 RCON 命令时 shutdown 不会无限等待")
    void shutdownDoesNotWaitForInFlightRconCommand() throws Exception {
        // 收到命令后不返回响应：模拟"命令仍在等 Minecraft 主线程处理"
        try (FakeRconServer rcon = new FakeRconServer("unused", false, true);
             RuntimeConfigFixture fixture = new RuntimeConfigFixture("")) {

            QueQiaoRuntime created = startRuntime(fixture, true, "127.0.0.1", rcon.getPort());

            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<String> command = executor.submit(() -> created.sendRconCommand("list"));
            try {
                assertNotNull(rcon.awaitCommand(), "服务端应已收到命令（客户端正在等待响应）");

                long startedAt = System.nanoTime();
                created.shutdown();
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

                assertTrue(elapsedMillis < 2000L, "shutdown 不应等待在途 RCON 命令，实际耗时 " + elapsedMillis + "ms");
                assertEquals(RuntimeState.STOPPED, created.getState());

                Throwable failure = assertThrows(Exception.class, () -> command.get(5, TimeUnit.SECONDS))
                        .getCause();
                assertTrue(failure instanceof RconException, "在途命令应以 RconException 结束：" + failure);
                assertEquals(RconException.Kind.COMMAND_FAILED, ((RconException) failure).getKind());
                assertTrue(awaitPortClosed(webSocketPort), "关闭后 WebSocket 端口应释放");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private QueQiaoRuntime newRuntime() {
        runtime = QueQiaoRuntime.create(null, PLATFORM_CONTEXT, null);
        return runtime;
    }

    /**
     * 启动 Runtime，并确认 WebSocket 端口确实进入监听状态
     *
     * <p>端口被抢占时换一个端口重试：这是测试环境的竞态，不是被测行为。
     *
     * @return 已启动的 Runtime
     */
    private QueQiaoRuntime startRuntime(
            RuntimeConfigFixture fixture, boolean rconEnabled, String rconHost, int rconPort) throws Exception {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < PORT_ATTEMPTS; attempt++) {
            int port = findFreePort();
            fixture.write(RuntimeConfigFixture.yaml(
                    true, port, rconEnabled, rconHost, rconPort, RCON_PASSWORD));

            QueQiaoRuntime candidate = newRuntime();
            try {
                candidate.start();
            } catch (RuntimeException e) {
                lastFailure = e;
                candidate.shutdown();
                continue;
            }
            if (awaitPortAccepting(port)) {
                webSocketPort = port;
                return candidate;
            }
            LOGGER.info("WebSocket 端口 {} 未能进入监听状态，换端口重试（第 {} 次）", port, attempt + 1);
            candidate.shutdown();
        }
        throw new IllegalStateException(
                "无法让 WebSocket 端口进入监听状态（已重试 " + PORT_ATTEMPTS + " 次）", lastFailure);
    }

    private static boolean awaitPortAccepting(int port) throws InterruptedException {
        return awaitPortState(port, true);
    }

    private static boolean awaitPortClosed(int port) throws InterruptedException {
        return awaitPortState(port, false);
    }

    /**
     * 等待端口进入期望状态
     *
     * <p>Java-WebSocket 的 bind 与 close 都发生在 selector 线程上，`start()` / `stop()`
     * 返回时状态可能尚未稳定，因此这里给一个短暂宽限。
     */
    private static boolean awaitPortState(int port, boolean accepting) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PORT_WAIT_MILLIS);
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

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static ScheduledThreadPoolExecutor readReconnectScheduler(WebsocketManager manager) throws Exception {
        assertNotNull(manager, "运行中的 Runtime 应持有 WebsocketManager");
        Field field = WebsocketManager.class.getDeclaredField("reconnectScheduler");
        field.setAccessible(true);
        return (ScheduledThreadPoolExecutor) field.get(manager);
    }
}
