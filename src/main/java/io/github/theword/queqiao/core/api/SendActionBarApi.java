package io.github.theword.queqiao.core.api;

import java.util.Objects;

import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.exception.protocol.ProtocolException;
import io.github.theword.queqiao.core.payload.MessagePayload;
import io.github.theword.queqiao.core.platform.AbstractPlatformContext;

/**
 * 发送 ActionBar
 *
 * <p>协议名：{@code send_actionbar}。
 *
 * <p>平台未实现 ActionBar 时，{@code AbstractPlatformContext#sendActionBarComponent}
 * 会抛出 503。
 */
public final class SendActionBarApi extends Api<MessagePayload, Void> {

    private final AbstractPlatformContext<?, ?, ?, ?> platformContext;

    public SendActionBarApi(AbstractPlatformContext<?, ?, ?, ?> platformContext) {
        super(MessagePayload.class);
        this.platformContext = Objects.requireNonNull(platformContext, "platformContext");
    }

    @Override
    public String name() {
        return ProtocolConstants.Api.SEND_ACTIONBAR;
    }

    @Override
    protected Void doExecute(MessagePayload payload) throws ProtocolException {
        platformContext.sendActionBar(payload.getMessage());
        return null;
    }
}
