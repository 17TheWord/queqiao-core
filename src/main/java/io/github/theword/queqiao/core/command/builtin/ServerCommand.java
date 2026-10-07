package io.github.theword.queqiao.core.command.builtin;

import io.github.theword.queqiao.core.command.CommandExecutionContext;
import io.github.theword.queqiao.core.command.CommandNode;
import io.github.theword.queqiao.core.config.Config;
import io.github.theword.queqiao.core.utils.WebsocketManager;
import org.slf4j.Logger;

import java.util.List;

public class ServerCommand<NCS> extends CommandNode<NCS> {

    public ServerCommand(
            Logger logger,
            Config config,
            WebsocketManager websocketManager) {
        super(logger);
        addChild(new InfoCommand<>(logger, config, websocketManager));
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
     * @param context 命令执行上下文
     * @param args            命令参数
     */
    @Override
    protected void onExecute(CommandExecutionContext<NCS> context, List<String> args) {
        sendCommandTree(context, this);
    }
}
