package io.github.theword.queqiao.core.command.builtin;

import io.github.theword.queqiao.core.command.CommandExecutionContext;
import io.github.theword.queqiao.core.command.CommandNode;
import io.github.theword.queqiao.core.constant.CommandConstant;
import io.github.theword.queqiao.core.utils.Tool;
import io.github.theword.queqiao.core.utils.WebsocketManager;
import io.github.theword.queqiao.core.websocket.WsClient;
import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;

public class ReconnectCommand<NCS> extends CommandNode<NCS> {

    /**
     * WebSocket 管理器（Runtime 启动后注入）
     */
    private final WebsocketManager websocketManager;

    public ReconnectCommand(
            Logger logger,
            WebsocketManager websocketManager) {
        super(logger);
        this.websocketManager = Objects.requireNonNull(
                websocketManager, "websocketManager 不能为 null：命令树须在 Runtime.start() 之后构建");
        addChild(new ReconnectAllCommand<>(logger, context -> reconnect(context, true)));
    }

    /**
     * 获取命令名称
     *
     * @return reconnect
     */
    @Override
    public String getName() {
        return "reconnect";
    }

    /**
     * 获取命令描述
     *
     * @return 重新连接 Websocket Clients
     */
    @Override
    public String getDescription() {
        return "重新连接断开的 Websocket Clients";
    }

    /**
     * 获取命令用法（添加参数说明）
     *
     * @return 使用说明
     */
    @Override
    public String getUsage() {
        return getFullPath();
    }

    /**
     * 重连 WebSocket 客户端 reconnect 命令调用
     *
     * @param context 命令执行上下文
     * @param args            命令参数
     */
    @Override
    protected void onExecute(CommandExecutionContext<NCS> context, List<String> args) {
        reconnect(context, false);
    }

    /**
     * 重连 WebSocket 客户端
     *
     * <p><b>{@code all} 的语义</b>：
     * <ul>
     *     <li>{@code false} —— 只重连<b>未打开</b>的客户端（{@code /queqiao client reconnect}）</li>
     *     <li>{@code true} —— <b>强制</b>重连全部客户端，含当前健康的连接
     *         （{@code /queqiao client reconnect all}），会主动断开再重建</li>
     * </ul>
     *
     * <p><b>注意</b>：本方法只是把重连任务<b>投递到调度器</b>，并不保证连接成功，
     * 因此结束提示为"已安排重连"而非"已重新连接"；真正的结果由
     * {@code WsClient} 在 {@code onOpen} / {@code onClose} 中记录到日志。
     *
     * <p><b>API 变更</b>：本方法此前是 {@code public static}，依赖全局上下文获取
     * 命令返回消息实现与 Manager。改为实例方法后依赖由构造器注入，
     * 平台侧不能再以 {@code ReconnectCommand.reconnect(...)} 静态调用。
     *
     * @param context 命令执行上下文
     * @param all             是否强制重连全部客户端
     */
    public void reconnect(CommandExecutionContext<NCS> context, boolean all) {
        context.reply(all ? CommandConstant.RECONNECT_ALL_CLIENT : CommandConstant.RECONNECT_NOT_OPEN_CLIENT);

        List<WsClient> wsClientList = websocketManager.getWsClientList();

        int alreadyOpenCount = 0;
        for (WsClient wsClient : wsClientList) {
            if (!all && wsClient.isOpen()) {
                alreadyOpenCount++;
                continue;
            }
            wsClient.reconnectNow();
            context.reply(Tool.format(CommandConstant.RECONNECT_MESSAGE, wsClient.getURI()));
        }

        if (alreadyOpenCount == wsClientList.size()) {
            context.reply(CommandConstant.RECONNECT_NO_CLIENT_NEED_RECONNECT);
        }
        context.reply(CommandConstant.RECONNECTED);
    }
}
