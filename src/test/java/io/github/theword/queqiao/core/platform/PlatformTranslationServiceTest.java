package io.github.theword.queqiao.core.platform;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import io.github.theword.queqiao.core.config.io.ConfigStore;
import io.github.theword.queqiao.core.constant.BaseConstant;
import io.github.theword.queqiao.core.constant.ServerTypeConstant;
import io.github.theword.queqiao.core.event.model.PlayerModel;
import io.github.theword.queqiao.core.runtime.QueQiaoRuntime;
import io.github.theword.queqiao.core.runtime.RuntimeState;
import io.github.theword.queqiao.core.translation.TranslationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台数据目录与平台翻译服务
 *
 * <p>本类锁定三件事：
 * <ol>
 *     <li>默认目录与历史布局<b>完全一致</b>（模组 {@code config/QueQiao}、插件 {@code plugins/QueQiao}）；</li>
 *     <li>平台覆盖 {@code getDataDirectory()} 后，配置与翻译<b>同步</b>跟着走；</li>
 *     <li>翻译服务的生命周期由 Runtime 按配置调度，关闭后不留活动状态。</li>
 * </ol>
 */
@DisplayName("AbstractPlatformContext：数据目录与翻译服务")
class PlatformTranslationServiceTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlatformTranslationServiceTest.class);

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // 目录
    // ------------------------------------------------------------------

    @Test
    @DisplayName("默认数据目录：模组端 config/QueQiao，插件端 plugins/QueQiao（与旧版本一致）")
    void defaultDataDirectoryMatchesLegacyLayout() {
        assertEquals(Paths.get("config", BaseConstant.MODULE_NAME),
                new StubPlatformContext(ServerTypeConstant.FABRIC, null).getDataDirectory());
        assertEquals(Paths.get("config", BaseConstant.MODULE_NAME),
                new StubPlatformContext(ServerTypeConstant.NEOFORGE, null).getDataDirectory());
        assertEquals(Paths.get("plugins", BaseConstant.MODULE_NAME),
                new StubPlatformContext(ServerTypeConstant.SPIGOT, null).getDataDirectory());
    }

    @Test
    @DisplayName("配置文件路径 = 数据目录下的 config.yml")
    void configPathFollowsDataDirectory() {
        assertEquals(tempDir.resolve("config.yml"),
                ConfigStore.resolveConfigPath(tempDir));
    }

    // ------------------------------------------------------------------
    // 翻译服务归属平台
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同一个 PlatformContext 始终返回同一个 TranslationService")
    void returnsSameInstance() {
        StubPlatformContext platform = new StubPlatformContext(ServerTypeConstant.SPIGOT, null);

        TranslationService first = platform.getTranslationService();
        TranslationService second = platform.getTranslationService();

        assertSame(first, second, "重复获取必须是同一实例");
    }

    @Test
    @DisplayName("覆盖 getDataDirectory() 后，翻译服务从新目录读取")
    void overriddenDataDirectoryDrivesTranslation() throws IOException {
        StubPlatformContext platform = new StubPlatformContext(ServerTypeConstant.SPIGOT, tempDir);
        writeTranslate(tempDir, "zh_cn.json", "{\"hello\": \"你好\"}");

        platform.getTranslationService().reload(true);

        assertTrue(platform.getTranslationService().isEnabled());
        assertEquals("你好", platform.getTranslationService().translate("hello", new Object[0]));
    }

    @Test
    @DisplayName("未重写 parseTranslateModel 的平台返回 null，Core 不因此失败")
    void defaultParseTranslateModelReturnsNull() {
        StubPlatformContext platform = new StubPlatformContext(ServerTypeConstant.SPIGOT, null);

        assertNull(platform.parseTranslateModel(new TestComponent(new JsonPrimitive("hi"))),
                "基类默认实现表示平台没有原生组件解析能力");
    }

    // ------------------------------------------------------------------
    // Runtime 调度
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Runtime start：配置启用翻译 → 平台服务被加载并可翻译；shutdown 后不留活动状态")
    void runtimeEnablesPlatformTranslation() throws Exception {
        StubPlatformContext platform = new StubPlatformContext(ServerTypeConstant.SPIGOT, tempDir);
        writeConfig(tempDir, true);
        writeTranslate(tempDir, "zh_cn.json", "{\"death.attack.player\": \"%s 被击败了\"}");

        QueQiaoRuntime runtime = QueQiaoRuntime.start(platform);
        try {
            assertTrue(platform.getTranslationService().isEnabled(), "配置启用后应加载翻译");
            assertEquals("Steve 被击败了", platform.getTranslationService()
                    .translate("death.attack.player", new Object[] {"Steve"}));
        } finally {
            runtime.shutdown();
        }

        assertFalse(platform.getTranslationService().isEnabled(),
                "shutdown 后不应留下活动翻译状态");
    }

    @Test
    @DisplayName("Runtime start：配置禁用翻译 → 即使目录里有文件也不加载")
    void runtimeKeepsTranslationOffWhenDisabled() throws Exception {
        StubPlatformContext platform = new StubPlatformContext(ServerTypeConstant.SPIGOT, tempDir);
        writeConfig(tempDir, false);
        writeTranslate(tempDir, "zh_cn.json", "{\"hello\": \"你好\"}");

        QueQiaoRuntime runtime = QueQiaoRuntime.start(platform);
        try {
            assertFalse(platform.getTranslationService().isEnabled(), "配置禁用时不应加载翻译");
            assertEquals("hello", platform.getTranslationService().translate("hello", new Object[0]));
        } finally {
            runtime.shutdown();
        }
    }

    @Test
    @DisplayName("Runtime reload 后翻译仍按配置生效")
    void runtimeReloadKeepsTranslationActive() throws Exception {
        StubPlatformContext platform = new StubPlatformContext(ServerTypeConstant.SPIGOT, tempDir);
        writeConfig(tempDir, true);
        writeTranslate(tempDir, "zh_cn.json", "{\"a\": \"甲\"}");

        QueQiaoRuntime runtime = QueQiaoRuntime.start(platform);
        try {
            runtime.reload();

            assertTrue(platform.getTranslationService().isEnabled(), "reload 后翻译应仍然启用");
            assertEquals("甲", platform.getTranslationService().translate("a", new Object[0]));
        } finally {
            runtime.shutdown();
        }
    }

    @Test
    @DisplayName("Runtime 翻译开关可动态切换：同一个 Runtime 与同一个翻译服务实例走完整个生命周期")
    void runtimeTogglesTranslationAcrossReloads() throws Exception {
        StubPlatformContext platform = new StubPlatformContext(ServerTypeConstant.SPIGOT, tempDir);
        writeConfig(tempDir, true);
        writeTranslate(tempDir, "zh_cn.json", "{\"hello\": \"你好\"}");

        QueQiaoRuntime runtime = QueQiaoRuntime.start(platform);
        TranslationService service = platform.getTranslationService();
        try {
            // ① 配置启用 → 启动即加载
            assertEquals(RuntimeState.RUNNING, runtime.getState(), "前置条件：Runtime 应已启动");
            assertTrue(service.isEnabled(), "启用配置下应加载翻译");
            assertEquals("你好", service.translate("hello", new Object[0]));

            // ② 配置改为 false 后 reload → 服务禁用，translate 回到 key
            writeConfig(tempDir, false);
            runtime.reload();
            assertSame(service, platform.getTranslationService(), "reload 不应更换翻译服务实例");
            assertFalse(service.isEnabled(), "配置关闭后应禁用翻译");
            assertEquals("hello", service.translate("hello", new Object[0]));

            // ③ 改动 JSON 但不 reload → 暂不生效
            writeTranslate(tempDir, "zh_cn.json", "{\"hello\": \"哈喽\"}");
            assertFalse(service.isEnabled(), "未 reload 时不应自行加载");
            assertEquals("hello", service.translate("hello", new Object[0]));

            // ④ 配置改回 true 再 reload → 重新读取 JSON，返回新译文
            writeConfig(tempDir, true);
            runtime.reload();
            assertSame(service, platform.getTranslationService(), "再次 reload 仍应是同一实例");
            assertTrue(service.isEnabled(), "重新启用后应再次加载");
            assertEquals("哈喽", service.translate("hello", new Object[0]), "应读到改动后的 JSON 内容");

            // 配置切换全程复用同一个 Runtime，不需要重建
            assertEquals(RuntimeState.RUNNING, runtime.getState(), "切换配置期间 Runtime 应保持 RUNNING");
        } finally {
            runtime.shutdown();
        }

        assertSame(service, platform.getTranslationService(), "shutdown 也不应更换实例");
        assertFalse(service.isEnabled(), "shutdown 后不存在活动翻译状态");
        assertEquals(RuntimeState.STOPPED, runtime.getState(), "shutdown 后 Runtime 应为 STOPPED");
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static void writeTranslate(Path dataDirectory, String fileName, String json) throws IOException {
        Path directory = dataDirectory.resolve("translate");
        Files.createDirectories(directory);
        Files.write(directory.resolve(fileName), json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 写入一份"关闭全部传输层"的配置，使 {@code start()} 不绑定端口、不建立连接
     */
    private static void writeConfig(Path dataDirectory, boolean enableTranslation) throws IOException {
        Files.createDirectories(dataDirectory);
        String content = String.join("\n",
                "enable: true",
                "debug: false",
                "server_name: \"TestServer\"",
                "access_token: \"\"",
                "message_prefix: \"[鹊桥]\"",
                "enable_translation: " + enableTranslation,
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
        Files.write(ConfigStore.resolveConfigPath(dataDirectory), content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 测试替身：可指定平台类型，并可覆盖数据目录
     *
     * <p>{@code dataDirectory} 为 null 时走基类默认推导（用于验证与历史布局一致）。
     */
    private static final class StubPlatformContext
            extends AbstractPlatformContext<TestServer, TestComponent, TestPlayer> {

        private final String serverType;
        private final Path dataDirectory;

        private StubPlatformContext(String serverType, Path dataDirectory) {
            super(new TestServer());
            this.serverType = serverType;
            this.dataDirectory = dataDirectory;
        }

        @Override
        public String getServerType() {
            return serverType;
        }

        @Override
        public String getServerVersion() {
            return "1.20.1";
        }

        @Override
        public Path getDataDirectory() {
            return dataDirectory != null ? dataDirectory : super.getDataDirectory();
        }

        @Override
        public TestComponent jsonToComponent(JsonElement jsonElement) {
            return new TestComponent(jsonElement);
        }

        @Override
        public Collection<TestPlayer> getPlayers() {
            return Collections.emptyList();
        }

        @Override
        public String getPlayerName(TestPlayer player) {
            return player.getName();
        }

        @Override
        public UUID getPlayerUUID(TestPlayer player) {
            return player.getUuid();
        }

        @Override
        public PlayerModel getPlayerModel(TestPlayer player) {
            PlayerModel playerModel = new PlayerModel();
            playerModel.setNickname(player.getName());
            playerModel.setUuid(player.getUuid());
            return playerModel;
        }

        @Override
        public PlatformResult<Void> broadcast(TestComponent component) {
            return PlatformResult.success(null);
        }

        @Override
        public PlatformResult<Void> sendPrivateMessage(TestPlayer player, TestComponent component) {
            return PlatformResult.success(null);
        }
    }
}
