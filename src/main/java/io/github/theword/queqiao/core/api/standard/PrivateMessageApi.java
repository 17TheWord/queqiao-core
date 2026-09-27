package io.github.theword.queqiao.core.api.standard;

import io.github.theword.queqiao.core.api.Api;
import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.exception.protocol.ProtocolException;
import io.github.theword.queqiao.core.payload.PrivateMessagePayload;
import io.github.theword.queqiao.core.response.PrivateMessageResponse;
import io.github.theword.queqiao.core.service.PrivateMessageService;
import io.github.theword.queqiao.core.utils.GsonUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

import java.util.Objects;

/**
 * 私聊 API（{@code send_private_msg}）
 *
 * <p>本类<b>属于 Core，但不是 Core 强制注册的 API</b>：
 * 只有平台注册了它，客户端才能调用 {@code send_private_msg}；
 * 未注册时协议层返回 404。这正是"平台没有能力就不注册"的落地方式。
 *
 * <p><b>三层职责边界</b>：
 * <pre>
 * PrivateMessageApi      ← 协议层：解析 payload、参数校验、返回响应
 *         ↓
 * PrivateMessageService  ← Core 业务：查找目标、找不到怎么办、成功后返回什么
 *         ↓
 * PlayerProvider / PlayerMessageSender  ← 平台层：怎么拿玩家、怎么发消息
 * </pre>
 *
 * <p>本类<b>不</b>继承 {@code AbstractProtocolHandler}：{@code Api} 是接口，
 * 新式 API 可以完全独立地实现它。
 *
 * <p>线程安全：无状态，可被多连接并发共享。
 *
 * @since 0.7.0
 */
public final class PrivateMessageApi implements Api {

    private final PrivateMessageService service;

    /**
     * 构造私聊 API
     *
     * @param service 私聊业务流程，不得为 null
     */
    public PrivateMessageApi(PrivateMessageService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return ProtocolConstants.Api.SEND_PRIVATE_MSG;
    }

    /**
     * 解析请求、校验参数并执行私聊
     *
     * <p>校验规则与迁移前保持一致：{@code nickname} 与 {@code uuid} 至少要有一个有效；
     * {@code nickname} 使用 {@code trim()} 后判空，因此纯空白字符串视为未提供。
     * 两者都无效时抛出 400，而不是"找不到玩家"。
     *
     * <p>通过校验后，{@code nickname} 原样交给 {@link PrivateMessageService}，
     * 由后者统一 trim 并执行查找（规则见
     * {@code io.github.theword.queqiao.core.service.PlayerLookupService}）。
     *
     * @param data 请求体中的 {@code data} 字段
     * @return {@link PrivateMessageResponse}
     * @throws ProtocolException 请求体无法解析或目标玩家信息缺失时抛出 400
     */
    @Override
    public Object handle(JsonElement data) throws ProtocolException {
        PrivateMessagePayload payload = parsePayload(data);

        String nickname = payload.getNickname();
        boolean nicknameMissing = nickname == null || nickname.trim().isEmpty();
        if (nicknameMissing && payload.getUuid() == null) {
            PrivateMessageResponse response = PrivateMessageResponse.playerIsNull();
            throw ProtocolException.badRequest(response.getMessage(), response);
        }

        return service.send(nickname, payload.getUuid(), payload.getMessage());
    }

    /**
     * 把 {@code data} 解析成强类型负载
     *
     * <p>这里刻意内联解析逻辑，而不是复用 {@code AbstractProtocolHandler}：
     * 本类是"不继承 Core 基类也能写 API"的示范。
     * 两处解析语义必须保持一致（解析失败 / 结果为 null 都归 400）。
     */
    private static PrivateMessagePayload parsePayload(JsonElement data) throws ProtocolException {
        PrivateMessagePayload payload;
        try {
            payload = GsonUtils.getGson().fromJson(data, PrivateMessagePayload.class);
        } catch (JsonParseException | IllegalStateException e) {
            throw ProtocolException.badRequest(ProtocolConstants.Message.PARSE_DATA_FAILED, data);
        }
        if (payload == null) {
            throw ProtocolException.badRequest(ProtocolConstants.Message.PARSE_DATA_FAILED, data);
        }
        return payload;
    }
}
