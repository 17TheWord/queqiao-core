package io.github.theword.queqiao.core.config.sync;

import io.github.theword.queqiao.core.config.io.ConfigFileState;
import io.github.theword.queqiao.core.config.ConfigKeys;
import io.github.theword.queqiao.core.config.schema.ConfigRegistry;
import io.github.theword.queqiao.core.config.Config;
import io.github.theword.queqiao.core.config.schema.ConfigTree;
import io.github.theword.queqiao.core.config.io.ConfigDocument;
import io.github.theword.queqiao.core.config.io.ConfigLoadResult;
import io.github.theword.queqiao.core.config.io.ConfigLoader;
import io.github.theword.queqiao.core.config.io.ConfigWriteSnapshot;
import io.github.theword.queqiao.core.config.io.ConfigWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfigSynchronizer} 测试
 *
 * <p>覆盖 Phase 6 §43 的 Sync 矩阵，以及 §48（幂等）、§49（dry-run 只读）、
 * §50（save ≠ sync 边界）、§51（不改 Runtime）等硬要求。
 */
class ConfigSynchronizerTest {

    private final ConfigSynchronizer synchronizer = new ConfigSynchronizer();
    private final ConfigChecker checker = new ConfigChecker();
    private final ConfigWriter writer = new ConfigWriter();

    /**
     * 配置项总数（用于测试空文档补齐默认值）
     */
    private final Integer configSize = 25;

    private static ConfigRegistry newRegistry() {
        ConfigRegistry registry = new ConfigRegistry();
        ConfigKeys.registerAll(registry);
        return registry;
    }

    private static ConfigDocument doc(String yaml) {
        return new ConfigDocument(new Yaml().load(yaml));
    }

    private static Map<String, Object> parse(String yaml) {
        return new Yaml().load(yaml);
    }

    private static Map<String, Object> readYaml(Path path) throws IOException {
        return new Yaml().load(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // 三个基本操作
    // ------------------------------------------------------------------

    @Test
    @DisplayName("缺失字段 → ADD（使用 Schema 默认值）")
    void addMissingFields() {
        ConfigTree tree = newRegistry().snapshot();
        ConfigDocument document = doc("server_name: \"Mine\"\n");

        ConfigSyncPlan plan = synchronizer.plan(tree, document);

        assertTrue(plan.getAdditions().size() >= 20, "应补齐大量缺失字段");
        assertTrue(
                plan.getAdditions().stream().anyMatch(c -> "websocket_server.port".equals(c.getPath())),
                plan.getAdditions().toString());
        assertEquals(8080, plan.getAdditions().stream()
                .filter(c -> "websocket_server.port".equals(c.getPath()))
                .findFirst()
                .get()
                .getValue());
    }

    @Test
    @DisplayName("核心未知字段 → REMOVE（整个子树，不留空壳）")
    void removeUnknownCore() {
        ConfigTree tree = newRegistry().snapshot();
        ConfigDocument document = doc("foo:\n  bar:\n    value: 1\n");

        ConfigSyncPlan plan = synchronizer.plan(tree, document);

        List<ConfigChange> removals = plan.getRemovals();
        assertEquals(1, removals.size(), "整个子树应作为一条：" + removals);
        assertEquals("foo", removals.get(0).getPath());

        ConfigDocument next = synchronizer.apply(plan, document);
        assertFalse(next.has("foo"), "未知核心子树必须被删除");
        assertFalse(next.has("foo.bar"), "不应残留 foo: {} 空壳");
    }

    @Test
    @DisplayName("addons 未知字段 → KEEP（必须保留）")
    void preserveUnknownAddon() {
        ConfigTree tree = newRegistry().snapshot();
        ConfigDocument document = doc("addons:\n  unloaded:\n    foo: bar\n");

        ConfigSyncPlan plan = synchronizer.plan(tree, document);

        assertTrue(plan.getRemovals().isEmpty(), "addons 下的内容不得删除：" + plan.getRemovals());
        assertTrue(
                plan.getPreserved().stream().anyMatch(c -> "addons.unloaded.foo".equals(c.getPath())),
                plan.getPreserved().toString());

        ConfigDocument next = synchronizer.apply(plan, document);
        assertEquals("bar", next.get("addons.unloaded.foo"), "addons 内容必须存活");
    }

    @Test
    @DisplayName("类型/取值非法 → KEEP + 报告（不自动修复）")
    void preserveInvalidFields() {
        ConfigTree tree = newRegistry().snapshot();
        ConfigDocument document = doc("websocket_server:\n  port: 99999\n");

        ConfigSyncPlan plan = synchronizer.plan(tree, document);

        assertTrue(plan.getRemovals().isEmpty(), "非法值不得被删除");
        assertFalse(
                plan.getAdditions().stream().anyMatch(c -> "websocket_server.port".equals(c.getPath())),
                "非法值不得被覆盖为默认值");

        ConfigDocument next = synchronizer.apply(plan, document);
        assertEquals(99999, next.get("websocket_server.port"), "非法值必须原样保留");

        // Checker 仍应报告它
        ConfigCheckResult result = checker.check(tree, next);
        assertEquals(1, result.getIssues(ConfigIssueType.INVALID_VALUE).size());
    }

    @Test
    @DisplayName("非法区块结构 → KEEP，不能用缺失子项默认值覆盖")
    void preservesInvalidSectionInsteadOfAddingChildren() {
        ConfigTree tree = newRegistry().snapshot();
        ConfigDocument document = doc("websocket_server: broken\n");

        ConfigSyncPlan plan = synchronizer.plan(tree, document);

        assertFalse(plan.getAdditions().stream()
                .anyMatch(change -> change.getPath().startsWith("websocket_server.")));
        assertTrue(plan.getPreserved().stream()
                .anyMatch(change -> "websocket_server".equals(change.getPath())));
        ConfigDocument next = synchronizer.apply(plan, document);
        assertEquals("broken", next.get("websocket_server"));
    }

    @Test
    @DisplayName("显式写了默认值 → 既不 ADD 也不 REMOVE（NO CHANGE）")
    void explicitDefaultIsNoChange() {
        ConfigRegistry registry = newRegistry();
        ConfigTree tree = registry.snapshot();
        ConfigDocument document = doc("websocket_server:\n  port: 8080\n");

        ConfigSyncPlan plan = synchronizer.plan(tree, document);

        assertFalse(
                plan.getRemovals().stream().anyMatch(c -> "websocket_server.port".equals(c.getPath())),
                "不得因为值等于默认值就删除");
        assertFalse(
                plan.getAdditions().stream().anyMatch(c -> "websocket_server.port".equals(c.getPath())),
                "字段已存在，不得再 ADD");
    }

    @Test
    @DisplayName("未知核心 + addons 混合：核心删除、扩展保留")
    void mixedUnknownCoreAndAddon() {
        ConfigTree tree = newRegistry().snapshot();
        ConfigDocument document = doc("foo: 1\n\naddons:\n  custom:\n    foo: 2\n");

        ConfigSyncPlan plan = synchronizer.plan(tree, document);

        assertEquals(1, plan.getRemovals().size());
        assertEquals("foo", plan.getRemovals().get(0).getPath());
        assertTrue(plan.getPreserved().stream().anyMatch(c -> "addons.custom.foo".equals(c.getPath())));

        ConfigDocument next = synchronizer.apply(plan, document);
        assertFalse(next.has("foo"));
        assertEquals(2, next.get("addons.custom.foo"));
    }

    // ------------------------------------------------------------------
    // 边界场景
    // ------------------------------------------------------------------

    @Test
    @DisplayName("空文档 → 补齐全部默认值，且不产生未知扩展")
    void emptyDocumentAddsAll() {
        ConfigRegistry registry = newRegistry();
        ConfigTree tree = registry.snapshot();

        ConfigSyncResult result = synchronizer.synchronize(tree, ConfigDocument.empty());

        assertEquals(configSize, result.getPlan().getAdditions().size(), "应补齐全部 " + configSize + " 项");
        assertTrue(result.getPlan().getPreserved().isEmpty());
        assertEquals(configSize, result.getDocument().leafPaths().size());
    }

    @Test
    @DisplayName("只有 addons 的文档 → 补齐核心默认值并保留 addons")
    void addonsOnlyDocument() {
        ConfigTree tree = newRegistry().snapshot();
        ConfigDocument document = doc("addons:\n  custom:\n    foo: bar\n");

        ConfigSyncResult result = synchronizer.synchronize(tree, document);

        assertEquals(configSize, result.getPlan().getAdditions().size());
        assertEquals("bar", result.getDocument().get("addons.custom.foo"), "addons 必须保留");
        assertTrue(result.getPlan().getRemovals().isEmpty());
    }

    // ------------------------------------------------------------------
    // 幂等 / 只读 / dry-run（§48 / §49 / §51）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同步幂等：sync(sync(d)) == sync(d)")
    void synchronizeIsIdempotent() {
        ConfigTree tree = newRegistry().snapshot();
        ConfigDocument original = doc("foo: 1\n\naddons:\n  custom:\n    foo: 2\n\nwebsocket_server:\n  port: 99999\n");

        ConfigDocument once = synchronizer.synchronize(tree, original).getDocument();
        ConfigDocument twice = synchronizer.synchronize(tree, once).getDocument();

        assertEquals(once.getRoot(), twice.getRoot(), "第二次同步不应再产生变化");
        assertEquals(
                once.leafPaths().size(),
                twice.leafPaths().size(),
                "幂等性失败：leaf 数量变化");
    }

    @Test
    @DisplayName("plan 是纯计算：不修改输入文档")
    void planIsPure() {
        ConfigTree tree = newRegistry().snapshot();
        ConfigDocument document = doc("foo: 1\n");
        Map<String, Object> before = document.getRoot();

        synchronizer.plan(tree, document);

        assertEquals(before, document.getRoot(), "plan 不得修改输入文档");
    }

    @Test
    @DisplayName("apply 不修改原文档，返回新文档")
    void applyDoesNotMutateInput() {
        ConfigTree tree = newRegistry().snapshot();
        ConfigDocument document = doc("foo: 1\n");
        Map<String, Object> before = document.getRoot();

        ConfigDocument next = synchronizer.apply(synchronizer.plan(tree, document), document);

        assertEquals(before, document.getRoot(), "原文档必须保持不变");
        assertFalse(next.has("foo"), "新文档应已删除未知字段");
    }

    @Test
    @DisplayName("dry-run 与真实同步使用同一份计划")
    void dryRunSharesSamePlan() {
        ConfigTree tree = newRegistry().snapshot();
        ConfigDocument document = doc("foo: 1\n");

        ConfigSyncPlan plan = synchronizer.plan(tree, document);
        ConfigSyncResult real = synchronizer.synchronize(tree, document);

        assertEquals(plan.getChanges().size(), real.getPlan().getChanges().size());
        for (int i = 0; i < plan.getChanges().size(); i++) {
            assertEquals(plan.getChanges().get(i).getPath(), real.getPlan().getChanges().get(i).getPath());
            assertEquals(plan.getChanges().get(i).getKind(), real.getPlan().getChanges().get(i).getKind());
        }
    }

    @Test
    @DisplayName("同步不修改 Runtime（值来源也不变）")
    void synchronizeDoesNotTouchRuntime() {
        ConfigRegistry registry = newRegistry();
        Config runtime = new Config(registry);
        runtime.set(ConfigKeys.SERVER_NAME, "MyServer");
        String before = runtime.describeEntries().toString();

        synchronizer.synchronize(registry.snapshot(), doc("foo: 1\n"));

        assertEquals(before, runtime.describeEntries().toString(), "同步不得改动运行时状态");
        assertTrue(runtime.contains(ConfigKeys.SERVER_NAME), "来源也不应变");
    }

    @Test
    @DisplayName("确定性：同一输入两次 plan 结果一致")
    void planIsDeterministic() {
        ConfigTree tree = newRegistry().snapshot();
        ConfigDocument document = doc("foo: 1\n\naddons:\n  c:\n    a: 1\n");

        List<ConfigChange> first = synchronizer.plan(tree, document).getChanges();
        List<ConfigChange> second = synchronizer.plan(tree, document).getChanges();

        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).getPath(), second.get(i).getPath());
            assertEquals(first.get(i).getKind(), second.get(i).getKind());
        }
    }

    // ------------------------------------------------------------------
    // save ≠ sync 边界（§50）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("save ≠ sync：save 保留未知字段，sync 才删除核心未知（addons 始终保留）")
    void saveAndSyncHaveDistinctSemantics(@TempDir Path tempDir) throws IOException {
        ConfigRegistry registry = newRegistry();
        Config runtime = new Config(registry);
        ConfigLoader loader = new ConfigLoader(registry, runtime);
        Path target = tempDir.resolve("config.yml");

        ConfigLoadResult loaded = loader.load(
                ConfigFileState.VALID,
                parse("foo: 1\n\naddons:\n  custom:\n    keep_me: true\n"));

        // 1) save：未知字段全部保留
        writer.write(ConfigWriteSnapshot.of(registry, runtime, loaded.getDocument()), target, null);
        Map<String, Object> afterSave = readYaml(target);
        assertNotNull(afterSave.get("foo"), "save 必须保留未知核心字段");
        assertNotNull(afterSave.get("addons"), "save 必须保留 addons");

        // 2) sync 后再 save：核心未知消失，addons 仍在
        ConfigSyncResult synced = synchronizer.synchronize(registry.snapshot(), loaded.getDocument());
        writer.write(ConfigWriteSnapshot.of(registry, runtime, synced.getDocument()), target, null);
        Map<String, Object> afterSync = readYaml(target);

        assertFalse(afterSync.containsKey("foo"), "sync 之后核心未知字段应被删除");
        assertNotNull(afterSync.get("addons"), "addons 未知字段必须始终保留");
    }
}
