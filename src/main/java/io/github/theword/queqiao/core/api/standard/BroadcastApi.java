package io.github.theword.queqiao.core.api.standard;

import io.github.theword.queqiao.core.api.Api;
import io.github.theword.queqiao.core.api.platform.BroadcastService;
import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.exception.protocol.ProtocolException;
import io.github.theword.queqiao.core.payload.MessagePayload;
import io.github.theword.queqiao.core.utils.GsonUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

import java.util.Objects;

/**
 * 广播 API（{@code broadcast} / {@code send_msg}）
 *
 * <p><b>名称由构造器传入</b>：{@code broadcast} 与 {@code send_msg} 语义相同，
 * 因此由平台创建<b>两个</b>实例、指向<b>同一个</b> {@link BroadcastService}：
 * <pre>
 * BroadcastService service = new XxxBroadcastService(server);
 * registry.register(new BroadcastApi("broadcast", service));
 * registry.register(new BroadcastApi("send_msg", service));
 * </pre>
 * 本阶段刻意<b>不</b>引入 alias 抽象——先验证"多个注册项可以指向相同的平台能力实现"。
 *
 * <p><b>属于 Core，但不是 Core 强制注册的 API</b>：Core 默认<b>不</b>注册
 * {@code broadcast} / {@code send_msg}，平台不注册时客户端会得到 404。
 *
 * <p><b>职责边界</b>：
 * <pre>
 * BroadcastApi        ← 协议层：解析 payload、组装响应
 *         ↓
 * BroadcastService    ← 平台层：真正的广播动作
 * </pre>
 *
 * <p>线程安全：无状态，可被多连接并发共享。
 *
 * @since 0.7.0
 */
public final class BroadcastApi implements Api {

    private final String name;
    private final BroadcastService service;

    /**
     * 构造广播 API
     *
     * @param name    协议 API 名称（{@code broadcast} 或 {@code send_msg}），不得为 null / 空白
     * @param service 平台广播能力，不得为 null
     */
    public BroadcastApi(String name, BroadcastService service) {
        this.name = Objects.requireNonNull(name, "name");
        this.service = Objects.requireNonNull(service, "service");
        if (name.trim().isEmpty()) {
            throw new IllegalArgumentException("BroadcastApi 名称不能为空白");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * 解析请求并执行广播
     *
     * <p><b>与迁移前的行为保持一致</b>：{@code message} 不做额外校验、原样透传给平台，
     * 响应 data 为 null（等价于原 {@code BroadcastHandler} 返回 {@code Void} 的效果）。
     * 本阶段只验证"可选注册"这一条，不引入新的校验语义。
     *
     * @param data 请求体中的 {@code data} 字段
     * @return null（响应 data 为空，与迁移前一致）
     * @throws ProtocolException 请求体无法解析时抛出 400
     */
    @Override
    public Object handle(JsonElement data) throws ProtocolException {
        MessagePayload payload = parsePayload(data);
        service.broadcast(payload.getMessage());
        return null;
    }

    /**
     * 把 {@code data} 解析成 {@link MessagePayload}
     *
     * <p>与 {@code AbstractProtocolHandler} 的解析语义保持一致：
     * 解析失败或结果为 null 都归 400。
     */
    private static MessagePayload parsePayload(JsonElement data) throws ProtocolException {
        MessagePayload payload;
        try {
            payload = GsonUtils.getGson().fromJson(data, MessagePayload.class);
        } catch (JsonParseException | IllegalStateException e) {
            throw ProtocolException.badRequest(ProtocolConstants.Message.PARSE_DATA_FAILED, data);
        }
        if (payload == null) {
            throw ProtocolException.badRequest(ProtocolConstants.Message.PARSE_DATA_FAILED, data);
        }
        return payload;
    }
}
