package io.github.theword.queqiao.core.command;

import io.github.theword.queqiao.core.command.subCommand.ClientCommand;
import io.github.theword.queqiao.core.command.subCommand.HelpCommand;
import io.github.theword.queqiao.core.command.subCommand.ReloadCommand;
import io.github.theword.queqiao.core.command.subCommand.ServerCommand;
import io.github.theword.queqiao.core.config.Config;
import io.github.theword.queqiao.core.constant.BaseConstant;
import io.github.theword.queqiao.core.platform.AbstractPlatformContext;
import io.github.theword.queqiao.core.utils.WebsocketManager;
import org.slf4j.Logger;

import java.util.List;
import java.util.function.Consumer;

/**
 * 根命令抽象类
 *
 * <p>所有平台的根命令实现应继承此类
 * <p>在各平台的实现中注册所有一级子命令
 *
 * <p><b>装配说明</b>：根命令负责把子命令真正需要的窄依赖分发下去。
 * 其中 {@code websocketManager} 由 Runtime 在 {@code start()} 中创建，
 * 因此命令树必须在 Runtime 启动完成后再构建。
 *
 * @since 0.5.0
 */
public class RootCommand extends SubCommand {

    /**
     * 构造根命令
     *
     * @param platformContext 命令返回消息实现，不得为 null
     * @param logger               日志实现，不得为 null
     * @param config               配置运行时状态，不得为 null
     * @param websocketManager     WebSocket 管理器（须在 Runtime.start() 之后获取），不得为 null
     * @param reloadAction         触发 Runtime 重载的动作，入参为命令执行者，不得为 null
     */
    public RootCommand(
            AbstractPlatformContext<?, ?, ?, ?> platformContext,
            Logger logger,
            Config config,
            WebsocketManager websocketManager,
            Consumer<Object> reloadAction) {
        super(platformContext, logger);
        addChild(new HelpCommand(platformContext, logger));
        addChild(new ReloadCommand(platformContext, logger, reloadAction));
        addChild(new ServerCommand(platformContext, logger, config, websocketManager));
        addChild(new ClientCommand(platformContext, logger, config, websocketManager));
    }

    /**
     * 获取命令名称
     *
     * @return 命令名称（queqiao）
     */
    @Override
    public String getName() {
        return BaseConstant.COMMAND_HEADER;
    }

    /**
     * 获取命令描述
     *
     * @return 命令描述
     */
    @Override
    public String getDescription() {
        return "QueQiao Tool 主命令";
    }

    /**
     * 执行命令
     *
     * @param commandReturner 命令执行者
     * @param args            命令参数
     */
    @Override
    protected void onExecute(Object commandReturner, List<String> args) {
        platformContext.returnCallBackMessage(
                commandReturner, "请使用帮助命令查看可用子命令：" + BaseConstant.COMMAND_HEADER + " help"
        );
    }
}
