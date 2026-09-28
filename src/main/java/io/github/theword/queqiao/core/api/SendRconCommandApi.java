package io.github.theword.queqiao.core.api;

import java.util.Objects;

import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.exception.protocol.ProtocolException;
import io.github.theword.queqiao.core.exception.rcon.RconException;
import io.github.theword.queqiao.core.payload.CommandPayload;
import io.github.theword.queqiao.core.protocol.RconCommandExecutor;
import io.github.theword.queqiao.core.response.RconCommandError;
import org.slf4j.Logger;

/**
 * 通过 RCON 执行命令
 *
 * <p>协议名：{@code send_rcon_command}。
 *
 * <p>该 Api 与平台无关——它只依赖 Runtime 注入的 RCON 执行器。
 *
 * <p>状态码语义（不同失败原因不统一归为 400）：
 * <ul>
 *     <li>命令为空 → 400（调用方请求不合法）</li>
 *     <li>Rcon 未启用 / 未连接 → 503（服务暂不可用）</li>
 *     <li>命令已下发但执行失败 → 500（服务端错误）</li>
 * </ul>
 *
 * <p>日志：不在 INFO 级别记录完整命令内容，因为命令可能包含密码、token 或玩家隐私；
 * 完整内容仅在显式开启 debug 时输出。
 */
public final class SendRconCommandApi extends Api<CommandPayload, String> {

    private final RconCommandExecutor rconCommandExecutor;
    private final Logger logger;

    public SendRconCommandApi(RconCommandExecutor rconCommandExecutor, Logger logger) {
        super(CommandPayload.class);
        this.rconCommandExecutor = Objects.requireNonNull(rconCommandExecutor, "rconCommandExecutor");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String name() {
        return ProtocolConstants.Api.SEND_RCON_COMMAND;
    }

    @Override
    protected String doExecute(CommandPayload payload) throws ProtocolException {
        String command = payload.getCommand();
        if (command == null || command.trim().isEmpty()) {
            this.logger.warn("收到空的 Rcon 命令，已拒绝");
            throw ProtocolException.badRequest(ProtocolConstants.Message.RCON_COMMAND_EMPTY);
        }

        try {
            String result = this.rconCommandExecutor.execute(command);
            this.logger.info("已通过 Rcon 执行命令（长度 {}）", command.length());
            if (this.logger.isDebugEnabled()) {
                this.logger.debug("Rcon 命令内容（可能含敏感信息，仅 debug 级别输出）：{}", command);
            }
            return result;
        } catch (RconException e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : ProtocolConstants.Message.FAILED;
            RconCommandError errorData = new RconCommandError(command, errorMessage);

            if (e.getKind() == RconException.Kind.INVALID_COMMAND) {
                throw ProtocolException.badRequest(errorMessage, errorData);
            }

            if (e.getKind() == RconException.Kind.DISABLED || e.getKind() == RconException.Kind.DISCONNECTED) {
                this.logger.warn("Rcon 暂不可用（{}）：{}", e.getKind(), errorMessage);
                throw ProtocolException.serviceUnavailable(errorMessage, errorData);
            }

            this.logger.warn("Rcon 命令执行失败：{}", errorMessage);
            throw ProtocolException.internalError(errorMessage, errorData);
        }
    }
}
