package io.github.theword.queqiao.core.command.builtin;

import io.github.theword.queqiao.core.command.CommandExecutionContext;
import io.github.theword.queqiao.core.command.CommandNode;
import io.github.theword.queqiao.core.constant.CommandConstant;
import io.github.theword.queqiao.core.runtime.ReloadResult;
import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 重载命令
 *
 * <p>本命令只依赖一个"重载动作"回调，而不直接持有 {@code QueQiaoRuntime}：
 * 重载是 Runtime 的生命周期操作，回调由 Runtime 侧提供（如 {@code runtime::reload}），
 * 命令层因此不必反向依赖 Runtime 类型，也不必通过 Runtime 的 getter 取用其它服务。
 *
 * <p><b>回执职责在本命令</b>：Runtime 只返回 {@link ReloadResult}（不自己回执），
 * 由本命令决定如何把进度消息展示给调用者。这样 Runtime 与 WebsocketManager
 * 都不需要知道命令上下文的存在。
 */
public class ReloadCommand<NCS> extends CommandNode<NCS> {

    /**
     * 触发 Runtime 重载的动作，返回本次重载的进度消息
     */
    private final Supplier<ReloadResult> reloadAction;

    public ReloadCommand(
            Logger logger,
            Supplier<ReloadResult> reloadAction) {
        super(logger);
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
     * <p>先逐条回显 Runtime 返回的进度消息，再回显本命令自己的完成提示——
     * 与迁移前的消息顺序保持一致。
     *
     * @param context 命令执行上下文
     * @param args   命令参数
     */
    @Override
    protected void onExecute(CommandExecutionContext<NCS> context, List<String> args) {
        ReloadResult result = reloadAction.get();
        for (String message : result.getMessages()) {
            context.reply(message);
        }
        context.reply(CommandConstant.RELOAD_CONFIG);
    }
}
