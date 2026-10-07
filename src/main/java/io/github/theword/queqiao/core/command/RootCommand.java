package io.github.theword.queqiao.core.command;

import io.github.theword.queqiao.core.command.builtin.ClientCommand;
import io.github.theword.queqiao.core.command.builtin.HelpCommand;
import io.github.theword.queqiao.core.command.builtin.ReloadCommand;
import io.github.theword.queqiao.core.command.builtin.ServerCommand;
import io.github.theword.queqiao.core.config.Config;
import io.github.theword.queqiao.core.constant.BaseConstant;
import io.github.theword.queqiao.core.runtime.ReloadResult;
import io.github.theword.queqiao.core.utils.WebsocketManager;
import org.slf4j.Logger;

import java.util.List;
import java.util.function.Supplier;

/**
 * QueQiao 内置根命令节点
 *
 * <p>命令树的根：名字固定为 {@code queqiao}，在构造时装配 QueQiao 的全部一级内置子命令
 * （{@code help} / {@code reload} / {@code server} / {@code client}）。
 *
 * <p><b>不是给平台继承的</b>：平台适配器不继承本类，而是通过 {@link CommandRouter}
 * 拿到本节点、再把它映射到平台自己的命令 API（如 Brigadier 的
 * {@code LiteralArgumentBuilder}）。
 *
 * <p><b>装配说明</b>：根节点负责把子命令真正需要的窄依赖分发下去。
 * 其中 {@code websocketManager} 由 Runtime 在 {@code start()} 中创建，
 * 因此命令树必须在 Runtime 启动完成后再构建。
 *
 * @since 0.5.0
 */
public class RootCommand<NCS> extends CommandNode<NCS> {

    /**
     * 构造根命令
     *
     * @param logger           日志实现，不得为 null
     * @param config           配置运行时状态，不得为 null
     * @param websocketManager WebSocket 管理器（须在 Runtime.start() 之后获取），不得为 null
     * @param reloadAction     触发 Runtime 重载的动作，返回重载结果，不得为 null
     */
    public RootCommand(
            Logger logger,
            Config config,
            WebsocketManager websocketManager,
            Supplier<ReloadResult> reloadAction) {
        super(logger);
        addChild(new HelpCommand<>(logger));
        addChild(new ReloadCommand<>(logger, reloadAction));
        addChild(new ServerCommand<>(logger, config, websocketManager));
        addChild(new ClientCommand<>(logger, config, websocketManager));
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
     * @param context 命令执行上下文
     * @param args            命令参数
     */
    @Override
    protected void onExecute(CommandExecutionContext<NCS> context, List<String> args) {
        context.reply("请使用帮助命令查看可用子命令：" + BaseConstant.COMMAND_HEADER + " help"
        );
    }
}
