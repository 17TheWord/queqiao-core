package io.github.theword.queqiao.core.command.builtin;

import io.github.theword.queqiao.core.command.CommandExecutionContext;
import io.github.theword.queqiao.core.command.CommandNode;
import io.github.theword.queqiao.core.config.ConfigKeys;
import io.github.theword.queqiao.core.config.Config;
import io.github.theword.queqiao.core.constant.CommandConstant;
import io.github.theword.queqiao.core.utils.Tool;
import io.github.theword.queqiao.core.utils.WebsocketManager;
import io.github.theword.queqiao.core.websocket.WsServer;
import org.java_websocket.WebSocket;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public class InfoCommand<NCS> extends CommandNode<NCS> {

    /**
     * 配置运行时状态供应商（与 Runtime 共用同一实例，reload 后自动生效）
     */
    private final Supplier<Config> configSupplier;

    /**
     * WebSocket 管理器供应商（命令执行时才取值，因此命令树可在 Runtime 启动前构建）
     */
    private final Supplier<WebsocketManager> websocketSupplier;

    public InfoCommand(
            Logger logger,
            Supplier<Config> configSupplier,
            Supplier<WebsocketManager> websocketSupplier) {
        super(logger);
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.websocketSupplier = Objects.requireNonNull(websocketSupplier, "websocketSupplier");
    }

    /**
     * 获取命令名称
     *
     * @return info
     */
    @Override
    public String getName() {
        return "info";
    }

    /**
     * 获取命令描述
     *
     * @return 获取 Websocket Server 信息
     */
    @Override
    public String getDescription() {
        return "获取 Websocket Server 信息";
    }

    /**
     * 获取 WebSocket 服务端状态 整合游戏内命令调用
     *
     * <p><b>连接集合只读取一次</b>：此前 {@code getConnections()} 被调用三次
     * （判空 / 取数量 / 遍历），三次之间集合可能变化，
     * 会出现"显示 N 个连接、却列出 M 条"的自相矛盾输出。
     *
     * @param context 命令执行上下文
     * @param args            命令参数
     */
    @Override
    protected void onExecute(CommandExecutionContext<NCS> context, List<String> args) {
        Config config = configSupplier.get();
        WebsocketManager websocketManager = websocketSupplier.get();
        if (config == null || websocketManager == null) {
            context.reply(CommandConstant.RUNTIME_NOT_READY);
            return;
        }

        if (!config.get(ConfigKeys.WebSocket.ENABLE)) {
            context.reply("Websocket Server 配置项未启用，如需开启，请在 config.yml 中启用 WebsocketServer 配置项");
            context.reply(Tool.format("配置项中地址为 {}:{}", config.get(ConfigKeys.WebSocket.HOST), config.get(ConfigKeys.WebSocket.PORT)));
            return;
        }

        WsServer wsServer = websocketManager.getWsServer();
        if (wsServer == null) {
            context.reply("Websocket Server 为 null，查询失败");
            return;
        }

        context.reply(Tool.format(
                        "当前 Websocket Server 已开启，监听地址为 {}:{}",
                        wsServer.getAddress().getHostString(),
                        wsServer.getPort()));

        // 取一次快照后统一使用，避免三次读取之间集合变化导致输出自相矛盾
        List<WebSocket> connections = new ArrayList<>(wsServer.getConnections());
        if (connections.isEmpty()) {
            context.reply("当前暂无 Websocket 连接到该 Server");
            return;
        }

        context.reply(Tool.format("当前 Websocket Server 已有 {} 个连接", connections.size()));

        int count = 0;
        for (WebSocket webSocket : connections) {
            count++;
            InetSocketAddress remoteAddress = webSocket.getRemoteSocketAddress();
            if (remoteAddress == null) {
                context.reply(Tool.format("{} 来自未知地址的连接", count));
            } else {
                context.reply(Tool.format("{} 来自 {}:{} 的连接", count, remoteAddress.getHostString(), remoteAddress.getPort()));
            }
        }
    }
}
