package io.github.theword.queqiao.core.command.builtin;

import io.github.theword.queqiao.core.command.CommandExecutionContext;
import io.github.theword.queqiao.core.command.CommandNode;
import io.github.theword.queqiao.core.config.Config;
import io.github.theword.queqiao.core.utils.WebsocketManager;
import org.slf4j.Logger;

public class ClientCommand<NCS> extends CommandNode<NCS> {

    public ClientCommand(
            Logger logger,
            Config config,
            WebsocketManager websocketManager) {
        super(logger);
        // 注册子命令
        addChild(new ListCommand<>(logger, config, websocketManager));
        addChild(new ReconnectCommand<>(logger, websocketManager));
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
     * @param context 命令执行上下文
     * @param args            命令参数
     */
    @Override
    protected void onExecute(CommandExecutionContext<NCS> context, java.util.List<String> args) {
        sendCommandTree(context, this);
    }
}
