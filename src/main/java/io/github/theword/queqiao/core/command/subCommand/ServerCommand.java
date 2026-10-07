package io.github.theword.queqiao.core.command.subCommand;

import io.github.theword.queqiao.core.command.SubCommand;
import io.github.theword.queqiao.core.command.subCommand.server.InfoCommand;
import io.github.theword.queqiao.core.config.Config;
import io.github.theword.queqiao.core.platform.CommandSource;
import io.github.theword.queqiao.core.utils.WebsocketManager;
import org.slf4j.Logger;

import java.util.List;

public class ServerCommand extends SubCommand {

    public ServerCommand(
            Logger logger,
            Config config,
            WebsocketManager websocketManager) {
        super(logger);
        addChild(new InfoCommand(logger, config, websocketManager));
    }

    /**
     * 获取命令名称
     *
     * @return server
     */
    @Override
    public String getName() {
        return "server";
    }

    /**
     * 获取命令描述
     *
     * @return Websocket Server 命令
     */
    @Override
    public String getDescription() {
        return "Websocket Server 命令";
    }


    /**
     * 执行命令
     *
     * <p>位于本命令 pass
     *
     * @param source 命令来源
     * @param args            命令参数
     */
    @Override
    protected void onExecute(CommandSource source, List<String> args) {
        sendCommandTree(source, this);
    }
}
