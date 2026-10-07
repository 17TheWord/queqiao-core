package io.github.theword.queqiao.core.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlatformResult} 值类型测试
 *
 * <p>锁定两条边界：
 * <ol>
 *     <li>成功时 {@code data} 可为 null（{@link Void} 场景），失败时 {@code data} 恒为 null；</li>
 *     <li>本类型<b>不</b>携带任何协议概念——只有 {@link PlatformResultCode} + message + data。</li>
 * </ol>
 */
class PlatformResultTest {

    @Test
    @DisplayName("success：isSuccess 为 true，message 默认为 null")
    void successCarriesData() {
        PlatformResult<String> result = PlatformResult.success("hello");

        assertTrue(result.isSuccess());
        assertEquals(PlatformResultCode.SUCCESS, result.getCode());
        assertEquals("hello", result.getData());
        assertNull(result.getMessage());
    }

    @Test
    @DisplayName("success：允许 data 为 null（Void 场景）")
    void successAllowsNullData() {
        PlatformResult<Void> result = PlatformResult.success(null);

        assertTrue(result.isSuccess());
        assertNull(result.getData());
    }

    @Test
    @DisplayName("success：可带说明信息")
    void successWithMessage() {
        PlatformResult<String> result = PlatformResult.success("data", "done");

        assertTrue(result.isSuccess());
        assertEquals("data", result.getData());
        assertEquals("done", result.getMessage());
    }

    @Test
    @DisplayName("failure：isSuccess 为 false，data 恒为 null")
    void failureCarriesCodeAndMessage() {
        PlatformResult<String> result =
                PlatformResult.failure(PlatformResultCode.PLAYER_NOT_FOUND, "not found");

        assertFalse(result.isSuccess());
        assertEquals(PlatformResultCode.PLAYER_NOT_FOUND, result.getCode());
        assertEquals("not found", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    @DisplayName("结果码必须非 null")
    void codeMustNotBeNull() {
        assertThrows(NullPointerException.class, () -> PlatformResult.failure(null, "x"));
    }

    @Test
    @DisplayName("equals / hashCode 按三个字段比较")
    void valueSemantics() {
        assertEquals(PlatformResult.success("a"), PlatformResult.success("a"));
        assertEquals(PlatformResult.success("a").hashCode(), PlatformResult.success("a").hashCode());

        assertNotEquals(PlatformResult.success("a"), PlatformResult.success("b"));
        assertNotEquals(
                PlatformResult.success("a"),
                PlatformResult.failure(PlatformResultCode.FAILED, "a"));
        assertNotEquals(
                PlatformResult.failure(PlatformResultCode.FAILED, "a"),
                PlatformResult.failure(PlatformResultCode.UNSUPPORTED, "a"));
    }

    @Test
    @DisplayName("toString 含结果码，便于排查")
    void toStringContainsCode() {
        assertTrue(PlatformResult.success("a").toString().contains("SUCCESS"));
        assertTrue(
                PlatformResult.failure(PlatformResultCode.UNSUPPORTED, "x")
                        .toString()
                        .contains("UNSUPPORTED"));
    }

    // ------------------------------------------------------------------
    // failure 的合法性不变量
    // ------------------------------------------------------------------

    @Test
    @DisplayName("failure 不接受 SUCCESS：非法状态必须快速失败")
    void failureRejectsSuccessCode() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PlatformResult.failure(PlatformResultCode.SUCCESS, "failed"),
                "failure(SUCCESS, ...) 会产生「看似失败实为成功」的非法状态");
    }

    @Test
    @DisplayName("failure 的 code 不得为 null")
    void failureRejectsNullCode() {
        assertThrows(NullPointerException.class, () -> PlatformResult.failure(null, "failed"));
    }

    @Test
    @DisplayName("合法状态均可构造，且 isSuccess 与 code 一致")
    void allLegalStatesAreAccepted() {
        assertTrue(PlatformResult.success(null).isSuccess(), "success(...) 应为成功");

        for (PlatformResultCode code : new PlatformResultCode[] {
                PlatformResultCode.PLAYER_NOT_FOUND,
                PlatformResultCode.INVALID_ARGUMENT,
                PlatformResultCode.UNSUPPORTED,
                PlatformResultCode.FAILED}) {
            PlatformResult<Void> result = PlatformResult.failure(code, "x");
            assertFalse(result.isSuccess(), code + " 不应被视为成功");
            assertEquals(code, result.getCode());
            assertNull(result.getData(), "失败结果的 data 恒为 null");
        }
    }
}
