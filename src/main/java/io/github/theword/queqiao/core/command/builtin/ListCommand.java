package io.github.theword.queqiao.core.command.builtin;

import io.github.theword.queqiao.core.command.CommandExecutionContext;
import io.github.theword.queqiao.core.command.CommandNode;
import io.github.theword.queqiao.core.config.ConfigKeys;
import io.github.theword.queqiao.core.config.Config;
import io.github.theword.queqiao.core.constant.CommandConstant;
import io.github.theword.queqiao.core.utils.Tool;
import io.github.theword.queqiao.core.utils.WebsocketManager;
import io.github.theword.queqiao.core.websocket.WsClient;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public class ListCommand<NCS> extends CommandNode<NCS> {

    /**
     * 配置运行时状态供应商（与 Runtime 共用同一实例，reload 后自动生效）
     */
    private final Supplier<Config> configSupplier;

    /**
     * WebSocket 管理器供应商（命令执行时才取值，因此命令树可在 Runtime 启动前构建）
     */
    private final Supplier<WebsocketManager> websocketSupplier;

    public ListCommand(
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
     * @return list
     */
    @Override
    public String getName() {
        return "list";
    }

    /**
     * 获取命令描述
     *
     * @return 获取当前 Websocket Client 列表
     */
    @Override
    public String getDescription() {
        return "获取当前 Websocket Client 列表";
    }

    /**
     * 获取 WebSocket 客户端状态 整合游戏内命令调用
     *
     * <p>两处修正：
     * <ol>
     *     <li>配置的 URL 列表<b>只读取一次</b>——此前在循环条件里反复调用
     *         {@code getConfig().getWebsocketClient().getUrlList()}（每轮两次），
     *         并发 reload 时甚至可能中途换掉列表</li>
     *     <li>编号<b>统一从 1 开始</b>——此前"未启用"分支用 {@code i + 1}（从 1 起），
     *         而"已启用"分支用 {@code i}（从 0 起），同一条命令的编号规则随配置变化</li>
     * </ol>
     *
     * @param context 命令执行上下文
     * @param args            命令参数
     * @since 0.1.5
     */
    @Override
    protected void onExecute(CommandExecutionContext<NCS> context, List<String> args) {
        Config config = configSupplier.get();
        WebsocketManager websocketManager = websocketSupplier.get();
        if (config == null || websocketManager == null) {
            context.reply(CommandConstant.RUNTIME_NOT_READY);
            return;
        }

        if (!config.get(ConfigKeys.WebSocketClient.ENABLE)) {
            List<String> urlList = new ArrayList<>(config.get(ConfigKeys.WebSocketClient.URL_LIST));
            context.reply("Websocket Client 配置项未启用，如需开启，请在 config.yml 中启用 WebsocketClient 配置项");
            context.reply(Tool.format("配置文件中连接列表如下共 {} 个 Client", urlList.size()));
            for (int i = 0; i < urlList.size(); i++) {
                context.reply(Tool.format("{} 连接至 {}", i + 1, urlList.get(i)));
            }
            return;
        }

        List<WsClient> wsClientList = websocketManager.getWsClientList();

        context.reply(Tool.format("Websocket Client 列表，共 {} 个 Client", wsClientList.size()));

        for (int i = 0; i < wsClientList.size(); i++) {
            WsClient wsClient = wsClientList.get(i);
            context.reply(Tool.format(
                            "{} 连接至 {} 的 Client，状态：{}",
                            i + 1,
                            wsClient.getURI(),
                            wsClient.isOpen() ? "已连接" : "未连接"));
        }
    }
}
