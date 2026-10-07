package io.github.theword.queqiao.core.platform;

import java.util.Objects;

/**
 * 平台操作的统一结果
 *
 * <p>把"平台侧发生了什么"用一种<b>不依赖协议层</b>的方式表达出来：
 * <pre>
 * PlatformContext → PlatformResult&lt;T&gt; → Api → Protocol Response / ProtocolException
 * </pre>
 *
 * <p><b>边界（重要）</b>：本类<b>不</b>依赖 {@code Api}、{@code Response}、
 * {@code ProtocolException} 或 WebSocket，也<b>不</b>持有 HTTP-like 状态码。
 * 把 {@link PlatformResultCode} 翻译成协议状态是 {@code Api} 层的职责。
 *
 * <p>因此其它模块（命令层、未来的非协议调用方）也可以直接调用
 * {@code PlatformContext} 并消费本类型，完全不需要经过 Api / WebSocket。
 *
 * <p><b>不可变</b>：三个字段均为 final，可安全地在多线程之间传递。
 *
 * <p><b>为什么不使用 record</b>：本模块以 {@code release = 8} 编译（需支持到 1.7.10），
 * {@code record} 是 Java 16+ 语法，无法使用。访问器沿用项目的 getter 风格。
 *
 * @param <T> 成功时的数据类型；无数据时用 {@link Void}
 * @since 0.7.0
 */
public final class PlatformResult<T> {

    private final PlatformResultCode code;
    private final String message;
    private final T data;

    private PlatformResult(PlatformResultCode code, String message, T data) {
        this.code = Objects.requireNonNull(code, "code");
        this.message = message;
        this.data = data;
    }

    /**
     * 构造成功结果
     *
     * @param data 成功数据，允许为 null（{@link Void} 场景）
     * @param <T>  数据类型
     * @return 成功结果
     */
    public static <T> PlatformResult<T> success(T data) {
        return new PlatformResult<>(PlatformResultCode.SUCCESS, null, data);
    }

    /**
     * 构造带说明的成功结果
     *
     * @param data    成功数据，允许为 null
     * @param message 说明信息，可为 null
     * @param <T>     数据类型
     * @return 成功结果
     */
    public static <T> PlatformResult<T> success(T data, String message) {
        return new PlatformResult<>(PlatformResultCode.SUCCESS, message, data);
    }

    /**
     * 构造失败结果
     *
     * @param code    失败结果码，不得为 null 且不应为 {@link PlatformResultCode#SUCCESS}
     * @param message 失败说明，可为 null
     * @param <T>     数据类型（失败时 data 恒为 null）
     * @return 失败结果
     */
    public static <T> PlatformResult<T> failure(PlatformResultCode code, String message) {
        return new PlatformResult<>(code, message, null);
    }

    /**
     * @return 结果码
     */
    public PlatformResultCode getCode() {
        return code;
    }

    /**
     * @return 说明信息，可为 null
     */
    public String getMessage() {
        return message;
    }

    /**
     * @return 成功数据；失败时为 null
     */
    public T getData() {
        return data;
    }

    /**
     * @return 是否成功
     */
    public boolean isSuccess() {
        return code == PlatformResultCode.SUCCESS;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlatformResult)) {
            return false;
        }
        PlatformResult<?> other = (PlatformResult<?>) o;
        return code == other.code
                && Objects.equals(message, other.message)
                && Objects.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, message, data);
    }

    @Override
    public String toString() {
        return "PlatformResult{code=" + code
                + ", message=" + message
                + ", data=" + data
                + '}';
    }
}
