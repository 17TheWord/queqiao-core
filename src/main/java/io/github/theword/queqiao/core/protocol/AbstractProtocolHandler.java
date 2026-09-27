package io.github.theword.queqiao.core.protocol;

import io.github.theword.queqiao.core.api.Api;
import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.exception.protocol.ProtocolException;
import io.github.theword.queqiao.core.handle.HandleApiService;
import io.github.theword.queqiao.core.payload.EmptyPayload;
import io.github.theword.queqiao.core.utils.GsonUtils;
import com.google.gson.JsonParseException;
import com.google.gson.JsonElement;
import org.slf4j.Logger;

import java.util.Objects;

/**
 * 协议处理器抽象基类
 *
 * <p><b>它是 {@link Api} 的一种可选实现方式</b>：负责"把 {@code JsonElement}
 * 解析成强类型 Payload 再交给子类"。{@code Api} 本身是接口，不强制继承本类——
 * 新的 API 可以像 {@code PrivateMessageApi} 那样直接 {@code implements Api}。
 *
 * <p><b>线程安全约束（重要）</b>：处理器实例由 {@link ProtocolRouter} 在构造阶段创建一次，
 * 随后在多个连接、多个线程之间共享。因此实现<b>必须无状态</b>——
 * 不得持有任何随请求变化的字段。
 *
 * <p>全部输入应来自方法参数，输出通过返回值表达。
 * 违反该约束会引入静默的数据竞争：不同连接的请求会互相污染中间状态。
 *
 * <p><b>依赖来源</b>：日志实现与平台 API 实现均由 {@link ProtocolRouter} 注入，
 * 处理器<b>不应</b>访问任何静态全局状态——
 * 那会让协议层依赖全局状态、无法独立测试（连"成功路径"都测不到）。
 *
 * @param <P> 负载类型
 * @param <R> 返回类型
 * @since 0.6.11
 */
public abstract class AbstractProtocolHandler<P, R> implements Api {

    /**
     * 日志实现，由 ProtocolRouter 注入
     */
    protected final Logger logger;

    /**
     * 平台 API 实现，由 ProtocolRouter 注入
     *
     * <p>允许为 null：运行时空对象（尚未 {@code init}）状态下平台实现尚未注入，
     * 但该状态下不存在任何连接，因此处理器不可能被调用。
     */
    protected final HandleApiService handleApiService;

    private final Class<P> payloadType;

    /**
     * 协议 API 名称
     *
     * <p>由子类通过构造器传入，而不是硬编码在基类里——因为同一个处理器类
     * 可能被注册到多个名称下（例如 {@code broadcast} 与 {@code send_msg}）。
     */
    private final String apiName;

    protected AbstractProtocolHandler(String apiName, Logger logger, HandleApiService handleApiService, Class<P> payloadType) {
        this.apiName = Objects.requireNonNull(apiName, "apiName");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.handleApiService = handleApiService;
        this.payloadType = Objects.requireNonNull(payloadType, "payloadType");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return apiName;
    }

    @Override
    public final R handle(JsonElement data) throws ProtocolException {
        if (payloadType == EmptyPayload.class) {
            // 协议决策：对"无负载"的 api（如 get_status），data 字段被忽略、不做校验。
            // 理由：客户端普遍习惯性发送 "data": {} 或 "data": null，
            // 拒绝它们只会造成不必要的兼容性破坏，而忽略 data 不存在安全影响。
            return handlePayload(payloadType.cast(EmptyPayload.INSTANCE));
        }
        P payload;
        try {
            payload = GsonUtils.getGson().fromJson(data, payloadType);
        } catch (JsonParseException | IllegalStateException e) {
            throw ProtocolException.badRequest(ProtocolConstants.Message.PARSE_DATA_FAILED, data);
        }
        if (payload == null) {
            throw ProtocolException.badRequest(ProtocolConstants.Message.PARSE_DATA_FAILED, data);
        }
        return handlePayload(payload);
    }

    protected abstract R handlePayload(P payload) throws ProtocolException;
}
