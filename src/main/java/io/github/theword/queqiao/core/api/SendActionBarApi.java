package io.github.theword.queqiao.core.api;

import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.exception.protocol.ProtocolException;
import io.github.theword.queqiao.core.payload.MessagePayload;
import io.github.theword.queqiao.core.platform.AbstractPlatformContext;
import org.slf4j.Logger;

/**
 * 发送 ActionBar
 *
 * <p>协议名：{@code send_actionbar}。
 *
 * <p>平台未实现时 {@code AbstractPlatformContext#sendActionBarComponent} 返回
 * {@code PlatformResultCode.UNSUPPORTED}，由 {@link Api#mapPlatformResult} 统一映射为 503
 * ——与迁移前直接抛出 503 的行为一致。
 */
public final class SendActionBarApi extends PlatformApi<MessagePayload, Void> {

    public SendActionBarApi(Logger logger, AbstractPlatformContext<?, ?, ?> platform) {
        super(MessagePayload.class, logger, platform);
    }

    @Override
    public String name() {
        return ProtocolConstants.Api.SEND_ACTIONBAR;
    }

    @Override
    protected Void doExecute(MessagePayload payload) throws ProtocolException {
        requireSuccess(platform.sendActionBar(payload.getMessage()));
        return null;
    }
}
