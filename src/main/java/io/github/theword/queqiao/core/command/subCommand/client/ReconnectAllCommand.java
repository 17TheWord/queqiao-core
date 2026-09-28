package io.github.theword.queqiao.core.command.subCommand.client;

import io.github.theword.queqiao.core.command.SubCommand;
import io.github.theword.queqiao.core.platform.AbstractPlatformContext;
import org.slf4j.Logger;

import java.util.List;

public class ReconnectAllCommand extends SubCommand {

    public ReconnectAllCommand(AbstractPlatformContext<?, ?, ?, ?> platformContext, Logger logger) {
        super(platformContext, logger);
    }

    /**
     * 获取命令名称
     *
     * @return all
     */
    @Override
    public String getName() {
        return "all";
    }

    /**
     * 获取命令描述
     *
     * @return 重连所有客户端
     */
    @Override
    public String getDescription() {
        return "强制重连所有客户端";
    }

    /**
     * 执行命令
     *
     * <p>重连业务逻辑由父命令 {@link ReconnectCommand} 持有（它才有 Manager 依赖），
     * 本命令只负责以 {@code all = true} 触发它。
     *
     * @param commandReturner 命令执行者
     * @param args            命令参数
     */
    @Override
    protected void onExecute(Object commandReturner, List<String> args) {
        SubCommand parent = getParent();
        if (parent instanceof ReconnectCommand) {
            ((ReconnectCommand) parent).reconnect(commandReturner, true);
            return;
        }
        logger.warn("ReconnectAllCommand 未挂载到 ReconnectCommand 之下，本次执行已忽略");
    }
}
