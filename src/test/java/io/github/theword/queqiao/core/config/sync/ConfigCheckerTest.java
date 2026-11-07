package io.github.theword.queqiao.core.config.sync;

import io.github.theword.queqiao.core.config.codec.BooleanCodec;
import io.github.theword.queqiao.core.config.schema.ConfigKey;
import io.github.theword.queqiao.core.config.ConfigKeys;
import io.github.theword.queqiao.core.config.schema.ConfigRegistry;
import io.github.theword.queqiao.core.config.schema.ConfigSectionNode;
import io.github.theword.queqiao.core.config.schema.ConfigTree;
import io.github.theword.queqiao.core.config.codec.IntegerCodec;
import io.github.theword.queqiao.core.config.codec.StringCodec;
import io.github.theword.queqiao.core.config.io.ConfigDocument;
import io.github.theword.queqiao.core.config.validation.ConfigValidators;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfigChecker} 测试
 *
 * <p>覆盖 Phase 6 §43 的 Check 矩阵与 §12 的稳定顺序要求。
 */
class ConfigCheckerTest {

    private static final ConfigKey<Boolean> ENABLE =
            ConfigKey.builder("websocket_server.enable", BooleanCodec.INSTANCE)
                    .defaultValue(true)
                    .build();

    private static final ConfigKey<Integer> PORT = ConfigKey.builder("websocket_server.port", IntegerCodec.INSTANCE)
            .defaultValue(8080)
            .validator(ConfigValidators.range(1, 65535))
            .build();

    private static final ConfigKey<String> HOST = ConfigKey.builder("websocket_server.host", StringCodec.INSTANCE)
            .defaultValue("127.0.0.1")
            .build();

    private final ConfigChecker checker = new ConfigChecker();

    private static ConfigTree tree() {
        ConfigRegistry registry = new ConfigRegistry();
        registry.register(ENABLE);
        registry.register(HOST);
        registry.register(PORT);
        registry.register(ConfigSectionNode.of("websocket_server").comment("WebSocket Server 配置"));
        return registry.snapshot();
    }

    @SuppressWarnings("unchecked")
    private static ConfigDocument doc(String yaml) {
        return new ConfigDocument((Map<String, Object>) new Yaml().load(yaml));
    }

    private ConfigCheckResult check(String yaml) {
        return checker.check(tree(), doc(yaml));
    }

    // ------------------------------------------------------------------
    // 基本
    // ------------------------------------------------------------------

    @Test
    @DisplayName("完整且合法的文档 → 无任何问题")
    void validDocument() {
        ConfigCheckResult result =
                check("websocket_server:\n  enable: false\n  host: \"0.0.0.0\"\n  port: 19132\n");

        assertFalse(result.hasIssues(), result.getIssues().toString());
    }

    @Test
    @DisplayName("显式写了与默认值相同的值 → 仍然 VALID（不是 MISSING）")
    void explicitDefaultIsValid() {
        ConfigCheckResult result =
                check("websocket_server:\n  enable: true\n  host: \"127.0.0.1\"\n  port: 8080\n");

        assertFalse(result.hasIssues(), result.getIssues().toString());
    }

    @Test
    @DisplayName("空文档 → 全部配置项都报 MISSING，且不是致命错误")
    void emptyDocumentReportsAllMissing() {
        ConfigCheckResult result = check("{}");

        assertEquals(3, result.getIssues(ConfigIssueType.MISSING).size());
        assertFalse(result.hasErrors(), "MISSING 是警告级，不是致命错误");
        assertTrue(result.hasIssues(), "缺失仍然算问题");
    }

    // ------------------------------------------------------------------
    // 缺失
    // ------------------------------------------------------------------

    @Test
    @DisplayName("缺失标量：报告路径与默认值")
    void missingScalar() {
        ConfigCheckResult result = check("websocket_server:\n  enable: true\n");

        List<ConfigIssue> missing = result.getIssues(ConfigIssueType.MISSING);
        assertEquals(2, missing.size());
        assertEquals("websocket_server.host", missing.get(0).getPath());
        assertEquals("127.0.0.1", missing.get(0).getDefaultValue());
        assertEquals("websocket_server.port", missing.get(1).getPath());
        assertEquals(8080, missing.get(1).getDefaultValue());
    }

    @Test
    @DisplayName("整个区块缺失 → 逐个报 Leaf MISSING，不额外报区块本身")
    void missingWholeSection() {
        ConfigCheckResult result = check("{}");

        List<ConfigIssue> missing = result.getIssues(ConfigIssueType.MISSING);
        assertEquals(3, missing.size());
        for (ConfigIssue issue : missing) {
            assertTrue(issue.getPath().startsWith("websocket_server."), "粒度应为 Leaf：" + issue.getPath());
        }
    }

    // ------------------------------------------------------------------
    // 未知
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未知顶层字段 → UNKNOWN_CORE")
    void unknownTopLevel() {
        ConfigCheckResult result = check("foo: bar\n");

        assertEquals(1, result.getIssues(ConfigIssueType.UNKNOWN_CORE).size());
        assertEquals("foo", result.getIssues(ConfigIssueType.UNKNOWN_CORE).get(0).getPath());
    }

    @Test
    @DisplayName("未知嵌套子树 → 只报子树根，不逐叶子刷屏")
    void unknownNestedSubtree() {
        ConfigCheckResult result = check("websocket_server:\n  foo:\n    bar: 1\n    baz: 2\n");

        List<ConfigIssue> unknown = result.getIssues(ConfigIssueType.UNKNOWN_CORE);
        assertEquals(1, unknown.size(), "整个子树应作为一条：" + unknown);
        assertEquals("websocket_server.foo", unknown.get(0).getPath());
    }

    @Test
    @DisplayName("addons 下的未知字段 → UNKNOWN_ADDON，且逐叶子报告")
    void unknownAddon() {
        ConfigCheckResult result = check("addons:\n  unloaded:\n    foo: bar\n    num: 123\n");

        List<ConfigIssue> addon = result.getIssues(ConfigIssueType.UNKNOWN_ADDON);
        assertEquals(2, addon.size());
        assertEquals("addons.unloaded.foo", addon.get(0).getPath());
        assertEquals("addons.unloaded.num", addon.get(1).getPath());
        assertTrue(result.getIssues(ConfigIssueType.UNKNOWN_CORE).isEmpty(), "不应混成核心未知");
    }

    @Test
    @DisplayName("addons2 不属于 addons 命名空间（按路径段判断）")
    void addonsPrefixIsNotAddonNamespace() {
        ConfigCheckResult result = check("addons2:\n  foo: bar\n");

        assertTrue(result.getIssues(ConfigIssueType.UNKNOWN_ADDON).isEmpty(), "addons2 不是 addons");
        assertEquals(1, result.getIssues(ConfigIssueType.UNKNOWN_CORE).size());
    }

    // ------------------------------------------------------------------
    // 结构 / 类型 / 取值
    // ------------------------------------------------------------------

    @Test
    @DisplayName("区块写成标量 → INVALID_STRUCTURE")
    void sectionAsScalar() {
        ConfigCheckResult result = check("websocket_server: \"not-a-map\"\n");

        assertEquals(1, result.getIssues(ConfigIssueType.INVALID_STRUCTURE).size());
        assertEquals(
                "websocket_server", result.getIssues(ConfigIssueType.INVALID_STRUCTURE).get(0).getPath());
        assertTrue(result.hasErrors());
    }

    @Test
    @DisplayName("addons 不是 Mapping → INVALID_STRUCTURE（不是 UNKNOWN_ADDON）")
    void addonsMustBeMapping() {
        ConfigCheckResult result = check("addons: hello\n");

        assertEquals(1, result.getIssues(ConfigIssueType.INVALID_STRUCTURE).size());
        assertEquals("addons", result.getIssues(ConfigIssueType.INVALID_STRUCTURE).get(0).getPath());
        assertTrue(result.getIssues(ConfigIssueType.UNKNOWN_ADDON).isEmpty());
    }

    @Test
    @DisplayName("类型错误 → INVALID_TYPE")
    void invalidType() {
        ConfigCheckResult result = check("websocket_server:\n  port: \"abc\"\n");

        assertEquals(1, result.getIssues(ConfigIssueType.INVALID_TYPE).size());
        assertEquals("websocket_server.port", result.getIssues(ConfigIssueType.INVALID_TYPE).get(0).getPath());
    }

    @Test
    @DisplayName("取值越界 → INVALID_VALUE，且消息含范围")
    void invalidValue() {
        ConfigCheckResult result = check("websocket_server:\n  port: 99999\n");

        List<ConfigIssue> invalid = result.getIssues(ConfigIssueType.INVALID_VALUE);
        assertEquals(1, invalid.size());
        assertTrue(invalid.get(0).getMessage().contains("65535"), invalid.get(0).getMessage());
    }

    @Test
    @DisplayName("null 值 → INVALID_TYPE（非 Optional 配置项不接受 null）")
    void nullValue() {
        ConfigCheckResult result = check("websocket_server:\n  host: null\n");

        assertEquals(1, result.getIssues(ConfigIssueType.INVALID_TYPE).size());
    }

    // ------------------------------------------------------------------
    // 顺序与只读
    // ------------------------------------------------------------------

    @Test
    @DisplayName("问题顺序稳定：结构 → 类型 → 取值 → 缺失 → 未知核心 → 未知扩展")
    void issueOrderIsStable() {
        ConfigCheckResult result = check(String.join("\n",
                "websocket_server:",
                "  port: 99999",       // INVALID_VALUE
                "  extra: 1",         // UNKNOWN_CORE
                "foo: bar",           // UNKNOWN_CORE
                "addons:",
                "  unloaded:",
                "    x: 1",           // UNKNOWN_ADDON
                ""));

        List<ConfigIssue> issues = result.getIssues();
        ConfigIssueType previous = null;
        for (ConfigIssue issue : issues) {
            if (previous != null) {
                assertTrue(
                        previous.ordinal() <= issue.getType().ordinal(),
                        "问题必须按类型枚举顺序排列：" + issues);
            }
            previous = issue.getType();
        }
        assertEquals(ConfigIssueType.INVALID_VALUE, issues.get(0).getType());
    }

    @Test
    @DisplayName("检查是只读的：不修改文档")
    void checkIsReadOnly() {
        ConfigDocument document = doc("websocket_server:\n  port: 99999\n  extra: 1\n");
        int leavesBefore = document.leafPaths().size();

        checker.check(tree(), document);

        assertEquals(leavesBefore, document.leafPaths().size());
        assertTrue(document.has("websocket_server.extra"));
    }

    @Test
    @DisplayName("使用真实核心 Schema 也不应有问题（空文档只报 MISSING）")
    void worksWithRealSchema() {
        ConfigRegistry registry = new ConfigRegistry();
        ConfigKeys.registerAll(registry);

        ConfigCheckResult result = checker.check(registry.snapshot(), ConfigDocument.empty());

        assertEquals(25, result.getIssues(ConfigIssueType.MISSING).size());
        assertFalse(result.hasErrors());
    }
}
