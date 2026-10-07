package io.github.theword.queqiao.core.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 架构边界守卫测试
 *
 * <p>把方案中的"泛型红线"变成可执行的断言，防止后续重构无意中把 {@code NCS}
 * 或命令上下文泄漏到 Command 子系统之外。
 *
 * <p>红线（来自方案 §2 / §6 / §13 / §33）：
 * <pre>
 * Runtime        × CommandExecutionContext / CommandNode / CommandRouter
 * WebsocketManager × 同上
 * AbstractPlatformContext × 同上
 * </pre>
 *
 * <p>实现方式：直接读取源码文本做静态断言。测试工作目录为项目根目录
 * （与 {@code ConfigStore} 的路径解析约定一致）。
 */
class CommandArchitectureBoundaryTest {

    private static final String COMMAND_PACKAGE_DIR =
            "src/main/java/io/github/theword/queqiao/core/command";

    /**
     * 定位项目根目录：从当前工作目录逐级向上查找含 {@code src/main/java} 的目录
     *
     * <p>不直接依赖测试的工作目录 —— 不同 Gradle 配置下 cwd 可能不是项目根目录，
     * 用相对路径读源码会直接 NoSuchFileException。
     */
    private static Path projectRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 8 && current != null; i++) {
            if (Files.isDirectory(current.resolve("src/main/java"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
                "未找到项目根目录（应含 src/main/java），起始目录=" + Paths.get("").toAbsolutePath());
    }

    private static String read(String relativePath) throws IOException {
        return new String(
                Files.readAllBytes(projectRoot().resolve(relativePath)), StandardCharsets.UTF_8);
    }

    /**
     * 去掉注释，避免 javadoc 里"提到"某个类型被误判为"依赖"该类型
     */
    private static String stripComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }

    private static List<Path> javaFilesUnder(String dir) throws IOException {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(projectRoot().resolve(dir))) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(result::add);
        }
        return result;
    }

    private static void assertNoReference(String dir, String forbidden, String reason) throws IOException {
        for (Path file : javaFilesUnder(dir)) {
            String body = stripComments(read(file.toString()));
            assertTrue(
                    !body.contains(forbidden),
                    file + " 不得引用 " + forbidden + " —— " + reason);
        }
    }

    @Test
    @DisplayName("Runtime 不知道命令上下文的存在")
    void runtimeDoesNotReferenceCommandContext() throws IOException {
        assertNoReference(
                "src/main/java/io/github/theword/queqiao/core/runtime",
                "CommandExecutionContext",
                "命令上下文只能存在于 Command 子系统内部");
        assertNoReference(
                "src/main/java/io/github/theword/queqiao/core/runtime",
                "CommandNode",
                "命令树属于 Command 子系统");
    }

    @Test
    @DisplayName("WebsocketManager 不知道命令上下文的存在")
    void websocketManagerDoesNotReferenceCommandContext() throws IOException {
        assertNoReference(
                "src/main/java/io/github/theword/queqiao/core/utils",
                "CommandExecutionContext",
                "WebSocket 生命周期操作只返回结果，不负责回执");
        assertNoReference(
                "src/main/java/io/github/theword/queqiao/core/utils",
                "CommandNode",
                "WebSocket 不属于 Command 子系统");
    }

    @Test
    @DisplayName("AbstractPlatformContext 不知道命令上下文的存在")
    void platformContextDoesNotReferenceCommandContext() throws IOException {
        assertNoReference(
                "src/main/java/io/github/theword/queqiao/core/platform",
                "CommandExecutionContext",
                "平台上下文只负责平台公共能力");
        assertNoReference(
                "src/main/java/io/github/theword/queqiao/core/platform",
                "CommandNode",
                "平台上下文不属于 Command 子系统");
    }

    @Test
    @DisplayName("NCS 只出现在 Command 包内（泛型红线）")
    void ncsIsConfinedToCommandPackage() throws IOException {
        Path root = projectRoot();
        String commandDir = root.resolve(COMMAND_PACKAGE_DIR).toString().replace('\\', '/');

        for (Path file : javaFilesUnder("src/main/java")) {
            if (file.toString().replace('\\', '/').startsWith(commandDir)) {
                continue;
            }
            String body = stripComments(read(root.relativize(file).toString().replace('\\', '/')));
            assertTrue(
                    !body.contains("<NCS>") && !body.contains("<NCS,"),
                    file + " 位于 Command 包之外，不得出现 NCS 泛型参数");
        }
    }

    /**
     * 强制转换的形态：{@code (CommandExecutionContext)} 或 {@code (CommandExecutionContext<...>)}
     *
     * <p>注意不能简单搜索 {@code "(CommandExecutionContext"} ——
     * 方法参数声明 {@code onExecute(CommandExecutionContext<NCS> context, ...)} 也包含该子串，
     * 那是签名而不是强转。
     */
    private static final java.util.regex.Pattern CONTEXT_CAST =
            java.util.regex.Pattern.compile("\\(\\s*CommandExecutionContext(\\s*<[^>]*>)?\\s*\\)");

    @Test
    @DisplayName("命令主执行链不含通配符或强制转换")
    void dispatchChainHasNoWildcardOrCast() throws IOException {
        for (Path file : javaFilesUnder(COMMAND_PACKAGE_DIR)) {
            String body = stripComments(read(file.toString()));
            assertTrue(
                    !body.contains("CommandExecutionContext<?"),
                    file + " 不得在分派主链使用 CommandExecutionContext<?>");
            assertTrue(
                    !CONTEXT_CAST.matcher(body).find(),
                    file + " 不得对 CommandExecutionContext 做强制转换");
        }
    }

    @Test
    @DisplayName("命令包内不出现平台类型（保持平台无关）")
    void commandPackageStaysPlatformFree() throws IOException {
        String[] platformImports = {
                "import net.minecraft",
                "import org.bukkit",
                "import com.velocitypowered",
                "import net.neoforged",
                "import net.minecraftforge",
        };
        for (Path file : javaFilesUnder(COMMAND_PACKAGE_DIR)) {
            String body = read(file.toString());
            for (String forbidden : platformImports) {
                assertTrue(
                        !body.contains(forbidden),
                        file + " 不得引入平台类型：" + forbidden);
            }
        }
    }
}
