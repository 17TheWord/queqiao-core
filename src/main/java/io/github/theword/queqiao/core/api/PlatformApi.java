package io.github.theword.queqiao.core.api;

import java.util.Objects;

import io.github.theword.queqiao.core.platform.AbstractPlatformContext;
import org.slf4j.Logger;

/**
 * 依赖平台的协议 API 基类
 *
 * <p>
 * 用于表达"这个协议能力需要平台配合才能实现"。相对 {@link Api}，它多强制持有一样东西：
 * {@link AbstractPlatformContext}。
 *
 * <p>
 * <b>三层职责边界</b>：
 * <pre>
 * Api                     = 协议能力
 * PlatformApi             = 依赖平台的协议能力（= Api + 平台上下文）
 * AbstractPlatformContext = 平台能力本身
 * </pre>
 *
 * <p>
 * 本类<b>只负责</b>：继承 {@link Api}、持有 {@link Logger}、强制持有平台上下文。
 * <b>不要</b>在这里增加 {@code broadcast()} / {@code sendMessage()} / {@code sendTitle()} /
 * {@code findPlayer()} 之类的转发方法——那些能力属于 {@link AbstractPlatformContext}，
 * 转发只会多出一层需要同步维护的薄壳。
 *
 * <p>
 * <b>线程安全</b>：与 {@link Api} 相同，实例在多个连接、多个线程之间共享，
 * 因此实现必须无状态；平台上下文本身也是构造期注入的协作者，不随请求变化。
 *
 * @param <P> 负载类型
 * @param <R> 返回类型
 */
public abstract class PlatformApi<P, R> extends Api<P, R> {

    /**
     * 平台上下文
     *
     * <p>由创建方注入，构造时即校验非 null，因此子类可以直接使用而不必再判空。
     */
    protected final AbstractPlatformContext<?, ?, ?> platform;

    /**
     * @param payloadType 负载类型，不得为 null
     * @param logger      日志实现，不得为 null
     * @param platform    平台上下文，不得为 null
     */
    protected PlatformApi(
            Class<P> payloadType,
            Logger logger,
            AbstractPlatformContext<?, ?, ?> platform) {
        super(payloadType, logger);
        this.platform = Objects.requireNonNull(platform, "platform");
    }
}
