package io.github.theword.queqiao.core.platform;

/**
 * 平台操作的结果码
 *
 * <p>表达"平台侧发生了什么"，与协议层的 HTTP-like 状态码<b>完全无关</b>：
 * 这里不出现 400 / 404 / 500 之类的概念，也不带任何 int / string 序列化字段。
 * 把结果码翻译成协议状态是 {@code Api} 层的职责。
 *
 * <p><b>不依赖 Protocol</b>：本枚举不得引用 {@code ProtocolException}、{@code Response}
 * 或任何协议类型。
 *
 * @since 0.7.0
 */
public enum PlatformResultCode {

    /**
     * 操作成功
     */
    SUCCESS,

    /**
     * 目标玩家不存在
     *
     * <p>由平台在查找阶段判定；与"请求里没给目标"（{@code INVALID_ARGUMENT}）是两回事。
     */
    PLAYER_NOT_FOUND,

    /**
     * 参数不合法
     *
     * <p>例如命令内容为空。属于调用方的问题。
     */
    INVALID_ARGUMENT,

    /**
     * 平台不支持该能力
     *
     * <p>典型场景：平台没有覆盖标题 / ActionBar 等可选原语。
     */
    UNSUPPORTED,

    /**
     * 其它失败
     *
     * <p>平台侧执行时发生的、无法归入以上分类的错误。
     */
    FAILED
}
