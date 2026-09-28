package io.github.theword.queqiao.core.api;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.exception.protocol.ProtocolException;
import io.github.theword.queqiao.core.payload.EmptyPayload;
import io.github.theword.queqiao.core.utils.GsonUtils;

/**
 * 协议 API 抽象基类
 *
 * <p>
 * 一个 Api 就是一个可被客户端调用的协议接口。它<b>自描述</b>——名字由 {@link #name()} 声明，
 * 而不是在外部注册表里配对，因此新增 api 不需要修改路由代码。
 *
 * <p>
 * <b>负载解析由框架代劳</b>：{@link #execute(JsonElement)} 是 final 模板方法，
 * 负责把原始 JSON 解析成 {@code payloadType} 并做错误映射；
 * 子类只需实现 {@link #doExecute(Object)} 处理已经解析好的负载。
 *
 * <p>
 * <b>依赖由子类自行注入</b>：本基类<b>不持有</b>日志实现，也不持有任何平台依赖。
 * 需要什么就在子类构造器里显式接收，避免"每个 Api 都能看见全部能力"的隐式耦合。
 *
 * <p>
 * <b>线程安全约束（重要）</b>：Api 实例由 {@code ProtocolRouter} 在构造阶段创建一次，
 * 随后在多个连接、多个线程之间共享。因此实现<b>必须无状态</b>——
 * 不得持有任何随请求变化的字段。全部输入应来自方法参数，输出通过返回值表达。
 * 违反该约束会引入静默的数据竞争：不同连接的请求会互相污染中间状态。
 *
 * <p>
 * <b>不访问静态全局状态</b>：所有依赖应由构造器注入，
 * 否则协议层将无法脱离全局上下文独立测试。
 *
 * @param <P> 负载类型
 * @param <R> 返回类型
 */
public abstract class Api<P, R> {

    private final Class<P> payloadType;

    /**
     * @param payloadType 负载类型，不得为 null
     */
    protected Api(Class<P> payloadType) {
        this.payloadType = Objects.requireNonNull(payloadType, "payloadType");
    }

    /**
     * 协议 {@code api} 字段的主名字，例如 {@code "broadcast"}
     *
     * @return api 名字
     */
    public abstract String name();

    /**
     * 别名
     *
     * <p>
     * 用于"同一操作有多个协议名"的情况，例如 {@code broadcast} 与 {@code send_msg}
     * 由同一个 Api 实例服务。
     *
     * @return 别名集合，默认空集合
     */
    public Set<String> aliases() {
        return Collections.emptySet();
    }

    /**
     * 处理一个请求
     *
     * <p>
     * 模板方法：解析负载 → 委托 {@link #doExecute(Object)}。子类不应覆盖。
     *
     * @param data 原始 JSON 负载，可为 null
     * @return 处理结果
     * @throws ProtocolException 请求不合法或处理失败
     */
    public final R execute(JsonElement data) throws ProtocolException {
        if (payloadType == EmptyPayload.class) {
            // 协议决策：对"无负载"的 api（如 get_status），data 字段被忽略、不做校验。
            // 理由：客户端普遍习惯性发送 "data": {} 或 "data": null，
            // 拒绝它们只会造成不必要的兼容性破坏，而忽略 data 不存在安全影响。
            return doExecute(payloadType.cast(EmptyPayload.INSTANCE));
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
        return doExecute(payload);
    }

    /**
     * 处理已经解析好的负载
     *
     * @param payload 负载，永不为 null
     * @return 处理结果
     * @throws ProtocolException 处理失败
     */
    protected abstract R doExecute(P payload) throws ProtocolException;
}
