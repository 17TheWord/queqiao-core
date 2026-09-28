package io.github.theword.queqiao.core.runtime;

import io.github.theword.queqiao.core.config.io.ConfigStore;
import io.github.theword.queqiao.core.config.schema.ConfigKey;
import io.github.theword.queqiao.core.config.codec.StringCodec;
import io.github.theword.queqiao.core.event.PlayerChatEvent;
import io.github.theword.queqiao.core.platform.AbstractPlatformContext;
import io.github.theword.queqiao.core.protocol.handler.status.ServerStatusCollector;
import io.github.theword.queqiao.core.support.PlatformStubs;
import io.github.theword.queqiao.core.response.PrivateMessageResponse;
import io.github.theword.queqiao.core.utils.WebsocketManager;
import com.google.gson.JsonElement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QueQiaoRuntime} 生命周期测试
 *
 * <p>本测试由原静态全局上下文门面的生命周期测试迁移而来。归属变化说明：
 * <ul>
 *     <li><b>E1（参数不变量）</b>：由原门面的 {@code init} 迁移到 {@link QueQiaoRuntime#create}，
 *         因为它是 Runtime 的构造不变量。</li>
 *     <li><b>G2（重复初始化关闭旧实例）</b>：<b>不再由 Core 承担</b>。
 *         该策略属于"当前平台实例的 Runtime 管理"，由平台适配器负责；
 *         Core 只保证 Runtime 自身的 create / start / shutdown 与启动失败清理。
 *         因此原"重复初始化"用例已随门面一并移除。</li>
 *     <li><b>G3（空对象语义）</b>：{@code QueQiaoRuntime.empty()} 已删除。
 *         新语义是"没有 Runtime 就是真的没有 Runtime"，
 *         原 empty-runtime 专用用例（默认忽略命令、默认字段可读）随之删除。</li>
 * </ul>
 *
 * <p><b>测试隔离说明</b>：涉及 {@code start()} 的用例会读取工作目录下的
 * {@code plugins/queqiao/config.yml}，因此通过写入"全部禁用"的配置夹具
 * （并在结束时还原原文件）来避免绑定端口或产生网络副作用。
 */
class QueQiaoRuntimeLifecycleTest {

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(QueQiaoRuntimeLifecycleTest.class);

    /**
     * 空平台上下文：本用例只关心 Runtime 的生命周期，不需要平台行为
     */
    private static final AbstractPlatformContext<?, ?, ?, ?> NOOP_PLATFORM_CONTEXT =
            PlatformStubs.noopPlatformContext();

    private QueQiaoRuntime runtime;

    private QueQiaoRuntime newRuntime() {
        runtime = QueQiaoRuntime.create(null, NOOP_PLATFORM_CONTEXT, null);
        return runtime;
    }

    @AfterEach
    void tearDown() {
        QueQiaoRuntime current = runtime;
        runtime = null;
        if (current != null) {
            current.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // 创建期不变量
    // ------------------------------------------------------------------

    @Test
    @DisplayName("create 返回独立 Runtime，且 utils / 状态采集器均已就绪")
    void createProducesIndependentRuntime() {
        QueQiaoRuntime first = QueQiaoRuntime.create(null, NOOP_PLATFORM_CONTEXT, null);
        QueQiaoRuntime second = QueQiaoRuntime.create(null, NOOP_PLATFORM_CONTEXT, null);

        assertNotSame(first, second, "两次 create 必须得到不同实例");
        assertNotSame(first.utils, second.utils, "utils 必须与 Runtime 一一绑定，不得共享");
        assertNotSame(
                first.getServerStatusCollector(),
                second.getServerStatusCollector(),
                "状态采集器必须与 Runtime 一一绑定，不得共享");
    }

    @Test
    @DisplayName("create 时平台上下文为 null 立即失败并指明参数名（E1）")
    void createRejectsNullPlatformImplementations() {
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> QueQiaoRuntime.create(null, null, null));
        assertTrue(
                exception.getMessage() != null && exception.getMessage().contains("platformContext"),
                "错误信息应指明参数名，实际=" + exception.getMessage());
    }

    @Test
    @DisplayName("运行时创建时允许 Addon 在配置加载前注册 Schema")
    void runtimeAcceptsStartupConfigRegistration() {
        ConfigKey<String> addonKey = ConfigKey.builder("addons.ai.model", StringCodec.INSTANCE)
                .defaultValue("default-model")
                .build();

        QueQiaoRuntime created = QueQiaoRuntime.create(
                registry -> registry.register(addonKey), NOOP_PLATFORM_CONTEXT, null);

        assertNotNull(created.getConfigRegistry().findByPath("addons.ai.model"));
        assertFalse(created.getConfigRegistry().isFrozen());
        assertEquals("default-model", created.getConfig().get(addonKey));
    }

    // ------------------------------------------------------------------
    // 未启动状态：不再是"空对象"，但入口仍需安全
    // ------------------------------------------------------------------

    @Test
    @DisplayName("NEW 状态 shutdown 为 no-op：状态保持 NEW，之后仍可正常启动")
    void shutdownBeforeStartIsNoOpAndKeepsNewState() throws Exception {
        try (DisabledConfigFixture ignored = new DisabledConfigFixture()) {
            QueQiaoRuntime created = newRuntime();

            created.shutdown();
            created.shutdown();
            assertEquals(RuntimeState.NEW, created.getState(), "未启动时 shutdown 不应改变状态");

            // no-op 的语义保证：shutdown 之后仍然可以启动
            created.start();
            assertEquals(RuntimeState.RUNNING, created.getState(), "shutdown(no-op) 之后应能正常启动");
        }
    }

    @Test
    @DisplayName("未启动时 sendEvent 不抛异常（静默丢弃）")
    void sendEventBeforeStartIsSafe() {
        QueQiaoRuntime created = newRuntime();

        created.sendEvent(new PlayerChatEvent(null, "", "", "hello"));
    }

    @Test
    @DisplayName("未启动时 translate 返回原 key，翻译为关闭状态")
    void translateBeforeStartReturnsKey() {
        QueQiaoRuntime created = newRuntime();

        assertEquals("death.attack.player", created.translate("death.attack.player", new String[] {"Steve"}));
        assertFalse(created.isTranslationEnabled(), "未启动时翻译应为关闭状态");
    }

    // ------------------------------------------------------------------
    // 启动 / 关闭
    // ------------------------------------------------------------------

    @Test
    @DisplayName("start 后可得到 WebsocketManager，shutdown 释放全部资源且幂等")
    void shutdownReleasesStartedResourcesAndIsIdempotent() throws Exception {
        try (DisabledConfigFixture ignored = new DisabledConfigFixture()) {
            QueQiaoRuntime created = newRuntime();
            assertEquals(RuntimeState.NEW, created.getState(), "初始状态应为 NEW");

            created.start();
            assertEquals(RuntimeState.RUNNING, created.getState(), "启动成功后状态应为 RUNNING");

            WebsocketManager manager = created.getWebsocketManager();
            assertNotNull(manager, "start 后应存在 WebsocketManager");
            ScheduledThreadPoolExecutor reconnectScheduler = readReconnectScheduler(manager);
            assertFalse(reconnectScheduler.isShutdown(), "start 后共享重连调度器应可用");

            ScheduledThreadPoolExecutor statusExecutor =
                    readRefreshExecutor(created.getServerStatusCollector());
            assertNotNull(statusExecutor, "start 后采集调度器应已创建");
            assertFalse(statusExecutor.isShutdown(), "start 后采集调度器应可用");

            created.shutdown();
            assertEquals(RuntimeState.STOPPED, created.getState(), "关闭后状态应为 STOPPED");
            assertTrue(reconnectScheduler.isShutdown(), "shutdown 应释放共享重连调度器");
            assertTrue(statusExecutor.isShutdown(), "shutdown 应关闭采集调度器（否则线程泄漏）");
            assertNull(created.getWebsocketManager(), "shutdown 应清空 WebsocketManager");

            // 幂等：重复 shutdown 不得抛异常，状态保持 STOPPED
            created.shutdown();
            assertEquals(RuntimeState.STOPPED, created.getState(), "重复 shutdown 后状态应保持 STOPPED");
            assertTrue(reconnectScheduler.isShutdown(), "重复 shutdown 后调度器仍应处于已关闭状态");
        }
    }

    // ------------------------------------------------------------------
    // 生命周期状态机
    // ------------------------------------------------------------------

    @Test
    @DisplayName("重复 start 被拒，且不重建 WebSocket / 采集器资源")
    void repeatedStartIsRejectedAndKeepsSameResources() throws Exception {
        try (DisabledConfigFixture ignored = new DisabledConfigFixture()) {
            QueQiaoRuntime created = newRuntime();
            created.start();

            WebsocketManager firstManager = created.getWebsocketManager();
            ScheduledThreadPoolExecutor firstStatusExecutor =
                    readRefreshExecutor(created.getServerStatusCollector());
            assertNotNull(firstManager, "首次启动后应存在 WebsocketManager");
            assertNotNull(firstStatusExecutor, "首次启动后应存在采集调度器");

            assertThrows(IllegalStateException.class, created::start);

            // 状态保持 RUNNING，且资源仍是同一实例（identity），没有被关闭后重建
            assertEquals(RuntimeState.RUNNING, created.getState(), "重复 start 被拒后状态应保持 RUNNING");
            assertSame(firstManager, created.getWebsocketManager(), "重复 start 不应替换 WebsocketManager");
            assertSame(
                    firstStatusExecutor,
                    readRefreshExecutor(created.getServerStatusCollector()),
                    "重复 start 不应重建采集调度器");
            assertFalse(firstStatusExecutor.isShutdown(), "重复 start 不应关闭原采集调度器");
        }
    }

    @Test
    @DisplayName("STOPPED 状态不允许再次 start")
    void stoppedRuntimeRejectsRestart() throws Exception {
        try (DisabledConfigFixture ignored = new DisabledConfigFixture()) {
            QueQiaoRuntime created = newRuntime();
            created.start();
            created.shutdown();
            assertEquals(RuntimeState.STOPPED, created.getState(), "关闭后状态应为 STOPPED");

            assertThrows(IllegalStateException.class, created::start);
            assertEquals(RuntimeState.STOPPED, created.getState(), "被拒后状态应保持 STOPPED");
        }
    }

    @Test
    @DisplayName("reload 只允许 RUNNING 状态")
    void reloadRequiresRunningState() throws Exception {
        try (DisabledConfigFixture ignored = new DisabledConfigFixture()) {
            QueQiaoRuntime created = newRuntime();

            // NEW
            assertThrows(IllegalStateException.class, () -> created.reload(null));
            assertEquals(RuntimeState.NEW, created.getState(), "被拒的 reload 不应改变状态");

            // RUNNING
            created.start();
            created.reload(null);
            assertEquals(RuntimeState.RUNNING, created.getState(), "reload 不应改变状态");

            // STOPPED
            created.shutdown();
            assertThrows(IllegalStateException.class, () -> created.reload(null));
            assertEquals(RuntimeState.STOPPED, created.getState(), "被拒的 reload 不应改变状态");
        }
    }

    /**
     * 通过反射读取 Manager 的私有调度器，避免为测试污染生产 API
     */
    private static ScheduledThreadPoolExecutor readReconnectScheduler(WebsocketManager manager) throws Exception {
        Field field = WebsocketManager.class.getDeclaredField("reconnectScheduler");
        field.setAccessible(true);
        return (ScheduledThreadPoolExecutor) field.get(manager);
    }

    /**
     * 通过反射读取采集器的私有调度器
     *
     * <p>断言对象是"该实例自己持有的 executor"，而不是线程名——后者在 JVM 内全局可见，
     * 会被其它用例正在退出的线程污染，导致断言不稳定。
     */
    private static ScheduledThreadPoolExecutor readRefreshExecutor(ServerStatusCollector collector) throws Exception {
        Field field = ServerStatusCollector.class.getDeclaredField("refreshExecutor");
        field.setAccessible(true);
        return (ScheduledThreadPoolExecutor) field.get(collector);
    }

    /**
     * "全部禁用"配置夹具
     *
     * <p>把 WebSocket Server / Client / Rcon 全部关闭，使 {@code start()} 不绑定端口、
     * 不建立任何连接。构造时备份原配置文件，{@link #close()} 时还原。
     */
    private static final class DisabledConfigFixture implements AutoCloseable {

        private static final Path CONFIG_PATH = ConfigStore.resolveConfigPath(false);
        private static final Path BACKUP_PATH = CONFIG_PATH.resolveSibling(CONFIG_PATH.getFileName() + ".bak");

        private static final String DISABLED_CONFIG = String.join("\n",
                "enable: true",
                "debug: false",
                "server_name: \"TestServer\"",
                "access_token: \"\"",
                "message_prefix: \"[鹊桥]\"",
                "enable_translation: false",
                "websocket_server:",
                "  enable: false",
                "  host: \"127.0.0.1\"",
                "  port: 8080",
                "websocket_client:",
                "  enable: false",
                "  reconnect_interval: 1",
                "  reconnect_max_times: 1",
                "  url_list: []",
                "rcon:",
                "  enable: false",
                "  port: 25575",
                "  password: \"\"",
                "subscribe_event:",
                "  player_chat: false",
                "  player_death: false",
                "  player_join: false",
                "  player_quit: false",
                "  player_command: false",
                "  player_advancement: false",
                "ignored_commands: []",
                "");

        private final byte[] previousContent;

        private DisabledConfigFixture() throws IOException {
            this.previousContent = Files.exists(CONFIG_PATH) ? Files.readAllBytes(CONFIG_PATH) : null;
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.write(CONFIG_PATH, DISABLED_CONFIG.getBytes(StandardCharsets.UTF_8));
            LOGGER.info("已写入禁用全部传输层的测试配置：{}", CONFIG_PATH.toAbsolutePath());
        }

        @Override
        public void close() throws IOException {
            if (previousContent != null) {
                Files.write(CONFIG_PATH, previousContent);
                return;
            }
            Files.deleteIfExists(CONFIG_PATH);
            Files.deleteIfExists(BACKUP_PATH);
        }
    }
}
