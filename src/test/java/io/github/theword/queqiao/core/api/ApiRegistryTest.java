package io.github.theword.queqiao.core.api;

import io.github.theword.queqiao.core.exception.protocol.ProtocolException;
import com.google.gson.JsonElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ApiRegistry} 单元测试
 *
 * <p>覆盖"谁决定这个 Runtime 对外开放哪些 API"这一核心命题：
 * 注册集合是唯一事实来源，冻结之后不可再变。
 */
class ApiRegistryTest {

    /**
     * 最小 Api 实现：把收到的 data 原样返回
     */
    private static final class EchoApi implements Api {

        private final String name;

        private EchoApi(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Object handle(JsonElement data) {
            return data;
        }
    }

    @Test
    @DisplayName("注册后可以按名称找到同一个实例")
    void registerThenFind() {
        ApiRegistry registry = new ApiRegistry();
        Api api = new EchoApi("test.echo");

        registry.register(api);

        Optional<Api> found = registry.find("test.echo");
        assertTrue(found.isPresent(), "应能找到已注册的 API");
        assertSame(api, found.get(), "find 应返回注册时的同一个实例");
        assertTrue(registry.contains("test.echo"));
    }

    @Test
    @DisplayName("未注册的名称返回 empty")
    void findUnknownReturnsEmpty() {
        ApiRegistry registry = new ApiRegistry();

        assertFalse(registry.find("no.such.api").isPresent());
        assertFalse(registry.contains("no.such.api"));
    }

    @Test
    @DisplayName("find / contains 对 null 名称安全返回空")
    void findNullIsSafe() {
        ApiRegistry registry = new ApiRegistry();

        assertFalse(registry.find(null).isPresent());
        assertFalse(registry.contains(null));
    }

    @Test
    @DisplayName("同名 API 不允许覆盖，重复注册抛异常")
    void duplicateApiIsRejected() {
        ApiRegistry registry = new ApiRegistry();
        registry.register(new EchoApi("test.echo"));

        assertThrows(IllegalArgumentException.class, () -> registry.register(new EchoApi("test.echo")));
        assertFalse(registry.isFrozen(), "重复注册失败不应改变冻结状态");
    }

    @Test
    @DisplayName("null API 抛 NullPointerException")
    void nullApiIsRejected() {
        ApiRegistry registry = new ApiRegistry();

        assertThrows(NullPointerException.class, () -> registry.register(null));
    }

    @Test
    @DisplayName("名称为 null 或空白时抛 IllegalArgumentException")
    void blankNameIsRejected() {
        ApiRegistry registry = new ApiRegistry();

        assertThrows(IllegalArgumentException.class, () -> registry.register(new EchoApi(null)));
        assertThrows(IllegalArgumentException.class, () -> registry.register(new EchoApi("")));
        assertThrows(IllegalArgumentException.class, () -> registry.register(new EchoApi("   ")));
    }

    @Test
    @DisplayName("freeze 之后注册抛 IllegalStateException，已有 API 仍可查找")
    void registerAfterFreezeIsRejected() {
        ApiRegistry registry = new ApiRegistry();
        registry.register(new EchoApi("test.echo"));

        assertFalse(registry.isFrozen());
        registry.freeze();
        assertTrue(registry.isFrozen());

        assertThrows(IllegalStateException.class, () -> registry.register(new EchoApi("test.late")));
        assertTrue(registry.find("test.echo").isPresent(), "冻结不应影响已注册的 API");
        assertFalse(registry.contains("test.late"));
    }

    @Test
    @DisplayName("freeze 幂等，重复调用不抛异常")
    void freezeIsIdempotent() {
        ApiRegistry registry = new ApiRegistry();

        registry.freeze();
        registry.freeze();

        assertTrue(registry.isFrozen());
    }

    @Test
    @DisplayName("自定义 API 无需修改 Core 即可注册与执行")
    void customApiWorksWithoutCoreChanges() throws ProtocolException {
        ApiRegistry registry = new ApiRegistry();
        Api api = new EchoApi("third.party.api");
        registry.register(api);

        JsonElement payload = new com.google.gson.JsonPrimitive("hello");
        assertEquals(payload, registry.find("third.party.api").orElseThrow().handle(payload));
    }
}
