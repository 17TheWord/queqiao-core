package io.github.theword.queqiao.core.config.io;

import io.github.theword.queqiao.core.config.codec.IntegerCodec;
import io.github.theword.queqiao.core.config.schema.ConfigKey;
import io.github.theword.queqiao.core.config.ConfigKeys;
import io.github.theword.queqiao.core.config.schema.ConfigRegistry;
import io.github.theword.queqiao.core.config.Config;
import io.github.theword.queqiao.core.config.schema.ConfigSectionNode;
import io.github.theword.queqiao.core.config.codec.StringCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfigWriter} 渲染测试
 *
 * <p>覆盖 Phase 5 补充约束 §44 要求的能力：标量、列表、注释、区块、顺序、空行、
 * 默认值、未知字段、特殊字符串、确定性输出、原子写。
 */
class ConfigWriterTest {

    private final ConfigWriter writer = new ConfigWriter();

    private ConfigRegistry registry;
    private Config runtime;
    private ConfigDocument document;

    @BeforeEach
    void setUp() {
        registry = new ConfigRegistry();
        ConfigKeys.registerAll(registry);
        runtime = new Config(registry);
        document = ConfigDocument.empty();
    }

    private String render() {
        return writer.render(ConfigWriteSnapshot.of(registry, runtime, document));
    }

    private static Map<String, Object> readYaml(Path path) throws IOException {
        return parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String yaml) {
        return (Map<String, Object>) new Yaml().load(yaml);
    }

    // ------------------------------------------------------------------
    // 基本渲染
    // ------------------------------------------------------------------

    @Test
    @DisplayName("首次生成：包含全部配置项与区块")
    void generatesAllKeys() {
        String yaml = render();

        for (ConfigKey<?> key : ConfigKeys.all()) {
            assertTrue(
                    yaml.contains(key.getName() + ":"),
                    "生成的 YAML 应包含配置项：" + key.getPath());
        }
        assertTrue(yaml.contains("websocket_server:"));
        assertTrue(yaml.contains("websocket_client:"));
        assertTrue(yaml.contains("rcon:"));
        assertTrue(yaml.contains("subscribe_event:"));
    }

    @Test
    @DisplayName("默认值必须被写出（不因为来源是 DEFAULT 就省略字段）")
    void defaultValuesAreWritten() {
        String yaml = render();

        assertTrue(yaml.contains("server_name: Server"), "默认值应出现在生成的文档中");
        assertTrue(yaml.contains("port: 8080"));
        assertTrue(yaml.contains("port: 25575"));
    }

    @Test
    @DisplayName("rcon 区块写出 host，默认值为 IPv4 回环地址，且字段顺序为 enable→host→port→password")
    @SuppressWarnings("unchecked")
    void writesRconHost() {
        String yaml = render();

        Map<String, Object> root = parse(yaml);
        Map<String, Object> rcon = (Map<String, Object>) root.get("rcon");
        assertNotNull(rcon, "应生成 rcon 区块");
        assertEquals("127.0.0.1", rcon.get("host"), "应写出 rcon.host 默认值");
        assertEquals(
                "enable,host,port,password",
                String.join(",", rcon.keySet()),
                "rcon 区块字段顺序应与声明顺序一致");
    }

    @Test
    @DisplayName("写盘快照：修改快照读取值不影响输出")
    void writeSnapshotDoesNotExposeMutableRuntimeValue() {
        runtime.set(ConfigKeys.WebSocketClient.URL_LIST, Arrays.asList("ws://original"));
        ConfigWriteSnapshot snapshot = ConfigWriteSnapshot.of(registry, runtime, document);

        List<String> fromSnapshot = snapshot.getRuntime().valueOf(ConfigKeys.WebSocketClient.URL_LIST);
        fromSnapshot.add("ws://injected");

        String yaml = writer.render(snapshot);
        assertTrue(yaml.contains("- ws://original"));
        assertFalse(yaml.contains("ws://injected"));
    }

    @Test
    @DisplayName("注释：区块注释位于区块键之前，配置项注释在键之前并带缩进")
    void commentsAreEmitted() {
        String yaml = render();

        int sectionComment = yaml.indexOf("# WebSocket Server配置项");
        int sectionKey = yaml.indexOf("websocket_server:");
        assertTrue(sectionComment >= 0, "应有区块注释");
        assertTrue(sectionComment < sectionKey, "区块注释必须位于区块键之前");

        int keyComment = yaml.indexOf("# 是否启用");
        assertTrue(keyComment >= 0, "应有配置项注释");
        assertTrue(yaml.contains("\n  # 是否启用"), "配置项注释应按层级缩进");
    }

    @Test
    @DisplayName("顺序：完全按声明顺序，不做字母排序")
    void orderingFollowsDeclaration() {
        String yaml = render();

        assertTrue(yaml.indexOf("server_name:") < yaml.indexOf("websocket_server:"), "声明顺序应被保持");
        assertTrue(yaml.indexOf("websocket_server:") < yaml.indexOf("rcon:"), "区块顺序应被保持");
    }

    @Test
    @DisplayName("声明顺序就是注册顺序，即使与字母序相反")
    void orderingIsNotAlphabetical() {
        ConfigRegistry custom = new ConfigRegistry();
        ConfigKey<Integer> b = ConfigKey.builder("b", IntegerCodec.INSTANCE)
                .defaultValue(2)
                .build();
        ConfigKey<Integer> a = ConfigKey.builder("a", IntegerCodec.INSTANCE)
                .defaultValue(1)
                .build();
        ConfigKey<Integer> c = ConfigKey.builder("c", IntegerCodec.INSTANCE)
                .defaultValue(3)
                .build();
        custom.register(b);
        custom.register(a);
        custom.register(c);

        Config customRuntime = new Config(custom);
        String yaml = writer.render(ConfigWriteSnapshot.of(custom, customRuntime, ConfigDocument.empty()));

        assertTrue(yaml.indexOf("b:") < yaml.indexOf("a:"), "应按注册顺序 b → a → c");
        assertTrue(yaml.indexOf("a:") < yaml.indexOf("c:"));
    }

    @Test
    @DisplayName("空行：blankLinesBefore 生效，且不会被重复叠加")
    void blankLinesAreAppliedExactly() {
        ConfigRegistry custom = new ConfigRegistry();
        custom.register(ConfigKey.builder("x", StringCodec.INSTANCE).defaultValue("v").build());
        custom.register(ConfigSectionNode.of("sectionA").blankLinesBefore(0));
        custom.register(ConfigKey.builder("sectionA.k", StringCodec.INSTANCE).defaultValue("v").build());
        custom.register(ConfigSectionNode.of("sectionB").blankLinesBefore(2));
        custom.register(ConfigKey.builder("sectionB.k", StringCodec.INSTANCE).defaultValue("v").build());

        Config customRuntime = new Config(custom);
        String yaml = writer.render(ConfigWriteSnapshot.of(custom, customRuntime, ConfigDocument.empty()));

        String expected = "x: v\nsectionA:\n  k: v\n\n\nsectionB:\n  k: v\n";
        assertEquals(expected, yaml, "空行数量必须精确等于 blankLinesBefore");
    }

    // ------------------------------------------------------------------
    // 值渲染
    // ------------------------------------------------------------------

    @Test
    @DisplayName("列表：保持原顺序，不排序不去重，逐项换行")
    void listIsRenderedInOrder() {
        runtime.set(
                ConfigKeys.WebSocketClient.URL_LIST,
                Arrays.asList("ws://b", "ws://a", "ws://b"));

        String yaml = render();

        assertTrue(yaml.contains("url_list:\n    - ws://b\n    - ws://a\n    - ws://b"), "列表应保持原顺序：\n" + yaml);
    }

    @Test
    @DisplayName("空列表输出为 []")
    void emptyListIsRendered() {
        runtime.set(ConfigKeys.IGNORED_COMMANDS, java.util.Collections.<String>emptyList());

        assertTrue(render().contains("ignored_commands: []"));
    }

    @Test
    @DisplayName("布尔与整数按类型输出，不加引号")
    void booleansAndIntegersAreTyped() {
        String yaml = render();

        assertTrue(yaml.contains("enable: true"));
        assertTrue(yaml.contains("debug: false"));
        assertTrue(yaml.contains("port: 8080"));
    }

    @Test
    @DisplayName("看起来像其它类型的字符串必须被加引号")
    void ambiguousStringsAreQuoted() {
        ConfigRegistry custom = new ConfigRegistry();
        ConfigKey<String> text = ConfigKey.builder("text", StringCodec.INSTANCE).defaultValue("").build();
        custom.register(text);
        Config customRuntime = new Config(custom);

        for (String value : Arrays.asList("true", "123", "null", "hello: world")) {
            customRuntime.set(text, value);
            String yaml = writer.render(ConfigWriteSnapshot.of(custom, customRuntime, ConfigDocument.empty()));

            Object reloaded = parse(yaml).get("text");
            assertEquals(value, reloaded, "字符串 " + value + " 回读后应仍是字符串，实际类型="
                    + (reloaded == null ? "null" : reloaded.getClass().getSimpleName()));
        }
    }

    // ------------------------------------------------------------------
    // 未知字段（§20 / §21 / §22）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未知核心字段：save 必须保留（save ≠ sync）")
    void unknownCoreFieldIsPreserved() {
        document = new ConfigDocument(parse("websocket_server:\n  enable: false\n  my_unknown: abc\n"));

        String yaml = render();

        assertTrue(yaml.contains("my_unknown: abc"), "未知核心字段必须被保留：\n" + yaml);
    }

    @Test
    @DisplayName("addons 下的未知字段：save 必须保留（未来 Addon 的基础能力）")
    void unknownAddonsFieldIsPreserved() {
        document = new ConfigDocument(parse("addons:\n  unloaded:\n    foo: bar\n    num: 123\n"));

        String yaml = render();

        assertTrue(yaml.contains("addons:"), "addons 区块必须被保留");
        assertTrue(yaml.contains("unloaded:"), "未加载 Addon 的区块必须被保留");
        assertTrue(yaml.contains("foo: bar"));
        assertTrue(yaml.contains("num: 123"));
    }

    @Test
    @DisplayName("已知字段与未知字段可以共存于同一区块")
    void knownAndUnknownCoexist() {
        document = new ConfigDocument(parse("websocket_server:\n  my_unknown: abc\n"));

        String yaml = render();

        assertTrue(yaml.contains("enable: true"), "已知字段照常输出");
        assertTrue(yaml.contains("my_unknown: abc"), "未知字段追加在区块末尾");
        assertTrue(yaml.indexOf("my_unknown:") > yaml.indexOf("port:"), "未知字段应在已知字段之后");
    }

    // ------------------------------------------------------------------
    // 确定性与只读
    // ------------------------------------------------------------------

    @Test
    @DisplayName("确定性：同一快照两次渲染结果完全一致")
    void outputIsDeterministic() {
        runtime.set(ConfigKeys.SERVER_NAME, "MyServer");

        String first = render();
        String second = render();

        assertEquals(first, second, "同一快照必须产生完全相同的输出");
    }

    @Test
    @DisplayName("Writer 不修改 Runtime，也不修改 Document")
    void writerIsReadOnly() {
        document = new ConfigDocument(parse("websocket_server:\n  my_unknown: abc\n"));
        runtime.set(ConfigKeys.SERVER_NAME, "MyServer");
        String runtimeBefore = runtime.describeEntries().toString();
        int documentLeavesBefore = document.leafPaths().size();

        render();

        assertEquals(runtimeBefore, runtime.describeEntries().toString(), "Writer 不得修改 Runtime");
        assertEquals(documentLeavesBefore, document.leafPaths().size(), "Writer 不得修改 Document");
        assertTrue(document.has("websocket_server.my_unknown"), "未知字段必须原样保留");
    }

    // ------------------------------------------------------------------
    // 原子写（§24 / §25 / §26）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("原子写：写入成功且生成备份，不残留临时文件")
    void writeIsAtomicAndBacksUp(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("config.yml");
        Files.write(target, "old: 1\n".getBytes(StandardCharsets.UTF_8));

        writer.write(ConfigWriteSnapshot.of(registry, runtime, document), target, null);

        assertTrue(Files.exists(target));
        assertTrue(new String(Files.readAllBytes(target), StandardCharsets.UTF_8).contains("server_name:"),
                "目标文件应为新内容");

        Path backup = tempDir.resolve("config.yml.bak");
        assertTrue(Files.exists(backup), "应生成备份");
        assertEquals("old: 1\n", new String(Files.readAllBytes(backup), StandardCharsets.UTF_8));

        try (java.util.stream.Stream<Path> files = Files.list(tempDir)) {
            assertFalse(
                    files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "不应残留临时文件");
        }
    }

    @Test
    @DisplayName("首次生成：目标不存在时不产生备份")
    void firstGenerationHasNoBackup(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("config.yml");

        writer.write(ConfigWriteSnapshot.of(registry, runtime, document), target, null);

        assertTrue(Files.exists(target));
        assertFalse(Files.exists(tempDir.resolve("config.yml.bak")), "目标原本不存在时不应产生备份");
    }
}
