package io.github.theword.queqiao.core.protocol.handler;

import io.github.theword.queqiao.core.handle.HandleApiService;
import io.github.theword.queqiao.core.payload.MessagePayload;
import io.github.theword.queqiao.core.protocol.AbstractProtocolHandler;
import org.slf4j.Logger;

public class SendActionBarHandler extends AbstractProtocolHandler<MessagePayload, Void> {
    public SendActionBarHandler(String apiName, Logger logger, HandleApiService handleApiService) {
        super(apiName, logger, handleApiService, MessagePayload.class);
    }

    @Override
    protected Void handlePayload(MessagePayload payload) {
        this.handleApiService.handleSendActionBarMessage(payload.getMessage());
        return null;
    }
}
