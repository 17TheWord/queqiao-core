package io.github.theword.queqiao.core.config.io;

import io.github.theword.queqiao.core.config.Config;
import io.github.theword.queqiao.core.config.schema.ConfigKey;
import io.github.theword.queqiao.core.config.ConfigKeys;
import io.github.theword.queqiao.core.config.schema.ConfigRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旧配置兼容性回归测试
 *
 * <p>用<b>旧版本随包的 {@code config.example.yml}</b>（即用户手上正在用的配置形态）作为输入，
 * 验证旧版 23 个配置路径都能被 {@link ConfigKeys} 中的 Schema 正确读取，
 * 并保持取值、类型与来源语义——<b>不重命名字段、不改变嵌套结构、不改变默认值</b>。
 *
 * <p><b>fixture 来源</b>：旧 {@code src/main/resources/queqiao/config.example.yml} 的副本，
 * 现存放于 {@code src/test/resources/fixtures/config/legacy-config.yml}。
 * 生产资源已随配置系统重写而删除，fixture 因此成为"旧配置形态"的唯一权威快照，
 * 使这条兼容性回归不依赖任何生产资源。
 */
class LegacyConfigCompatibilityTest {

    private static final String FIXTURE = "fixtures/config/legacy-config.yml";

    private Config runtime;
    private ConfigLoadResult result;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void loadLegacyConfig() throws IOException {
        ConfigRegistry registry = new ConfigRegistry();
        ConfigKeys.registerAll(registry);
        runtime = new Config(registry);
        ConfigLoader loader = new ConfigLoader(registry, runtime);

        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(FIXTURE)) {
            assertNotNull(inputStream, "fixture 必须存在：" + FIXTURE);
            Map<String, Object> document = (Map<String, Object>) new Yaml().load(inputStream);
            result = loader.load(ConfigFileState.VALID, document);
        }
    }

    @Test
    @DisplayName("注册全部核心配置项，并保持旧版 23 个路径兼容")
    void declaresAllLegacyPaths() {
        assertEquals(25, ConfigKeys.all().size(), "核心配置项数量应包含新增状态采集与 Rcon 地址配置");

        ConfigRegistry registry = new ConfigRegistry();
        ConfigKeys.registerAll(registry);
        for (ConfigKey<?> key : ConfigKeys.all()) {
            assertNotNull(registry.findByPath(key.getPath()), "路径应已注册：" + key.getPath());
        }
    }

    @Test
    @DisplayName("旧配置字段识别为 USER，新加配置保留 Schema 默认值")
    void allLegacyFieldsAreUserSourced() {
        for (ConfigKey<?> key : ConfigKeys.all()) {
            if (key.equals(ConfigKeys.Status.REFRESH_INTERVAL_SECONDS) || key.equals(ConfigKeys.Rcon.HOST)) {
                assertFalse(runtime.contains(key), "旧配置不包含新增字段：" + key.getPath());
                assertTrue(runtime.isDefault(key), "新增字段应使用 Schema 默认值：" + key.getPath());
                continue;
            }
            assertTrue(runtime.contains(key), "字段应来源于用户配置：" + key.getPath());
            assertFalse(runtime.isDefault(key), "字段来源应为 USER：" + key.getPath());
        }
    }

    @Test
    @DisplayName("旧配置不产生任何未知字段")
    void legacyConfigHasNoUnknownFields() {
        assertEquals(Collections.emptyList(), result.getUnknownCorePaths(), "不应有未知核心字段");
        assertEquals(Collections.emptyList(), result.getUnknownAddonPaths(), "不应有未知 addons 字段");
    }

    @Test
    @DisplayName("旧配置的取值与类型保持正确")
    void legacyValuesAndTypes() {
        // 顶层
        assertEquals(Boolean.TRUE, runtime.get(ConfigKeys.ENABLE));
        assertEquals(Boolean.FALSE, runtime.get(ConfigKeys.DEBUG));
        assertEquals("Server", runtime.get(ConfigKeys.SERVER_NAME));
        assertEquals("", runtime.get(ConfigKeys.ACCESS_TOKEN));
        assertEquals("[鹊桥]", runtime.get(ConfigKeys.MESSAGE_PREFIX));
        assertEquals(Boolean.FALSE, runtime.get(ConfigKeys.ENABLE_TRANSLATION));
        assertEquals(Collections.emptyList(), runtime.get(ConfigKeys.IGNORED_COMMANDS));

        // websocket_server
        assertEquals(Boolean.TRUE, runtime.get(ConfigKeys.WebSocket.ENABLE));
        assertEquals("127.0.0.1", runtime.get(ConfigKeys.WebSocket.HOST));
        assertEquals(Integer.valueOf(8080), runtime.get(ConfigKeys.WebSocket.PORT));
        assertTrue(runtime.get(ConfigKeys.WebSocket.PORT) instanceof Integer, "端口必须是 Integer");

        // websocket_client
        assertEquals(Boolean.FALSE, runtime.get(ConfigKeys.WebSocketClient.ENABLE));
        assertEquals(Integer.valueOf(5), runtime.get(ConfigKeys.WebSocketClient.RECONNECT_INTERVAL));
        assertEquals(Integer.valueOf(5), runtime.get(ConfigKeys.WebSocketClient.RECONNECT_MAX_TIMES));

        List<String> urlList = runtime.get(ConfigKeys.WebSocketClient.URL_LIST);
        assertEquals(1, urlList.size(), "示例配置含 1 个连接地址");
        assertTrue(urlList.get(0).startsWith("ws://"), "URL 列表元素应为 String：" + urlList);

        // rcon
        assertEquals(Boolean.FALSE, runtime.get(ConfigKeys.Rcon.ENABLE));
        assertEquals(Integer.valueOf(25575), runtime.get(ConfigKeys.Rcon.PORT));
        assertEquals("", runtime.get(ConfigKeys.Rcon.PASSWORD));
        // 旧配置没有 rcon.host：内存中取 Schema 默认值，不要求用户迁移文件
        assertEquals("127.0.0.1", runtime.get(ConfigKeys.Rcon.HOST), "缺失 rcon.host 时应回退默认地址");

        // subscribe_event
        assertEquals(Boolean.TRUE, runtime.get(ConfigKeys.SubscribeEvent.PLAYER_CHAT));
        assertEquals(Boolean.TRUE, runtime.get(ConfigKeys.SubscribeEvent.PLAYER_DEATH));
        assertEquals(Boolean.TRUE, runtime.get(ConfigKeys.SubscribeEvent.PLAYER_JOIN));
        assertEquals(Boolean.TRUE, runtime.get(ConfigKeys.SubscribeEvent.PLAYER_QUIT));
        assertEquals(Boolean.TRUE, runtime.get(ConfigKeys.SubscribeEvent.PLAYER_COMMAND));
        assertEquals(Boolean.TRUE, runtime.get(ConfigKeys.SubscribeEvent.PLAYER_ADVANCEMENT));
    }

    @Test
    @DisplayName("Schema 默认值与旧版随包配置一致（升级后未显式配置的项行为不变）")
    void schemaDefaultsMatchLegacyConfig() {
        // 这条守住"Schema 默认值 == 旧版模板默认值"，避免升级后
        // 用户没有显式写过的配置项悄悄改变行为。
        ConfigDocument document = result.getDocument();

        assertEquals(ConfigKeys.ENABLE.defaultValue(), document.get("enable"));
        assertEquals(ConfigKeys.DEBUG.defaultValue(), document.get("debug"));
        assertEquals(ConfigKeys.SERVER_NAME.defaultValue(), document.get("server_name"));
        assertEquals(ConfigKeys.ACCESS_TOKEN.defaultValue(), document.get("access_token"));
        assertEquals(ConfigKeys.MESSAGE_PREFIX.defaultValue(), document.get("message_prefix"));
        assertEquals(ConfigKeys.ENABLE_TRANSLATION.defaultValue(), document.get("enable_translation"));

        assertEquals(ConfigKeys.WebSocket.ENABLE.defaultValue(), document.get("websocket_server.enable"));
        assertEquals(ConfigKeys.WebSocket.HOST.defaultValue(), document.get("websocket_server.host"));
        assertEquals(ConfigKeys.WebSocket.PORT.defaultValue(), document.get("websocket_server.port"));

        assertEquals(ConfigKeys.WebSocketClient.ENABLE.defaultValue(), document.get("websocket_client.enable"));
        assertEquals(
                ConfigKeys.WebSocketClient.RECONNECT_INTERVAL.defaultValue(),
                document.get("websocket_client.reconnect_interval"));
        assertEquals(
                ConfigKeys.WebSocketClient.RECONNECT_MAX_TIMES.defaultValue(),
                document.get("websocket_client.reconnect_max_times"));

        assertEquals(ConfigKeys.Rcon.ENABLE.defaultValue(), document.get("rcon.enable"));
        assertEquals(ConfigKeys.Rcon.PORT.defaultValue(), document.get("rcon.port"));
        assertEquals(ConfigKeys.Rcon.PASSWORD.defaultValue(), document.get("rcon.password"));

        assertEquals(
                ConfigKeys.SubscribeEvent.PLAYER_CHAT.defaultValue(), document.get("subscribe_event.player_chat"));
        assertEquals(
                ConfigKeys.SubscribeEvent.PLAYER_DEATH.defaultValue(), document.get("subscribe_event.player_death"));
        assertEquals(
                ConfigKeys.SubscribeEvent.PLAYER_JOIN.defaultValue(), document.get("subscribe_event.player_join"));
        assertEquals(
                ConfigKeys.SubscribeEvent.PLAYER_QUIT.defaultValue(), document.get("subscribe_event.player_quit"));
        assertEquals(
                ConfigKeys.SubscribeEvent.PLAYER_COMMAND.defaultValue(),
                document.get("subscribe_event.player_command"));
        assertEquals(
                ConfigKeys.SubscribeEvent.PLAYER_ADVANCEMENT.defaultValue(),
                document.get("subscribe_event.player_advancement"));

        // ignored_commands 是"强制项 ∪ 用户配置"，比较生效集合
        assertEquals(
                new HashSet<>(ConfigKeys.MANDATORY_IGNORED_COMMANDS),
                ConfigKeys.effectiveIgnoredCommands(runtime),
                "旧版配置中 ignored_commands 为空，生效集合应等于强制项");
    }
}
