package io.github.theword.queqiao.core.config.validation;

import io.github.theword.queqiao.core.config.exception.ConfigValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验器工具测试
 */
class ConfigValidatorsTest {

    @Test
    @DisplayName("range：闭区间，越界报错并带路径与范围")
    void range() {
        ConfigValidator<Integer> validator = ConfigValidators.range(1, 65535);

        assertDoesNotThrow(() -> validator.validate("a.port", 1));
        assertDoesNotThrow(() -> validator.validate("a.port", 65535));

        ConfigValidationException e =
                assertThrows(ConfigValidationException.class, () -> validator.validate("a.port", 65536));
        assertTrue(e.getMessage().contains("a.port"), e.getMessage());
        assertTrue(e.getMessage().contains("65535"), e.getMessage());
    }

    @Test
    @DisplayName("range：min > max 属于编程错误，构建时即失败")
    void rangeWithInvalidBounds() {
        assertThrows(IllegalArgumentException.class, () -> ConfigValidators.range(10, 1));
    }

    @Test
    @DisplayName("positive：必须大于给定下界")
    void positive() {
        ConfigValidator<Integer> validator = ConfigValidators.positive(0);

        assertDoesNotThrow(() -> validator.validate("a.n", 1));
        assertThrows(ConfigValidationException.class, () -> validator.validate("a.n", 0));
        assertThrows(ConfigValidationException.class, () -> validator.validate("a.n", -1));
    }

    @Test
    @DisplayName("nonEmpty：空串报错，null 交给 codec 决定")
    void nonEmpty() {
        ConfigValidator<String> validator = ConfigValidators.nonEmpty();

        assertDoesNotThrow(() -> validator.validate("a.s", "x"));
        assertDoesNotThrow(() -> validator.validate("a.s", null));
        assertThrows(ConfigValidationException.class, () -> validator.validate("a.s", ""));
    }

    @Test
    @DisplayName("nonBlank：空串与纯空白都报错，null 交给 codec 决定")
    void nonBlank() {
        ConfigValidator<String> validator = ConfigValidators.nonBlank();

        assertDoesNotThrow(() -> validator.validate("a.host", "127.0.0.1"));
        assertDoesNotThrow(() -> validator.validate("a.host", "::1"));
        assertDoesNotThrow(() -> validator.validate("a.host", null));
        assertThrows(ConfigValidationException.class, () -> validator.validate("a.host", ""));
        assertThrows(ConfigValidationException.class, () -> validator.validate("a.host", "   "));
        assertThrows(ConfigValidationException.class, () -> validator.validate("a.host", "\t\n"));
    }

    @Test
    @DisplayName("oneOf：取值必须在集合内")
    void oneOf() {
        ConfigValidator<String> validator = ConfigValidators.oneOf("a", "b");

        assertDoesNotThrow(() -> validator.validate("a.e", "a"));
        assertThrows(ConfigValidationException.class, () -> validator.validate("a.e", "c"));
    }

    @Test
    @DisplayName("notEmpty：空列表与 null 都报错")
    void notEmptyList() {
        ConfigValidator<Collection<String>> validator = ConfigValidators.notEmpty();

        assertDoesNotThrow(() -> validator.validate("a.list", Arrays.asList("x")));
        assertThrows(ConfigValidationException.class, () -> validator.validate("a.list", Collections.emptyList()));
        assertThrows(ConfigValidationException.class, () -> validator.validate("a.list", null));
    }
}
