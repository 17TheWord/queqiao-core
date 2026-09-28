package io.github.theword.queqiao.core.command.subCommand;

import io.github.theword.queqiao.core.command.SubCommand;
import io.github.theword.queqiao.core.platform.AbstractPlatformContext;
import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 重载命令
 *
 * <p>本命令只依赖一个"重载动作"回调，而不直接持有 {@code QueQiaoRuntime}：
 * 重载是 Runtime 的生命周期操作，回调由 Runtime 侧提供（如 {@code runtime::reload}），
 * 命令层因此不必反向依赖 Runtime 类型，也不必通过 Runtime 的 getter 取用其它服务。
 */
public class ReloadCommand extends SubCommand {

    /**
     * 触发 Runtime 重载的动作，入参为命令执行者
     */
    private final Consumer<Object> reloadAction;

    public ReloadCommand(
            AbstractPlatformContext<?, ?, ?, ?> platformContext,
            Logger logger,
            Consumer<Object> reloadAction) {
        super(platformContext, logger);
        this.reloadAction = Objects.requireNonNull(reloadAction, "reloadAction");
    }

    /**
     * 获取命令名称
     *
     * @return reload
     */
    @Override
    public String getName() {
        return "reload";
    }

    /**
     * 获取命令描述
     *
     * @return 重载配置文件并重新连接所有 Websocket Client
     */
    @Override
    public String getDescription() {
        return "重载配置文件并重新连接所有 Websocket Client";
    }

    /**
     * 重载 WebSocket reload 命令调用
     *
     * @param commandReturner 命令执行者
     * @param args            命令参数
     */
    @Override
    protected void onExecute(Object commandReturner, List<String> args) {
        reloadAction.accept(commandReturner);
    }
}
