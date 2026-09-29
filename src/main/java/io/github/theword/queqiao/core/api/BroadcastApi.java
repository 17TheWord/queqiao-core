package io.github.theword.queqiao.core.api;

import java.util.Collections;
import java.util.Set;

import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.payload.MessagePayload;
import io.github.theword.queqiao.core.platform.AbstractPlatformContext;
import org.slf4j.Logger;

/**
 * 广播消息
 *
 * <p>协议名：{@code broadcast}，别名 {@code send_msg}。
 */
public final class BroadcastApi extends PlatformApi<MessagePayload, Void> {

    public BroadcastApi(Logger logger, AbstractPlatformContext<?, ?, ?, ?> platform) {
        super(MessagePayload.class, logger, platform);
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
        platform.broadcast(payload.getMessage());
        return null;
    }
}
