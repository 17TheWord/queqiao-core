package io.github.theword.queqiao.core.api;

import java.util.Collections;
import java.util.Set;

import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.exception.protocol.ProtocolException;
import io.github.theword.queqiao.core.payload.MessagePayload;
import io.github.theword.queqiao.core.platform.AbstractPlatformContext;
import io.github.theword.queqiao.core.platform.PlatformResult;
import org.slf4j.Logger;

/**
 * 广播消息
 *
 * <p>协议名：{@code broadcast}，别名 {@code send_msg}。
 *
 * <p>结果里的渲染文本由<b>平台</b>提供（只有平台认识自己的组件类型），
 * 本 Api 只用它打日志——{@code C} 不会离开平台层。
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
    protected Void doExecute(MessagePayload payload) throws ProtocolException {
        PlatformResult<String> result = platform.broadcast(payload.getMessage());

        String message = requireSuccess(result);
        logger.info("广播了一条消息：{}", message);

        return null;
    }
}
