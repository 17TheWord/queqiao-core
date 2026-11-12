package io.github.theword.queqiao.core.config.validation;

import io.github.theword.queqiao.core.config.exception.ConfigValidationException;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * 常用校验器工厂
 *
 * <p>校验器属于 {@code ConfigKey}，而不是集中在一个庞大的规则表里——
 * 因此将来出现新约束时只需在这里加一个工厂方法，<b>不必改 {@code ConfigKey} 的核心结构</b>。
 *
 * @since 0.6.12
 */
public final class ConfigValidators {

    private ConfigValidators() {
    }

    /**
     * 闭区间范围校验
     *
     * @param min 最小值（含）
     * @param max 最大值（含）
     * @param <T> 可比较的数值类型
     * @return 校验器
     */
    public static <T extends Comparable<T>> ConfigValidator<T> range(T min, T max) {
        if (min.compareTo(max) > 0) {
            throw new IllegalArgumentException("min 不能大于 max：" + min + " > " + max);
        }
        return (path, value) -> {
            if (value == null) {
                throw new ConfigValidationException(path, "值不能为空");
            }
            if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
                throw new ConfigValidationException(
                        path, "值 " + value + " 超出范围 [" + min + ", " + max + "]");
            }
        };
    }

    /**
     * 最小值校验（含边界），适用于有自然类型上限但不设置业务最大值的配置。
     *
     * @param min 最小值（含）
     * @param <T> 可比较的值类型
     * @return 校验器
     */
    public static <T extends Comparable<T>> ConfigValidator<T> atLeast(T min) {
        return (path, value) -> {
            if (value == null || value.compareTo(min) < 0) {
                throw new ConfigValidationException(path, "值必须大于或等于 " + min + "，实际 " + value);
            }
        };
    }

    /**
     * 必须为正数（{@code > 0}）
     *
     * @param <T> 可比较的数值类型
     * @return 校验器
     */
    public static <T extends Comparable<T>> ConfigValidator<T> positive(T zero) {
        return (path, value) -> {
            if (value == null || value.compareTo(zero) <= 0) {
                throw new ConfigValidationException(path, "值必须大于 " + zero + "，实际 " + value);
            }
        };
    }

    /**
     * 字符串非空（允许 null 时不校验，由 codec 决定 null 的语义）
     *
     * @return 校验器
     */
    public static ConfigValidator<String> nonEmpty() {
        return (path, value) -> {
            if (value != null && value.isEmpty()) {
                throw new ConfigValidationException(path, "值不能为空字符串");
            }
        };
    }

    /**
     * 字符串非空白（{@code trim()} 后仍非空）
     *
     * <p>与 {@link #nonEmpty()} 的区别：本校验器同时拒绝<b>纯空白</b>（{@code "   "}）。
     * 用于"空白值无法被使用、只会在更晚的阶段以更难懂的错误暴露"的配置项，
     * 例如主机地址——{@code "  "} 会在建立连接时变成 {@code UnknownHostException}。
     *
     * <p>与 {@link #nonEmpty()} 一致，{@code null} 交给 codec 决定语义，此处不校验。
     *
     * @return 校验器
     */
    public static ConfigValidator<String> nonBlank() {
        return (path, value) -> {
            if (value != null && value.trim().isEmpty()) {
                throw new ConfigValidationException(path, "值不能为空白");
            }
        };
    }

    /**
     * 取值必须在给定集合内
     *
     * @param allowed 允许的取值
     * @param <T>     值类型
     * @return 校验器
     */
    @SafeVarargs
    public static <T> ConfigValidator<T> oneOf(T... allowed) {
        List<T> allowedList = Arrays.asList(allowed);
        return (path, value) -> {
            if (!allowedList.contains(value)) {
                throw new ConfigValidationException(path, "值必须是 " + allowedList + " 之一，实际 " + value);
            }
        };
    }

    /**
     * 列表非空（{@code null} 视为空）
     *
     * @param <T> 元素类型
     * @return 校验器
     */
    public static <T> ConfigValidator<Collection<T>> notEmpty() {
        return (path, value) -> {
            if (value == null || value.isEmpty()) {
                throw new ConfigValidationException(path, "列表不能为空");
            }
        };
    }
}
