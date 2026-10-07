package io.github.theword.queqiao.core.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令 API 的泛型签名守卫测试
 *
 * <p>把"命令 API 必须端到端保持 {@code <NCS>}，不得退化为 raw type"这条要求
 * 变成可执行断言 —— 仅靠编译无法发现 raw type 退化（raw 只是警告）。
 *
 * <p>覆盖：
 * <ul>
 *     <li>四个核心类型的类型参数必须存在且命名为 {@code NCS}；</li>
 *     <li>{@code CommandRouter.getRootCommand()} 的返回类型必须参数化；</li>
 *     <li>{@code CommandRouter.rootCommand} 字段类型必须参数化；</li>
 *     <li>{@code CommandNode.execute} 的 context 参数必须参数化。</li>
 * </ul>
 */
class CommandApiSurfaceTest {

    private static void assertNcsTypeParameter(Class<?> type) {
        TypeVariable<?>[] parameters = type.getTypeParameters();
        assertEquals(1, parameters.length,
                type.getSimpleName() + " 应恰好有 1 个类型参数，实际=" + parameters.length);
        assertEquals("NCS", parameters[0].getName(),
                type.getSimpleName() + " 的类型参数应命名为 NCS（Native Command Source）");
    }

    @Test
    @DisplayName("CommandNode / RootCommand / CommandRouter / CommandExecutionContext 均保留 <NCS>")
    void coreTypesKeepNcsTypeParameter() {
        assertNcsTypeParameter(CommandNode.class);
        assertNcsTypeParameter(RootCommand.class);
        assertNcsTypeParameter(CommandRouter.class);
        assertNcsTypeParameter(CommandExecutionContext.class);
    }

    @Test
    @DisplayName("CommandRouter.getRootCommand() 返回参数化的 RootCommand<NCS>，不是 raw type")
    void getRootCommandReturnsParameterizedType() throws Exception {
        Method method = CommandRouter.class.getMethod("getRootCommand");
        Type returnType = method.getGenericReturnType();

        assertTrue(returnType instanceof ParameterizedType,
                "返回类型必须参数化，实际=" + returnType);
        ParameterizedType parameterized = (ParameterizedType) returnType;
        assertEquals(RootCommand.class, parameterized.getRawType());
        assertTrue(parameterized.getActualTypeArguments()[0] instanceof TypeVariable,
                "类型实参应是 NCS 类型变量，实际=" + parameterized.getActualTypeArguments()[0]);
    }

    @Test
    @DisplayName("CommandRouter.rootCommand 字段类型参数化，不是 raw RootCommand")
    void rootCommandFieldIsParameterized() throws Exception {
        Field field = CommandRouter.class.getDeclaredField("rootCommand");
        Type fieldType = field.getGenericType();

        assertTrue(fieldType instanceof ParameterizedType,
                "字段类型必须参数化，实际=" + fieldType);
        ParameterizedType parameterized = (ParameterizedType) fieldType;
        assertEquals(RootCommand.class, parameterized.getRawType());
        assertEquals("NCS", ((TypeVariable<?>) parameterized.getActualTypeArguments()[0]).getName());
    }

    @Test
    @DisplayName("CommandNode 的公开签名参数化到 NCS，无 raw CommandNode")
    void commandNodeSignaturesAreParameterized() throws Exception {
        Method execute = CommandNode.class.getMethod(
                "execute", CommandExecutionContext.class, List.class);
        Type contextParam = execute.getGenericParameterTypes()[0];
        assertTrue(contextParam instanceof ParameterizedType,
                "execute 的 context 参数应参数化，实际=" + contextParam);
        assertEquals(CommandExecutionContext.class,
                ((ParameterizedType) contextParam).getRawType());

        Method sendTree = CommandNode.class.getMethod(
                "sendCommandTree", CommandExecutionContext.class, CommandNode.class);
        Type nodeParam = sendTree.getGenericParameterTypes()[1];
        assertTrue(nodeParam instanceof ParameterizedType,
                "sendCommandTree 的 command 参数应参数化，实际=" + nodeParam);
        assertEquals(CommandNode.class, ((ParameterizedType) nodeParam).getRawType());

        Method getChildren = CommandNode.class.getMethod("getChildren");
        Type returnType = getChildren.getGenericReturnType();
        assertTrue(returnType instanceof ParameterizedType,
                "getChildren 返回类型应参数化，实际=" + returnType);
    }

    @Test
    @DisplayName("RootCommand 的父类型参数化到 NCS")
    void rootCommandSuperclassIsParameterized() {
        Type superclass = RootCommand.class.getGenericSuperclass();
        assertTrue(superclass instanceof ParameterizedType,
                "RootCommand 的父类型应参数化，实际=" + superclass);
        ParameterizedType parameterized = (ParameterizedType) superclass;
        assertEquals(CommandNode.class, parameterized.getRawType());
        assertTrue(parameterized.getActualTypeArguments()[0] instanceof TypeVariable);
    }
}
