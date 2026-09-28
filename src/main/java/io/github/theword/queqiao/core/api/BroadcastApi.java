package io.github.theword.queqiao.core.api;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.payload.MessagePayload;
import io.github.theword.queqiao.core.platform.AbstractPlatformContext;

/**
 * 广播消息
 *
 * <p>协议名：{@code broadcast}，别名 {@code send_msg}。
 */
public final class BroadcastApi extends Api<MessagePayload, Void> {

    private final AbstractPlatformContext<?, ?, ?, ?> platformContext;

    public BroadcastApi(AbstractPlatformContext<?, ?, ?, ?> platformContext) {
        super(MessagePayload.class);
        this.platformContext = Objects.requireNonNull(platformContext, "platformContext");
    }

    @Override
    public String name() {
        return ProtocolConstants.Api.BROADCAST;
    }

    @Override
    public Set<String> aliases() {
        return Collections.singleton(ProtocolConstants.Api.SEND_MSG);
    }

    @Override
    protected Void doExecute(MessagePayload payload) {
        platformContext.broadcast(payload.getMessage());
        return null;
    }
}
