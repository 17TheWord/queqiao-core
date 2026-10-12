package io.github.theword.queqiao.core.command;

import io.github.theword.queqiao.core.command.builtin.HelpCommand;
import io.github.theword.queqiao.core.config.Config;
import io.github.theword.queqiao.core.constant.CommandConstant;
import io.github.theword.queqiao.core.runtime.QueQiaoRuntime;
import io.github.theword.queqiao.core.runtime.ReloadResult;
import io.github.theword.queqiao.core.runtime.RuntimeState;
import io.github.theword.queqiao.core.support.DisabledConfigFixture;
import io.github.theword.queqiao.core.support.FakeCommandExecutionContext;
import io.github.theword.queqiao.core.support.PlatformStubs;
import io.github.theword.queqiao.core.utils.WebsocketManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提前构建的命令树（{@code QueQiaoRuntime.createDeferredCommandTree}）契约测试
 *
 * <p>覆盖方案 §四 要求的七点：
 * <ol>
 *     <li>Runtime 尚不存在时可完整构造与遍历；</li>
 *     <li>构造与结构读取不触发依赖供应商；</li>
 *     <li>Runtime 启动后 {@code info} / {@code list} / {@code reload} / {@code reconnect} 正常；</li>
 *     <li>Runtime 未运行时安全失败（不抛异常）；</li>
 *     <li>更换 Runtime 后使用新 Runtime 的依赖；</li>
 *     <li>重复执行不重建节点；</li>
 *     <li>树已冻结，晚到的注册被拒绝。</li>
 * </ol>
 *
 * <p><b>关于路径解析</b>：{@code CommandNode.execute} 只执行<b>该节点自身</b>，
 * 逐层匹配是平台命令 API（Fabric 的 Brigadier 递归字面量注册）的职责 ——
 * 这正是本轮"命令树提前构建"要换来的东西。因此本测试先用 {@link #execute} 按路径解析到目标
 * 节点、再以空参数执行，与 Fabric 侧 {@code CommandExecutor} 的行为一致。
 *
 * <p>用例 3、5 走真实启动路径，因此与 {@code CommandLayerTest} 一样写工作目录下的配置文件
 * （由 {@link DisabledConfigFixture} 备份与还原），并标记 {@link Isolated} 避免并发写同一文件。
 */
@Isolated
class DeferredCommandTreeTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeferredCommandTreeTest.class);

    private static final Object NATIVE_SOURCE = new Object();

    // ------------------------------------------------------------------
    // 1. 无 Runtime 也能构造与遍历
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Runtime 不存在时可构造并完整遍历命令树")
    void treeIsCompleteWithoutRuntime() {
        RootCommand<Object> tree = QueQiaoRuntime.createDeferredCommandTree(LOGGER, () -> null);

        assertEquals("queqiao", tree.getName(), "根节点名应为命令前缀");
        assertEquals("queqiao", tree.getFullPermissionNode());
        assertTrue(tree.isRoot());
        assertTrue(tree.isFrozen(), "工厂返回的树应已冻结");

        assertEquals(Arrays.asList("help", "reload", "server", "client"), childNames(tree));
        assertEquals(Collections.singletonList("info"), childNames(find(tree, "server")));
        assertEquals(Arrays.asList("list", "reconnect"), childNames(find(tree, "client")));
        assertEquals(Collections.singletonList("all"),
                childNames(find(find(tree, "client"), "reconnect")));

        assertEquals("queqiao.client.reconnect.all",
                find(find(find(tree, "client"), "reconnect"), "all").getFullPermissionNode());
        assertEquals("/queqiao client reconnect all",
                find(find(find(tree, "client"), "reconnect"), "all").getFullPath());
    }

    // ------------------------------------------------------------------
    // 2. 构造与结构读取不解析依赖
    // ------------------------------------------------------------------

    @Test
    @DisplayName("构造命令树与读取结构不调用任何依赖供应商")
    void constructionDoesNotResolveDependencies() {
        Supplier<Config> configSupplier =
                () -> { throw new AssertionError("构造期不得读取 Config"); };
        Supplier<WebsocketManager> websocketSupplier =
                () -> { throw new AssertionError("构造期不得读取 WebsocketManager"); };
        Supplier<ReloadResult> reloadSupplier =
                () -> { throw new AssertionError("构造期不得读取 reload"); };

        RootCommand<Object> tree =
                new RootCommand<>(LOGGER, configSupplier, websocketSupplier, reloadSupplier);

        List<CommandNode<Object>> nodes = flatten(tree);
        assertEquals(9, nodes.size(),
                "应为 根 + help/reload/server/info/client/list/reconnect/all");
        for (CommandNode<Object> node : nodes) {
            assertTrue(node.getName() != null && !node.getName().isEmpty(), "节点名应可读取");
            assertTrue(node.getDescription() != null && !node.getDescription().isEmpty());
            assertTrue(node.getUsage().startsWith("/queqiao"), "用法应可读取：" + node.getUsage());
            assertTrue(node.getPermissionNode().startsWith("queqiao"));
        }
    }

    @Test
    @DisplayName("工厂不在构造期解析 Runtime（供应商抛异常也不影响建树）")
    void factoryDoesNotResolveRuntime() {
        RootCommand<Object> tree = QueQiaoRuntime.createDeferredCommandTree(
                LOGGER,
                () -> { throw new AssertionError("构造期不得读取 Runtime"); });

        assertEquals(4, tree.getChildren().size());
        assertTrue(tree.isFrozen());
    }

    // ------------------------------------------------------------------
    // 3. Runtime 启动后命令正常
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Runtime 启动后 info / list / reconnect / reload / help 均正常执行")
    void commandsWorkAfterRuntimeStarts() throws Exception {
        try (DisabledConfigFixture ignored = new DisabledConfigFixture()) {
            QueQiaoRuntime runtime = QueQiaoRuntime.start(PlatformStubs.noopPlatformContext());
            try {
                assertEquals(RuntimeState.RUNNING, runtime.getState());
                RootCommand<Object> tree =
                        QueQiaoRuntime.createDeferredCommandTree(LOGGER, () -> runtime);

                FakeCommandExecutionContext<Object> info =
                        new FakeCommandExecutionContext<>(NATIVE_SOURCE);
                assertEquals(CommandConstant.SUCCESS_SIGNAL,
                        execute(tree, info, Arrays.asList("server", "info")));
                assertTrue(info.hasReplyContaining("Websocket Server 配置项未启用"),
                        "info 应读到真实 Config，实际=" + info.getReplies());

                FakeCommandExecutionContext<Object> list =
                        new FakeCommandExecutionContext<>(NATIVE_SOURCE);
                execute(tree, list, Arrays.asList("client", "list"));
                assertTrue(list.hasReplyContaining("Websocket Client 配置项未启用"),
                        "list 应读到真实 Config，实际=" + list.getReplies());

                FakeCommandExecutionContext<Object> reconnect =
                        new FakeCommandExecutionContext<>(NATIVE_SOURCE);
                execute(tree, reconnect, Arrays.asList("client", "reconnect"));
                assertTrue(reconnect.hasReplyContaining(CommandConstant.RECONNECTED),
                        "reconnect 应读到真实 WebsocketManager，实际=" + reconnect.getReplies());

                FakeCommandExecutionContext<Object> reconnectAll =
                        new FakeCommandExecutionContext<>(NATIVE_SOURCE);
                execute(tree, reconnectAll, Arrays.asList("client", "reconnect", "all"));
                assertTrue(reconnectAll.hasReplyContaining(CommandConstant.RECONNECT_ALL_CLIENT),
                        "reconnect all 应生效，实际=" + reconnectAll.getReplies());

                FakeCommandExecutionContext<Object> reload =
                        new FakeCommandExecutionContext<>(NATIVE_SOURCE);
                execute(tree, reload, Collections.singletonList("reload"));
                assertTrue(reload.hasReplyContaining(CommandConstant.RELOAD_CONFIG),
                        "reload 应完成，实际=" + reload.getReplies());

                FakeCommandExecutionContext<Object> help =
                        new FakeCommandExecutionContext<>(NATIVE_SOURCE);
                execute(tree, help, Collections.singletonList("help"));
                assertTrue(help.hasReplyContaining("/queqiao client reconnect all"),
                        "help 应打印整棵树，实际=" + help.getReplies());
            } finally {
                runtime.shutdown();
            }
        }
    }

    // ------------------------------------------------------------------
    // 4. 未就绪时安全失败
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Runtime 为 null 时依赖运行状态的命令回执未就绪且不抛异常")
    void commandsFailSafelyWithoutRuntime() {
        RootCommand<Object> tree = QueQiaoRuntime.createDeferredCommandTree(LOGGER, () -> null);

        List<List<String>> paths = Arrays.asList(
                Arrays.asList("server", "info"),
                Arrays.asList("client", "list"),
                Arrays.asList("client", "reconnect"),
                Arrays.asList("client", "reconnect", "all"),
                Collections.singletonList("reload"));

        for (List<String> path : paths) {
            FakeCommandExecutionContext<Object> context =
                    new FakeCommandExecutionContext<>(NATIVE_SOURCE);
            int signal = execute(tree, context, path);

            assertEquals(CommandConstant.SUCCESS_SIGNAL, signal, path + " 不应抛异常");
            assertTrue(context.hasReplyContaining(CommandConstant.RUNTIME_NOT_READY),
                    path + " 应回执未就绪，实际=" + context.getReplies());
            assertTrue(context.getReplies().stream().noneMatch(r -> r.contains("{}")),
                    path + " 不应出现未替换占位符，实际=" + context.getReplies());
        }
    }

    @Test
    @DisplayName("Runtime 已创建但未启动（WebsocketManager 尚不存在）同样回执未就绪")
    void createdButNotStartedRuntimeIsNotReady() {
        QueQiaoRuntime notStarted = QueQiaoRuntime.create(PlatformStubs.noopPlatformContext());
        RootCommand<Object> tree =
                QueQiaoRuntime.createDeferredCommandTree(LOGGER, () -> notStarted);

        FakeCommandExecutionContext<Object> context =
                new FakeCommandExecutionContext<>(NATIVE_SOURCE);
        execute(tree, context, Arrays.asList("server", "info"));

        assertTrue(context.hasReplyContaining(CommandConstant.RUNTIME_NOT_READY),
                "未启动的 Runtime 不应被当作就绪，实际=" + context.getReplies());
    }

    @Test
    @DisplayName("不依赖运行状态的命令（根命令 / help）在未就绪时仍可用")
    void runtimeIndependentCommandsStillWork() {
        RootCommand<Object> tree = QueQiaoRuntime.createDeferredCommandTree(LOGGER, () -> null);

        FakeCommandExecutionContext<Object> root = new FakeCommandExecutionContext<>(NATIVE_SOURCE);
        execute(tree, root, Collections.emptyList());
        assertTrue(root.hasReplyContaining("请使用帮助命令查看可用子命令"),
                "根命令不需要 Runtime，实际=" + root.getReplies());

        FakeCommandExecutionContext<Object> help = new FakeCommandExecutionContext<>(NATIVE_SOURCE);
        execute(tree, help, Collections.singletonList("help"));
        assertTrue(help.hasReplyContaining("/queqiao help - 获取命令帮助"),
                "help 不需要 Runtime，实际=" + help.getReplies());
    }

    // ------------------------------------------------------------------
    // 5. 更换 Runtime
    // ------------------------------------------------------------------

    @Test
    @DisplayName("更换 Runtime 后使用新 Runtime 的依赖，无需重建命令树")
    void swappedRuntimeIsUsedOnNextExecution() throws Exception {
        try (DisabledConfigFixture ignored = new DisabledConfigFixture()) {
            QueQiaoRuntime running = QueQiaoRuntime.start(PlatformStubs.noopPlatformContext());
            QueQiaoRuntime notStarted = QueQiaoRuntime.create(PlatformStubs.noopPlatformContext());
            AtomicReference<QueQiaoRuntime> current = new AtomicReference<>(running);
            RootCommand<Object> tree =
                    QueQiaoRuntime.createDeferredCommandTree(LOGGER, current::get);

            try {
                FakeCommandExecutionContext<Object> before =
                        new FakeCommandExecutionContext<>(NATIVE_SOURCE);
                execute(tree, before, Arrays.asList("server", "info"));
                assertTrue(before.hasReplyContaining("Websocket Server 配置项未启用"),
                        "首次应使用运行中的 Runtime，实际=" + before.getReplies());

                current.set(notStarted);
                FakeCommandExecutionContext<Object> swapped =
                        new FakeCommandExecutionContext<>(NATIVE_SOURCE);
                execute(tree, swapped, Arrays.asList("server", "info"));
                assertTrue(swapped.hasReplyContaining(CommandConstant.RUNTIME_NOT_READY),
                        "替换后应改用新 Runtime 的依赖，实际=" + swapped.getReplies());

                current.set(running);
                FakeCommandExecutionContext<Object> restored =
                        new FakeCommandExecutionContext<>(NATIVE_SOURCE);
                execute(tree, restored, Arrays.asList("server", "info"));
                assertTrue(restored.hasReplyContaining("Websocket Server 配置项未启用"),
                        "切回后应重新使用原 Runtime，实际=" + restored.getReplies());
            } finally {
                running.shutdown();
            }
        }
    }

    // ------------------------------------------------------------------
    // 6. 重复执行不重建节点
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同一棵树重复执行不会重建节点")
    void repeatedExecutionKeepsSameNodes() {
        RootCommand<Object> tree = QueQiaoRuntime.createDeferredCommandTree(LOGGER, () -> null);

        List<CommandNode<Object>> before = new ArrayList<>(tree.getChildren());
        CommandNode<Object> clientBefore = find(tree, "client");
        CommandNode<Object> reconnectBefore = find(clientBefore, "reconnect");

        for (int i = 0; i < 3; i++) {
            FakeCommandExecutionContext<Object> context =
                    new FakeCommandExecutionContext<>(NATIVE_SOURCE);
            execute(tree, context, Collections.singletonList("help"));
        }

        List<CommandNode<Object>> after = new ArrayList<>(tree.getChildren());
        assertEquals(before.size(), after.size(), "执行不得改变子命令数量");
        for (int i = 0; i < before.size(); i++) {
            assertSame(before.get(i), after.get(i), "第 " + i + " 个子节点应为同一实例");
        }
        assertSame(clientBefore, find(tree, "client"));
        assertSame(reconnectBefore, find(clientBefore, "reconnect"));
    }

    // ------------------------------------------------------------------
    // 7. 冻结与重复注册
    // ------------------------------------------------------------------

    @Test
    @DisplayName("工厂返回的树已冻结，晚到的 addChild 被拒绝（含深层节点）")
    void frozenTreeRejectsLateRegistration() {
        RootCommand<Object> tree = QueQiaoRuntime.createDeferredCommandTree(LOGGER, () -> null);

        assertTrue(tree.isFrozen(), "根节点应已冻结");
        assertTrue(find(tree, "client").isFrozen(), "freeze 应递归到深层节点");

        assertThrows(IllegalStateException.class, () -> tree.addChild(new HelpCommand<>(LOGGER)));
        assertThrows(IllegalStateException.class,
                () -> find(tree, "client").addChild(new HelpCommand<>(LOGGER)));
    }

    @Test
    @DisplayName("未冻结时同一父节点下的重复名称仍被拒绝")
    void duplicateChildNameIsStillRejected() {
        RootCommand<Object> fresh = new RootCommand<>(LOGGER, () -> null, () -> null, () -> null);

        assertThrows(IllegalArgumentException.class,
                () -> fresh.addChild(new HelpCommand<>(LOGGER)),
                "根节点构造时已注册 help，重复注册必须被拒绝");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 按路径解析到目标节点后执行 —— 与平台命令 API（Brigadier 递归注册）的行为一致：
     * 路径由平台侧解析，Core 只执行被命中的那个节点，且没有剩余参数。
     *
     * @return {@code CommandNode.execute} 返回的执行信号
     */
    private static int execute(
            CommandNode<Object> root,
            FakeCommandExecutionContext<Object> context,
            List<String> path) {
        CommandNode<Object> node = root;
        for (String segment : path) {
            node = find(node, segment);
        }
        return node.execute(context, Collections.emptyList());
    }

    private static List<String> childNames(CommandNode<Object> node) {
        List<String> names = new ArrayList<>();
        for (CommandNode<Object> child : node.getChildren()) {
            names.add(child.getName());
        }
        return names;
    }

    private static CommandNode<Object> find(CommandNode<Object> node, String name) {
        for (CommandNode<Object> child : node.getChildren()) {
            if (child.getName().equals(name)) {
                return child;
            }
        }
        throw new AssertionError("未找到子命令 " + name + "（父节点 " + node.getFullPath() + "）");
    }

    private static List<CommandNode<Object>> flatten(CommandNode<Object> root) {
        List<CommandNode<Object>> nodes = new ArrayList<>();
        collect(root, nodes);
        return nodes;
    }

    private static void collect(CommandNode<Object> node, List<CommandNode<Object>> sink) {
        sink.add(node);
        for (CommandNode<Object> child : node.getChildren()) {
            collect(child, sink);
        }
    }
}
