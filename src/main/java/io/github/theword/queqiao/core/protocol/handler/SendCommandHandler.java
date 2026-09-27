package io.github.theword.queqiao.core.protocol.handler;

import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.exception.protocol.ProtocolException;
import io.github.theword.queqiao.core.payload.EmptyPayload;
import io.github.theword.queqiao.core.handle.HandleApiService;
import io.github.theword.queqiao.core.protocol.AbstractProtocolHandler;
import org.slf4j.Logger;

public class SendCommandHandler extends AbstractProtocolHandler<EmptyPayload, Void> {

    public SendCommandHandler(String apiName, Logger logger, HandleApiService handleApiService) {
        super(apiName, logger, handleApiService, EmptyPayload.class);
    }

    @Override
    protected Void handlePayload(EmptyPayload payload) throws ProtocolException {
        throw ProtocolException.internalError(ProtocolConstants.Message.SEND_COMMAND_UNSUPPORTED);
    }
}
