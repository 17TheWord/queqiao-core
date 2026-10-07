package io.github.theword.queqiao.core.command;

import io.github.theword.queqiao.core.constant.CommandConstant;
import io.github.theword.queqiao.core.support.FakeCommandExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommandNode} 与 {@link CommandExecutionContext} 的契约测试
 *
 * <p>覆盖方案要求的契约：
 * <ol>
 *     <li>Native Source 同一性 —— 命令内取到的就是传入的同一个对象；</li>
 *     <li>权限通过 / 拒绝；</li>
 *     <li>reply 落到执行上下文；</li>
 *     <li>重复子命令被拒绝；</li>
 *     <li>parent 归属唯一；</li>
 *     <li>环检测；</li>
 *     <li>{@code nativeSource} 严格非 null。</li>
 * </ol>
 *
 * <p>本类不依赖 Runtime / Config / WebSocket —— 命令树与执行上下文可以独立测试，
 * 这正是"命令层不认识平台上下文"带来的收益。
 */
class CommandNodeContractTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandNodeContractTest.class);

    /**
     * 探针命令：记录 native source 与执行次数
     */
    private static final class ProbeNode<NCS> extends CommandNode<NCS> {

        private final String name;
        private final List<Object> seenNativeSources = new ArrayList<>();
        private final List<String> executions = new ArrayList<>();

        private ProbeNode(String name) {
            super(LOGGER);
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "探针命令";
        }

        @Override
        protected void onExecute(CommandExecutionContext<NCS> context, List<String> args) {
            seenNativeSources.add(context.nativeSource);
            executions.add(String.join(",", args));
        }

        private List<Object> getSeenNativeSources() {
            return seenNativeSources;
        }

        private List<String> getExecutions() {
            return executions;
        }
    }

    // ------------------------------------------------------------------
    // 1. Native Source 同一性
    // ------------------------------------------------------------------

    @Test
    @DisplayName("命令内取到的 nativeSource 与传入的是同一个对象（assertSame）")
    void nativeSourceIsIdentical() {
        Object nativeSource = new Object();
        ProbeNode<Object> node = new ProbeNode<>("probe");
        FakeCommandExecutionContext<Object> context =
                new FakeCommandExecutionContext<>(nativeSource);

        node.execute(context, Collections.<String>emptyList());

        assertEquals(1, node.getSeenNativeSources().size(), "命令体应执行一次");
        assertSame(nativeSource, node.getSeenNativeSources().get(0), "必须是同一个对象");
        assertSame(nativeSource, context.nativeSource, "上下文持有的也是同一个对象");
    }

    @Test
    @DisplayName("nativeSource 为 null 时构造失败（严格非 null）")
    void nullNativeSourceIsRejected() {
        assertThrows(NullPointerException.class, () -> new FakeCommandExecutionContext<>(null));
    }

    // ------------------------------------------------------------------
    // 2. 权限
    // ------------------------------------------------------------------

    @Test
    @DisplayName("有权限：命令体执行，回执含分隔线")
    void permissionGrantedExecutesCommand() {
        ProbeNode<Object> node = new ProbeNode<>("probe");
        FakeCommandExecutionContext<Object> context =
                new FakeCommandExecutionContext<>(new Object(), true);

        int signal = node.execute(context, Arrays.asList("a", "b"));

        assertEquals(CommandConstant.SUCCESS_SIGNAL, signal);
        assertEquals(Collections.singletonList("a,b"), node.getExecutions());
        assertTrue(context.hasReplyContaining("============ 鹊桥 ==========="));
        assertTrue(context.hasReplyContaining("============================"));
    }

    @Test
    @DisplayName("无权限：命令体不执行，只回执无权限提示")
    void permissionDeniedSkipsCommand() {
        ProbeNode<Object> node = new ProbeNode<>("probe");
        FakeCommandExecutionContext<Object> context =
                new FakeCommandExecutionContext<>(new Object(), false);

        int signal = node.execute(context, Collections.<String>emptyList());

        assertEquals(CommandConstant.FAIL_SIGNAL, signal);
        assertTrue(node.getExecutions().isEmpty(), "无权限时命令体不得执行");
        assertTrue(context.hasReplyContaining("您没有权限执行此命令。"));
        assertFalse(context.hasReplyContaining("============ 鹊桥 ==========="), "无权限时不应输出分隔线");
    }

    // ------------------------------------------------------------------
    // 3. reply
    // ------------------------------------------------------------------

    @Test
    @DisplayName("命令体抛异常：回执错误信息并返回失败信号")
    void commandBodyFailureIsReported() {
        CommandNode<Object> throwing = new CommandNode<Object>(LOGGER) {
            @Override
            public String getName() {
                return "boom";
            }

            @Override
            public String getDescription() {
                return "会抛异常的命令";
            }

            @Override
            protected void onExecute(CommandExecutionContext<Object> context, List<String> args) {
                throw new IllegalStateException("炸了");
            }
        };
        FakeCommandExecutionContext<Object> context =
                new FakeCommandExecutionContext<>(new Object(), true);

        int signal = throwing.execute(context, Collections.<String>emptyList());

        assertEquals(CommandConstant.FAIL_SIGNAL, signal);
        assertTrue(context.hasReplyContaining("命令执行出错"));
    }

    // ------------------------------------------------------------------
    // 4~6. 注册不变量
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同一父节点下重复名称被拒绝（fail-fast，不静默覆盖）")
    void duplicateChildNameIsRejected() {
        ProbeNode<Object> parent = new ProbeNode<>("parent");

        parent.addChild(new ProbeNode<Object>("foo"));

        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> parent.addChild(new ProbeNode<Object>("foo")));
        assertTrue(e.getMessage().contains("foo"), "错误信息应指出冲突的名称：" + e.getMessage());
        assertEquals(1, parent.getChildren().size(), "失败后不得改变子命令列表");
    }

    @Test
    @DisplayName("名称比较忽略大小写（与路由匹配规则一致）")
    void duplicateChildNameIsCaseInsensitive() {
        ProbeNode<Object> parent = new ProbeNode<>("parent");
        parent.addChild(new ProbeNode<Object>("Foo"));

        assertThrows(IllegalArgumentException.class, () -> parent.addChild(new ProbeNode<Object>("foo")));
    }

    @Test
    @DisplayName("已有 parent 的节点不能再注册到第二个 parent（不自动 detach）")
    void childWithExistingParentIsRejected() {
        ProbeNode<Object> parentA = new ProbeNode<>("parentA");
        ProbeNode<Object> parentB = new ProbeNode<>("parentB");
        ProbeNode<Object> child = new ProbeNode<>("child");

        parentA.addChild(child);

        assertThrows(IllegalArgumentException.class, () -> parentB.addChild(child));
        assertSame(parentA, child.getParent(), "原 parent 关系不得被改动");
        assertTrue(parentB.getChildren().isEmpty());
    }

    @Test
    @DisplayName("形成环的注册被拒绝（把自己或祖先挂到后代之下）")
    void cycleIsRejected() {
        ProbeNode<Object> root = new ProbeNode<>("root");
        ProbeNode<Object> child = new ProbeNode<>("child");
        root.addChild(child);

        assertThrows(IllegalArgumentException.class, () -> child.addChild(root), "祖先挂到后代之下应被拒绝");
        assertThrows(IllegalArgumentException.class, () -> root.addChild(root), "自环应被拒绝");
    }

    @Test
    @DisplayName("null 子节点与空白名称被拒绝")
    void nullAndBlankChildAreRejected() {
        ProbeNode<Object> parent = new ProbeNode<>("parent");

        assertThrows(NullPointerException.class, () -> parent.addChild(null));
        assertThrows(IllegalArgumentException.class, () -> parent.addChild(new ProbeNode<Object>("   ")));
        assertThrows(IllegalArgumentException.class, () -> parent.addChild(new ProbeNode<Object>(null)));
    }

    // ------------------------------------------------------------------
    // 树结构基本行为
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getChildren 返回只读视图，外部无法直接增删")
    void childrenViewIsUnmodifiable() {
        ProbeNode<Object> parent = new ProbeNode<>("parent");
        parent.addChild(new ProbeNode<Object>("foo"));

        assertThrows(UnsupportedOperationException.class, () -> parent.getChildren().clear());
    }

    @Test
    @DisplayName("完整路径与权限节点按层级拼接")
    void fullPathAndPermissionNode() {
        ProbeNode<Object> root = new ProbeNode<>("queqiao");
        ProbeNode<Object> child = new ProbeNode<>("client");
        ProbeNode<Object> grandChild = new ProbeNode<>("list");
        root.addChild(child);
        child.addChild(grandChild);

        assertEquals("/queqiao client list", grandChild.getFullPath());
        assertEquals("queqiao.client.list", grandChild.getFullPermissionNode());
        assertTrue(root.isRoot());
        assertFalse(grandChild.isRoot());
    }

    // ------------------------------------------------------------------
    // API 硬化：final 模板方法 + 私有结构字段
    // ------------------------------------------------------------------

    @Test
    @DisplayName("结构修改与执行模板方法均为 final，第三方无法覆盖绕过 Core 不变量")
    void structuralAndTemplateMethodsAreFinal() throws Exception {
        assertFinal("addChild", CommandNode.class);
        assertFinal("execute", CommandExecutionContext.class, List.class);
        assertFinal("sendCommandTree", CommandExecutionContext.class, CommandNode.class);
        assertFinal("getChildren");
        assertFinal("getParent");
        assertFinal("isRoot");
        assertFinal("getFullPath");
        assertFinal("getFullPermissionNode");
        assertFinal("freeze");
        assertFinal("isFrozen");
    }

    private static void assertFinal(String name, Class<?>... parameterTypes) throws Exception {
        Method method = CommandNode.class.getMethod(name, parameterTypes);
        assertTrue(
                Modifier.isFinal(method.getModifiers()),
                name + " 必须为 final —— 它承担 Core 不变量，不允许被覆盖绕过");
    }

    @Test
    @DisplayName("parent / children 为 private，第三方无法直接改写命令树")
    void treeStateIsPrivate() throws Exception {
        Field parent = CommandNode.class.getDeclaredField("parent");
        Field children = CommandNode.class.getDeclaredField("children");

        assertTrue(Modifier.isPrivate(parent.getModifiers()), "parent 必须 private");
        assertTrue(Modifier.isPrivate(children.getModifiers()), "children 必须 private");
    }

    // ------------------------------------------------------------------
    // freeze：注册生命周期
    // ------------------------------------------------------------------

    @Test
    @DisplayName("freeze 后禁止 addChild，且递归生效、可重复调用")
    void freezeBlocksFurtherRegistration() {
        ProbeNode<Object> root = new ProbeNode<>("root");
        ProbeNode<Object> child = new ProbeNode<>("child");
        root.addChild(child);

        assertFalse(root.isFrozen(), "构造后不应自动冻结");
        root.freeze();

        assertTrue(root.isFrozen());
        assertTrue(child.isFrozen(), "freeze 应递归到子节点");
        root.freeze();   // 幂等，不应抛异常

        assertThrows(
                IllegalStateException.class,
                () -> root.addChild(new ProbeNode<Object>("late")),
                "冻结后注册必须 fail-fast");
        assertThrows(
                IllegalStateException.class,
                () -> child.addChild(new ProbeNode<Object>("late")),
                "子节点也应已冻结");
    }

    // ------------------------------------------------------------------
    // 入参 fail-fast
    // ------------------------------------------------------------------

    @Test
    @DisplayName("execute 对 null context / args fail-fast")
    void executeRejectsNullArguments() {
        ProbeNode<Object> node = new ProbeNode<>("probe");
        FakeCommandExecutionContext<Object> context = new FakeCommandExecutionContext<>(new Object());

        assertThrows(NullPointerException.class, () -> node.execute(null, Collections.<String>emptyList()));
        assertThrows(NullPointerException.class, () -> node.execute(context, null));
    }

    @Test
    @DisplayName("sendCommandTree 对 null 参数 fail-fast")
    void sendCommandTreeRejectsNullArguments() {
        ProbeNode<Object> node = new ProbeNode<>("probe");
        FakeCommandExecutionContext<Object> context = new FakeCommandExecutionContext<>(new Object());

        assertThrows(NullPointerException.class, () -> node.sendCommandTree(null, node));
        assertThrows(NullPointerException.class, () -> node.sendCommandTree(context, null));
    }

    // ------------------------------------------------------------------
    // 异常脱敏
    // ------------------------------------------------------------------

    @Test
    @DisplayName("命令体异常不把原始 exception message 泄漏给命令调用者")
    void exceptionMessageIsNotLeaked() {
        String secret = "SECRET-INTERNAL-DETAIL /etc/passwd token=abc123";
        CommandNode<Object> throwing = new CommandNode<Object>(LOGGER) {
            @Override
            public String getName() {
                return "leaky";
            }

            @Override
            public String getDescription() {
                return "会泄漏细节的命令";
            }

            @Override
            protected void onExecute(CommandExecutionContext<Object> context, List<String> args) {
                throw new IllegalStateException(secret);
            }
        };
        FakeCommandExecutionContext<Object> context =
                new FakeCommandExecutionContext<>(new Object(), true);

        throwing.execute(context, Collections.<String>emptyList());

        assertTrue(context.hasReplyContaining("命令执行出错"), "应回执通用错误提示");
        for (String reply : context.getReplies()) {
            assertFalse(
                    reply.contains("SECRET-INTERNAL-DETAIL"),
                    "回执不得包含原始异常信息，实际=" + reply);
        }
    }
}
