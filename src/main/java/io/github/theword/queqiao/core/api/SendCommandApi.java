package io.github.theword.queqiao.core.api;

import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.exception.protocol.ProtocolException;
import io.github.theword.queqiao.core.payload.EmptyPayload;
import org.slf4j.Logger;

/**
 * 发送命令（占位实现）
 *
 * <p>协议名：{@code send_command}。
 *
 * <p>该接口目前<b>不受支持</b>，无条件返回 500。保留它而不是不注册，
 * 是为了让客户端得到明确的"不支持"而不是 404 "未知 api"——两者的语义不同。
 *
 * <p>若使用方希望把该 api 表现为"不存在"，不注册它即可（走 404 分支）。
 *
 * <p>它既不依赖平台能力也不依赖任何 Runtime 服务，是 {@link Api} 中"纯协议壳"的形态；
 * 接收 {@link Logger} 只是为了让所有 Api 的构造方式保持一致。
 */
public final class SendCommandApi extends Api<EmptyPayload, Void> {

    public SendCommandApi(Logger logger) {
        super(EmptyPayload.class, logger);
    }

    @Override
    public String name() {
        return ProtocolConstants.Api.SEND_COMMAND;
    }

    @Override
    protected Void doExecute(EmptyPayload payload) throws ProtocolException {
        throw ProtocolException.internalError(ProtocolConstants.Message.SEND_COMMAND_UNSUPPORTED);
    }
}
