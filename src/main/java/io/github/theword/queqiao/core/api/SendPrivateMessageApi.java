package io.github.theword.queqiao.core.api;

import java.util.Objects;

import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.exception.protocol.ProtocolException;
import io.github.theword.queqiao.core.payload.PrivateMessagePayload;
import io.github.theword.queqiao.core.platform.AbstractPlatformContext;
import io.github.theword.queqiao.core.response.PrivateMessageResponse;

/**
 * 发送私聊消息
 *
 * <p>协议名：{@code send_private_msg}。
 *
 * <p>校验规则：{@code nickname} 与 {@code uuid} 至少要有一个有效。
 * {@code nickname} 使用 {@code trim()} 后判空，因此纯空白字符串（如 {@code "   "}）视为未提供。
 *
 * <p>两者同时提供时的优先级由 {@code AbstractPlatformContext#findPlayer} 决定。
 * 真正的"查找玩家 + 发送 + 构造响应"由平台上下文内部完成——只有平台侧认识玩家类型。
 */
public final class SendPrivateMessageApi extends Api<PrivateMessagePayload, PrivateMessageResponse> {

    private final AbstractPlatformContext<?, ?, ?, ?> platformContext;

    public SendPrivateMessageApi(AbstractPlatformContext<?, ?, ?, ?> platformContext) {
        super(PrivateMessagePayload.class);
        this.platformContext = Objects.requireNonNull(platformContext, "platformContext");
    }

    @Override
    public String name() {
        return ProtocolConstants.Api.SEND_PRIVATE_MSG;
    }

    @Override
    protected PrivateMessageResponse doExecute(PrivateMessagePayload payload) throws ProtocolException {
        String nickname = payload.getNickname();
        String normalizedNickname = nickname == null ? null : nickname.trim();

        boolean nicknameMissing = normalizedNickname == null || normalizedNickname.isEmpty();
        if (nicknameMissing && payload.getUuid() == null) {
            PrivateMessageResponse response = PrivateMessageResponse.playerIsNull();
            throw ProtocolException.badRequest(response.getMessage(), response);
        }

        return platformContext.sendPrivateMessage(
                normalizedNickname, payload.getUuid(), payload.getMessage());
    }
}
