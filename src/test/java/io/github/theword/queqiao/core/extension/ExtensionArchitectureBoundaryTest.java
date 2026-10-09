package io.github.theword.queqiao.core.extension;

import io.github.theword.queqiao.core.api.Api;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extension SPI 架构边界守卫测试
 *
 * <p>把方案中的"Extension 只是已有 Runtime 上的一段注册逻辑"这条原则变成可执行断言，
 * 防止后续无意中把 WebSocket 实现、Runtime、协议路由或平台类型泄漏进 public SPI。
 *
 * <p>红线（来自方案 §16 / §17 / §27 / §28 / §29 / §30）：
 * <pre>
 * Extension SPI × WebsocketManager / WsServer / WsClient / org.java_websocket
 * Extension SPI × QueQiaoRuntime / ProtocolRouter / AbstractPlatformContext / Config
 * Extension SPI × 平台原生类型
 * QueQiaoExtensionContext × 返回 API 集合（只能 registerApi，不能读取）
 * </pre>
 *
 * <p>源码扫描前先去掉注释，避免 Javadoc 里"提到"某个类型被误判为"依赖"该类型。
 */
class ExtensionArchitectureBoundaryTest {

    private static final String EXTENSION_PACKAGE_DIR =
            "src/main/java/io/github/theword/queqiao/core/extension";

    /**
     * 定位项目根目录：从当前工作目录逐级向上查找含 {@code src/main/java} 的目录
     *
     * <p>不直接依赖测试的工作目录 —— 不同 Gradle 配置下 cwd 可能不是项目根目录。
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

    private static String read(String absolutePath) throws IOException {
        return new String(Files.readAllBytes(Paths.get(absolutePath)), StandardCharsets.UTF_8);
    }

    /**
     * 去掉注释，避免 Javadoc 里"提到"某个类型被误判为"依赖"该类型
     */
    private static String stripComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }

    private static List<Path> extensionSources() throws IOException {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(projectRoot().resolve(EXTENSION_PACKAGE_DIR))) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(result::add);
        }
        return result;
    }

    private static void assertNoToken(String forbidden, String reason) throws IOException {
        for (Path file : extensionSources()) {
            String body = stripComments(read(file.toString()));
            assertTrue(
                    !body.contains(forbidden),
                    file + " 不得引用 " + forbidden + " —— " + reason);
        }
    }

    // ------------------------------------------------------------------
    // 源码边界
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Extension SPI 不依赖 WebSocket 实现（Extension 不拥有连接生命周期）")
    void extensionSpiDoesNotDependOnWebSocketImplementation() throws IOException {
        for (String token : Arrays.asList("WebsocketManager", "WsServer", "WsClient", "org.java_websocket")) {
            assertNoToken(token, "WebSocket 由 Runtime 拥有，Extension 只贡献能力");
        }
    }

    @Test
    @DisplayName("Extension SPI 不依赖 Runtime / 协议路由 / 平台上下文 / 配置")
    void extensionSpiDoesNotDependOnInternalCollaborators() throws IOException {
        String[] forbiddenImports = {
                "import io.github.theword.queqiao.core.runtime",
                "import io.github.theword.queqiao.core.protocol",
                "import io.github.theword.queqiao.core.platform",
                "import io.github.theword.queqiao.core.config",
                "import io.github.theword.queqiao.core.utils",
                "import io.github.theword.queqiao.core.websocket",
                "import io.github.theword.queqiao.core.handle",
        };
        for (String forbidden : forbiddenImports) {
            assertNoToken(forbidden, "Extension SPI 只应依赖 Api 抽象");
        }
    }

    @Test
    @DisplayName("Extension SPI 不依赖具体平台类型")
    void extensionSpiStaysPlatformFree() throws IOException {
        String[] platformImports = {
                "import net.minecraft",
                "import org.bukkit",
                "import com.velocitypowered",
                "import net.neoforged",
                "import net.minecraftforge",
        };
        for (String forbidden : platformImports) {
            assertNoToken(forbidden, "Core 的 Extension SPI 必须平台无关");
        }
    }

    // ------------------------------------------------------------------
    // public API surface（reflection）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("QueQiaoExtension 只暴露 register(QueQiaoExtensionContext)")
    void queQiaoExtensionExposesOnlyRegister() {
        Method[] methods = QueQiaoExtension.class.getDeclaredMethods();

        assertEquals(1, methods.length,
                "QueQiaoExtension 应只有 register 一个方法，实际=" + Arrays.toString(methods));
        assertEquals("register", methods[0].getName());
        assertEquals(void.class, methods[0].getReturnType());
        assertArrayEquals(
                new Class<?>[]{QueQiaoExtensionContext.class},
                methods[0].getParameterTypes(),
                "register 的参数必须是 QueQiaoExtensionContext");
    }

    @Test
    @DisplayName("QueQiaoExtensionContext 只暴露 registerApi(Api)")
    void queQiaoExtensionContextExposesOnlyRegisterApi() {
        Method[] methods = QueQiaoExtensionContext.class.getDeclaredMethods();

        assertEquals(1, methods.length,
                "QueQiaoExtensionContext 应只有 registerApi 一个方法，实际=" + Arrays.toString(methods));
        assertEquals("registerApi", methods[0].getName());
        assertEquals(void.class, methods[0].getReturnType());
        assertArrayEquals(
                new Class<?>[]{Api.class},
                methods[0].getParameterTypes(),
                "registerApi 的参数必须是 Api");
    }

    @Test
    @DisplayName("Extension SPI 的方法签名不暴露内部协作对象，也不返回 API 集合")
    void extensionSpiDoesNotLeakInternalTypes() {
        Set<String> forbiddenTypes = new HashSet<>(Arrays.asList(
                "QueQiaoRuntime", "WebsocketManager", "WsServer", "WsClient",
                "ProtocolRouter", "AbstractPlatformContext", "HandleProtocolMessage"));

        for (Class<?> spi : Arrays.asList(QueQiaoExtension.class, QueQiaoExtensionContext.class)) {
            for (Method method : spi.getMethods()) {
                if (method.getDeclaringClass() == Object.class) {
                    continue;
                }
                assertTrue(
                        !forbiddenTypes.contains(method.getReturnType().getSimpleName()),
                        spi.getSimpleName() + "." + method.getName() + " 不得返回内部类型 "
                                + method.getReturnType().getSimpleName());
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertTrue(
                            !forbiddenTypes.contains(parameter.getSimpleName()),
                            spi.getSimpleName() + "." + method.getName() + " 不得接受内部类型 "
                                    + parameter.getSimpleName());
                }
                // §30：不得暴露可变 API 集合 —— Extension 只能 registerApi，不能读取/改写
                assertTrue(
                        !Collection.class.isAssignableFrom(method.getReturnType()),
                        spi.getSimpleName() + "." + method.getName() + " 不得返回集合类型");
            }
        }
    }
}
