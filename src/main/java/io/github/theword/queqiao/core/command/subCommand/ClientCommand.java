package io.github.theword.queqiao.core.command.subCommand;

import io.github.theword.queqiao.core.command.SubCommand;
import io.github.theword.queqiao.core.command.subCommand.client.ListCommand;
import io.github.theword.queqiao.core.command.subCommand.client.ReconnectCommand;
import io.github.theword.queqiao.core.config.Config;
import io.github.theword.queqiao.core.platform.AbstractPlatformContext;
import io.github.theword.queqiao.core.utils.WebsocketManager;
import org.slf4j.Logger;

public class ClientCommand extends SubCommand {

    public ClientCommand(
            AbstractPlatformContext<?, ?, ?, ?> platformContext,
            Logger logger,
            Config config,
            WebsocketManager websocketManager) {
        super(platformContext, logger);
        // 注册子命令
        addChild(new ListCommand(platformContext, logger, config, websocketManager));
        addChild(new ReconnectCommand(platformContext, logger, websocketManager));
    }

    /**
     * 获取命令名称
     *
     * @return client
     */
    @Override
    public String getName() {
        return "client";
    }

    /**
     * 获取命令描述
     *
     * @return Websocket Client 命令
     */
    @Override
    public String getDescription() {
        return "Websocket Client 命令";
    }


    /**
     * 执行命令
     *
     * <p>Pass
     *
     * @param commandReturner 命令执行者
     * @param args            命令参数
     */
    @Override
    protected void onExecute(Object commandReturner, java.util.List<String> args) {
        sendCommandTree(commandReturner, this);
    }
}
