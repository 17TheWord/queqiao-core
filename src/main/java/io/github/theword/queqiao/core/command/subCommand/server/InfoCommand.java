package io.github.theword.queqiao.core.command.subCommand.server;

import io.github.theword.queqiao.core.command.SubCommand;
import io.github.theword.queqiao.core.config.ConfigKeys;
import io.github.theword.queqiao.core.config.Config;
import io.github.theword.queqiao.core.platform.CommandSource;
import io.github.theword.queqiao.core.utils.Tool;
import io.github.theword.queqiao.core.utils.WebsocketManager;
import io.github.theword.queqiao.core.websocket.WsServer;
import org.java_websocket.WebSocket;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class InfoCommand extends SubCommand {

    /**
     * 配置运行时状态（与 Runtime 共用同一实例，reload 后自动生效）
     */
    private final Config config;

    /**
     * WebSocket 管理器（Runtime 启动后注入）
     */
    private final WebsocketManager websocketManager;

    public InfoCommand(
            Logger logger,
            Config config,
            WebsocketManager websocketManager) {
        super(logger);
        this.config = Objects.requireNonNull(config, "config");
        this.websocketManager = Objects.requireNonNull(
                websocketManager, "websocketManager 不能为 null：命令树须在 Runtime.start() 之后构建");
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
     * @param source 命令来源
     * @param args            命令参数
     */
    @Override
    protected void onExecute(CommandSource source, List<String> args) {
        if (!config.get(ConfigKeys.WebSocket.ENABLE)) {
            source.reply("Websocket Server 配置项未启用，如需开启，请在 config.yml 中启用 WebsocketServer 配置项");
            source.reply(Tool.format("配置项中地址为 {}:{}", config.get(ConfigKeys.WebSocket.HOST), config.get(ConfigKeys.WebSocket.PORT)));
            return;
        }

        WsServer wsServer = websocketManager.getWsServer();
        if (wsServer == null) {
            source.reply("Websocket Server 为 null，查询失败");
            return;
        }

        source.reply(Tool.format(
                        "当前 Websocket Server 已开启，监听地址为 {}:{}",
                        wsServer.getAddress().getHostString(),
                        wsServer.getPort()));

        // 取一次快照后统一使用，避免三次读取之间集合变化导致输出自相矛盾
        List<WebSocket> connections = new ArrayList<>(wsServer.getConnections());
        if (connections.isEmpty()) {
            source.reply("当前暂无 Websocket 连接到该 Server");
            return;
        }

        source.reply(Tool.format("当前 Websocket Server 已有 {} 个连接", connections.size()));

        int count = 0;
        for (WebSocket webSocket : connections) {
            count++;
            InetSocketAddress remoteAddress = webSocket.getRemoteSocketAddress();
            if (remoteAddress == null) {
                source.reply(Tool.format("{} 来自未知地址的连接", count));
            } else {
                source.reply(Tool.format("{} 来自 {}:{} 的连接", count, remoteAddress.getHostString(), remoteAddress.getPort()));
            }
        }
    }
}
