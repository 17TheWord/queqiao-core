package io.github.theword.queqiao.core.extension;

import io.github.theword.queqiao.core.api.Api;

/**
 * QueQiao Runtime 暴露给 Extension 的受控能力集合
 *
 * <p>Context 由 Runtime 创建并注入给 {@link QueQiaoExtension#register(QueQiaoExtensionContext)}，
 * 扩展<b>不应</b>自行实现或构造 Context。
 *
 * <p><b>能力边界</b>：当前版本只开放"注册协议 API"这一项能力。
 * 刻意<b>不</b>暴露 Runtime、WebsocketManager、ProtocolRouter、PlatformContext、Config 或 Logger ——
 * 扩展贡献的 API 天然复用 Runtime 已有的协议栈（ProtocolRouter、Response、echo 与 WebSocket 传输），
 * 无需、也不应直接接触这些底层对象。
 *
 * <p><b>生命周期</b>：Context 在 Runtime 装配 API 集合期间处于可写状态，
 * 装配结束（协议路由构造前）即被 seal。seal 之后调用 {@link #registerApi(Api)}
 * 会抛出 {@link IllegalStateException}，以保证"路由表在构造后不可变"这一不变量。
 *
 * @since 0.7.0-beta.3
 */
public interface QueQiaoExtensionContext {

    /**
     * 向当前 Runtime 注册一个协议 API
     *
     * <p>注册只允许发生在 {@link QueQiaoExtension#register(QueQiaoExtensionContext)}
     * 调用期间。注册的 API 会与内置 API 一起进入同一个最终 API 集合，
     * 因此它自动获得既有的路由、响应封装与 echo 回传能力。
     *
     * <p>重复的 API 名称（与内置 API、与 {@code apiConfigurer} 注册的 API、
     * 或与其它 Extension 注册的 API 冲突）会在 Runtime 构造阶段被拒绝，
     * 不会静默覆盖。
     *
     * @param api 要注册的 API
     * @throws NullPointerException  如果 api 为 null
     * @throws IllegalStateException 如果注册阶段已经结束（Context 已被 seal）
     */
    void registerApi(Api<?, ?> api);
}
