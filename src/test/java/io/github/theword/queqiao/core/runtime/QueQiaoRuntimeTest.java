package io.github.theword.queqiao.core.runtime;

import io.github.theword.queqiao.core.platform.PlatformContext;
import io.github.theword.queqiao.core.platform.TestServer;
import com.google.gson.JsonElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QueQiaoRuntime} 消息前缀解析测试
 *
 * <p>
 * 本测试由原静态全局上下文门面的测试迁移而来：其用例一直在测试 Runtime 本身，
 * 门面只是入口。随门面删除，本测试直接构造 Runtime，不再经过任何静态入口。
 */
class QueQiaoRuntimeTest {

    /**
     * 未启动的 Runtime：构造不触发文件系统与线程
     */
    private final QueQiaoRuntime runtime = QueQiaoRuntime.create(
        null,
        new PlatformContext(new TestServer()),
        null
    );

    @Test
    @DisplayName("实际 Runtime 将普通文本前缀转换为黄色文本组件")
    void plainTextPrefixBecomesYellowComponent() {
        JsonElement result = runtime.initMessagePrefixJsonObject("[鹊桥]");

        assertTrue(result.isJsonObject());
        assertEquals("[鹊桥]", result.getAsJsonObject().get("text").getAsString());
        assertEquals("yellow", result.getAsJsonObject().get("color").getAsString());
    }

    @Test
    @DisplayName("实际 Runtime 保留合法 JSON 对象前缀")
    void validObjectPrefixIsPreserved() {
        JsonElement result = runtime.initMessagePrefixJsonObject("{\"text\":\"[鹊桥]\",\"color\":\"green\"}");

        assertTrue(result.isJsonObject());
        assertEquals("green", result.getAsJsonObject().get("color").getAsString());
    }

    @Test
    @DisplayName("实际 Runtime 保留首项为对象的 JSON 数组前缀")
    void validComponentArrayPrefixIsPreserved() {
        JsonElement result = runtime.initMessagePrefixJsonObject("[{\"text\":\"[鹊桥]\"}]");

        assertTrue(result.isJsonArray());
        assertEquals("[鹊桥]", result.getAsJsonArray().get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    @DisplayName("空白前缀会生成禁用显示的空文本组件")
    void blankPrefixProducesEmptyText() {
        JsonElement result = runtime.initMessagePrefixJsonObject("   ");

        assertTrue(result.isJsonObject());
        assertEquals("", result.getAsJsonObject().get("text").getAsString());
    }

    @Test
    @DisplayName("非法 JSON 回退为普通文本")
    void invalidJsonFallsBackToText() {
        JsonElement result = runtime.initMessagePrefixJsonObject("{invalid json}");

        assertEquals("{invalid json}", result.getAsJsonObject().get("text").getAsString());
        assertEquals("yellow", result.getAsJsonObject().get("color").getAsString());
    }
}
