package io.github.theword.queqiao.core.command.builtin;

import io.github.theword.queqiao.core.command.CommandExecutionContext;
import io.github.theword.queqiao.core.command.CommandNode;
import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 强制重连全部客户端
 *
 * <p>重连业务逻辑由 {@link ReconnectCommand} 持有（它才有 WebsocketManager 依赖），
 * 本命令只负责以 {@code all = true} 触发它。
 *
 * <p><b>依赖通过构造器注入，而不是向上强转父节点</b>：此前实现是
 * {@code ((ReconnectCommand) getParent()).reconnect(...)}，这既引入 raw type 强转，
 * 又让命令树同时承担了 Service Locator 的角色。
 * 现在改为在装配时注入 {@code reconnectAllAction} —— 树只负责结构与路由。
 *
 * @param <NCS> Native Command Source，平台原生命令来源类型
 */
public class ReconnectAllCommand<NCS> extends CommandNode<NCS> {

    /**
     * 触发"强制重连全部客户端"的动作，由 {@link ReconnectCommand} 在装配时注入
     */
    private final Consumer<CommandExecutionContext<NCS>> reconnectAllAction;

    /**
     * @param logger             日志实现，不得为 null
     * @param reconnectAllAction 触发强制重连的动作，不得为 null
     */
    public ReconnectAllCommand(Logger logger, Consumer<CommandExecutionContext<NCS>> reconnectAllAction) {
        super(logger);
        this.reconnectAllAction = Objects.requireNonNull(reconnectAllAction, "reconnectAllAction");
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
     * @param context 本次命令调用的执行上下文
     * @param args    命令参数
     */
    @Override
    protected void onExecute(CommandExecutionContext<NCS> context, List<String> args) {
        reconnectAllAction.accept(context);
    }
}
