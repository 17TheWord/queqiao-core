package io.github.theword.queqiao.core.utils;

import io.github.theword.queqiao.core.config.ConfigKeys;
import io.github.theword.queqiao.core.config.Config;
import io.github.theword.queqiao.core.constant.WebsocketConstantMessage;
import io.github.theword.queqiao.core.event.base.BaseEvent;
import io.github.theword.queqiao.core.platform.CommandSource;
import io.github.theword.queqiao.core.handle.HandleProtocolMessage;
import io.github.theword.queqiao.core.websocket.ReconnectPolicy;
import io.github.theword.queqiao.core.websocket.WebSocketUrlNormalizer;
import io.github.theword.queqiao.core.websocket.WsClient;
import io.github.theword.queqiao.core.websocket.WsServer;
import com.google.gson.Gson;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket 生命周期管理器
 *
 * <p>所有权模型（单向、不允许跨层 shutdown）：
 * <pre>
 * WebsocketManager
 *     owns  →  reconnectScheduler（共享重连调度器）
 * WsClient
 *     owns  →  reconnectFuture / generation / retry state / reconnectInProgress
 * Java-WebSocket
 *     owns  →  socket / read thread / write thread / connectionLostChecker
 * </pre>
 *
 * <p>调度器在构造阶段创建（{@link ScheduledThreadPoolExecutor} 的 core 线程在首次提交任务时才启动，
 * 因此即使未启用 Client 也无实际线程开销），只在 {@link #stop(int, String, CommandSource)} 这一永久销毁路径
 * 关闭；{@code stopClients()} / {@code restartClients()} 绝不触碰它。
 *
 * @since 0.6.11
 */
public class WebsocketManager {

    /**
     * 共享重连调度器核心线程数
     *
     * <p>取 2 而非 1：{@code WebSocketClient.reconnect()} 内部会走 {@code reset() → closeBlocking()}，
     * 而 {@code closeBlocking()} 没有超时。单 worker 会被一个卡住的 Client 拖住，
     * 导致其它 Client 的重连全部停滞。
     */
    private static final int RECONNECT_SCHEDULER_CORE_POOL_SIZE = 2;

    /**
     * 正常关闭使用的关闭码
     */
    private static final int CLOSE_CODE_NORMAL = 1000;

    /**
     * 重连调度器线程名前缀
     */
    private static final String RECONNECT_THREAD_NAME_PREFIX = "QueQiao-WebSocket-Reconnect-";

    private final Object lifecycleLock = new Object();
    private final List<WsClient> wsClientList;
    private volatile WsServer wsServer;
    private final Logger logger;
    private final Gson gson;

    /**
     * 协议分发入口，由 QueQiaoRuntime 创建并注入
     *
     * <p>所有 Client 与 Server 共用同一实例：该对象构造后不可变、无每请求状态，
     * 可安全并发使用。详见 {@link HandleProtocolMessage}。
     */
    private final HandleProtocolMessage handleProtocolMessage;

    /**
     * 当前配置快照
     *
     * <p>由 {@code QueQiaoRuntime} 注入；reload 时通过 {@link #restart(Config, CommandSource)} 整体替换。
     * 标记 {@code volatile}：reload 线程写入，WebSocket 读写线程与游戏线程读取。
     *
     * <p>配置与 Runtime 作用域辅助能力均由外部注入，
     * 因此可以脱离全局上下文独立构造与测试。
     */
    private volatile Config config;

    /**
     * Runtime 作用域辅助能力，由 QueQiaoRuntime 注入
     *
     * <p>本类与 {@link WsClient} 的 debug 日志统一通过它输出，
     * 不再依赖任何静态全局状态。
     */
    private final RuntimeUtils utils;

    /**
     * 共享重连调度器：本 Manager 独占持有，Client 只使用不销毁
     */
    private final ScheduledThreadPoolExecutor reconnectScheduler;

    /**
     * 是否已启动（保证 {@link #start(CommandSource)} 幂等）
     */
    private boolean started = false;

    /**
     * 是否已永久销毁（保证 {@link #stop(int, String, CommandSource)} 幂等）
     */
    private boolean destroyed = false;

    /**
     * 构造 WebSocket 生命周期管理器
     *
     * @param logger                          日志实现
     * @param gson                            Gson 实例
     * @param handleProtocolMessage           协议分发入口（由 QueQiaoRuntime 创建并注入）
     * @param config                          配置快照（由 QueQiaoRuntime 注入）
     * @param utils                           Runtime 作用域辅助能力（由 QueQiaoRuntime 注入）
     */
    public WebsocketManager(
                    Logger logger,
                    Gson gson,
                    HandleProtocolMessage handleProtocolMessage,
                    Config config,
                    RuntimeUtils utils) {
        this.logger = logger;
        this.gson = gson;
        this.utils = Objects.requireNonNull(utils, "utils");
        // 内部不变量：这些依赖由 QueQiaoRuntime 注入，为 null 属接线缺陷。
        // 快速失败，避免在 stop() 中途（客户端已全部停止、调度器尚未关闭）才抛 NPE。
        this.handleProtocolMessage = Objects.requireNonNull(handleProtocolMessage, "handleProtocolMessage");
        this.config = Objects.requireNonNull(config, "config");
        this.wsClientList = new ArrayList<>();
        this.reconnectScheduler = createReconnectScheduler();
    }

    /**
     * 获取共享重连调度器
     *
     * <p>包级可见：仅供同包测试断言其生命周期（已关闭 / 未关闭），
     * 避免测试使用反射读取私有字段。生产代码不应调用。
     *
     * @return 共享重连调度器
     */
    ScheduledThreadPoolExecutor reconnectSchedulerForTest() {
        return this.reconnectScheduler;
    }

    /**
     * 创建共享重连调度器
     *
     * @return 已配置好的调度器
     */
    private static ScheduledThreadPoolExecutor createReconnectScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                RECONNECT_SCHEDULER_CORE_POOL_SIZE, new ReconnectThreadFactory());
        // 取消的任务立即从延迟队列移除，避免已取消任务继续占位
        executor.setRemoveOnCancelPolicy(true);
        // 关闭时不再执行已排队的延迟任务
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    public List<WsClient> getWsClientList() {
        synchronized (lifecycleLock) {
            return new ArrayList<>(wsClientList);
        }
    }

    public WsServer getWsServer() {
        synchronized (lifecycleLock) {
            return wsServer;
        }
    }

    /**
     * 启动所有 endpoint
     *
     * <p>每个 URL 独立启动：单个 endpoint 失败不得阻断其它 endpoint。
     *
     * @param source 命令来源；无实际命令来源时使用 CommandSource.NONE
     */
    private void startClients(CommandSource source) {
        source.reply(WebsocketConstantMessage.Client.LAUNCHING);

        WebSocketUrlNormalizer.Result normalized =
                WebSocketUrlNormalizer.normalize(this.config.get(ConfigKeys.WebSocketClient.URL_LIST));

        for (String rejectedUrl : normalized.getRejected()) {
            this.logger.warn("WebSocket URL scheme 不受支持（仅支持 ws:// 与 wss://），已跳过：{}", rejectedUrl);
            source.reply(buildUriErrorMessage(rejectedUrl));
        }

        ReconnectPolicy reconnectPolicy = new ReconnectPolicy(
                this.config.get(ConfigKeys.WebSocketClient.RECONNECT_INTERVAL),
                this.config.get(ConfigKeys.WebSocketClient.RECONNECT_MAX_TIMES));
        if (!reconnectPolicy.isAutoReconnectEnabled()) {
            this.logger.warn("WebSocket 自动重连未启用（reconnect_max_times <= 0），连接断开后不会自动重连");
        }

        for (String websocketUrl : normalized.getAccepted()) {
            startClient(websocketUrl, reconnectPolicy, source);
        }
    }

    /**
     * 启动单个 endpoint
     *
     * @param websocketUrl    已归一化的 URL
     * @param reconnectPolicy 退避策略
     * @param source 命令来源；无实际命令来源时使用 CommandSource.NONE
     */
    private void startClient(String websocketUrl, ReconnectPolicy reconnectPolicy, CommandSource source) {
        WsClient wsClient = null;
        try {
            URI uri = new URI(websocketUrl);
            wsClient = new WsClient(
                    uri,
                    this.logger,
                    this.reconnectScheduler,
                    reconnectPolicy,
                    this.handleProtocolMessage,
                    this.config.get(ConfigKeys.SERVER_NAME),
                    this.config.get(ConfigKeys.ACCESS_TOKEN),
                    this.config.get(ConfigKeys.ENABLE),
                    this.utils
            );
            // 先纳入管理列表：保证 connect() 同步抛异常时该实例仍能被回收，Manager 始终拥有 Client 生命周期
            this.wsClientList.add(wsClient);
            wsClient.connect();
        } catch (URISyntaxException e) {
            discardFailedClient(wsClient);
            this.logger.warn("WebSocket URL 格式错误，无法连接：{}", WebSocketUrlNormalizer.sanitizeForLog(websocketUrl));
            source.reply(buildUriErrorMessage(websocketUrl));
        } catch (RuntimeException e) {
            discardFailedClient(wsClient);
            this.logger.warn(
                    "WebSocket 客户端启动失败，url={}，error={}", WebSocketUrlNormalizer.sanitizeForLog(websocketUrl), e.getMessage());
            source.reply(buildUriErrorMessage(websocketUrl));
        }
    }

    /**
     * 回收启动失败的 Client
     *
     * @param wsClient 可能为 null
     */
    private void discardFailedClient(WsClient wsClient) {
        if (wsClient == null) {
            return;
        }
        this.wsClientList.remove(wsClient);
        try {
            wsClient.stopWithoutReconnect(CLOSE_CODE_NORMAL, WebsocketConstantMessage.CLOSE_BY_RELOAD);
        } catch (RuntimeException e) {
            utils.debugLog("清理启动失败的 WebSocket 客户端时出现异常：{}", e.getMessage());
        }
    }

    /**
     * 构造 URL 错误提示（已脱敏）
     *
     * @param websocketUrl 原始 URL
     * @return 可直接返回给命令执行者的文本
     */
    private static String buildUriErrorMessage(String websocketUrl) {
        String sanitizedUrl = WebSocketUrlNormalizer.sanitizeForLog(websocketUrl);
        return Tool.format(WebsocketConstantMessage.Client.URI_SYNTAX_ERROR, sanitizedUrl);
    }

    /**
     * 停止所有 Client
     *
     * <p>只停止 Client，<b>绝不</b>关闭共享调度器。
     *
     * <p>{@code reason} 是<b>纯文本</b>关闭原因，直接透传给底层；
     * 给命令执行者的提示另行用 {@link Tool#format} 插值。
     * 此前这里把 {@code {}} 模板直接交给 {@code String.format}，
     * 由于该字符串不含 {@code %s}，{@code String.format} 会原样返回、参数被静默忽略，
     * 导致<b>关闭帧的原因里带着字面 {@code {}}</b>。
     *
     * @param code            关闭码
     * @param reason          关闭原因（纯文本，不作为格式模板使用）
     * @param source 命令来源；无实际命令来源时使用 CommandSource.NONE
     */
    private void stopClients(int code, String reason, CommandSource source) {
        for (WsClient wsClient : wsClientList) {
            wsClient.stopWithoutReconnect(code, reason);
            source.reply(Tool.format(WebsocketConstantMessage.Client.CLOSING_CONNECTION, wsClient.getURI(), code, reason));
        }
        wsClientList.clear();
        source.reply(WebsocketConstantMessage.Client.CLEAR_WEBSOCKET_CLIENT_LIST);
    }

    /**
     * 按新配置重建所有 Client
     *
     * <p><b>语义：先停全部旧连接，再按新配置逐个尽力启动。</b>
     * 单个 endpoint 失败不影响其它 endpoint；最终 {@code wsClientList} 只包含
     * <b>成功启动</b>的客户端，失败项均有日志——最终状态可预测。
     *
     * <p><b>有意不做回滚</b>：回滚需要保留旧连接直到新连接全部就绪，
     * 而旧连接是用<b>旧配置</b>（可能已变更的地址 / 凭据）建立的，
     * 保留它会让系统停在"用旧配置运行、却声称已重载完成"的状态，比
     * "部分启动 + 明确日志"更难排查；实现回滚还需双份连接共存与原子切换，复杂度与收益不成比例。
     *
     * @param source 命令来源；无实际命令来源时使用 CommandSource.NONE
     */
    private void restartClients(CommandSource source) {
        source.reply(WebsocketConstantMessage.Client.RELOADING);
        stopClients(CLOSE_CODE_NORMAL, WebsocketConstantMessage.CLOSE_BY_RELOAD, source);
        if (this.config.get(ConfigKeys.WebSocketClient.ENABLE)) {
            startClients(source);
        }
        source.reply(WebsocketConstantMessage.Client.RELOADED);
    }

    private void startServer(CommandSource source) {
        WsServer server = new WsServer(
                new InetSocketAddress(
                        this.config.get(ConfigKeys.WebSocket.HOST),
                        this.config.get(ConfigKeys.WebSocket.PORT)
                ),
                logger,
                handleProtocolMessage,
                this.config.get(ConfigKeys.SERVER_NAME),
                this.config.get(ConfigKeys.ACCESS_TOKEN),
                this.config.get(ConfigKeys.ENABLE),
                this::onServerFailure
        );
        wsServer = server;
        server.start();
        source.reply(Tool.format(
                        WebsocketConstantMessage.Server.SERVER_STARTING,
                        this.config.get(ConfigKeys.WebSocket.HOST),
                        this.config.get(ConfigKeys.WebSocket.PORT)
                )
        );
    }

    /**
     * 清除仍指向当前失败实例的引用。volatile identity check 可避免旧实例的迟到回调清掉新 Server。
     */
    private void onServerFailure(WsServer failedServer) {
        if (wsServer == failedServer) {
            wsServer = null;
        }
    }

    private void stopServer(CommandSource source, String reason) {
        if (wsServer != null) {
            try {
                wsServer.stop(0, reason);
                source.reply(reason);
            } catch (InterruptedException e) {
                source.reply(WebsocketConstantMessage.Server.ERROR_ON_STOPPING);
                utils.debugLog(e.getMessage());
            }
            wsServer = null;
        }
    }

    private void restartServer(CommandSource source) {
        stopServer(source, WebsocketConstantMessage.Server.RELOADING);
        if (this.config.get(ConfigKeys.WebSocket.ENABLE)) {
            startServer(source);
        }
        source.reply(WebsocketConstantMessage.Server.RELOADED);
    }

    /**
     * 启动（幂等）
     *
     * <p>重复调用不会创建第二套 Client，也不会创建第二个调度器。
     *
     * @param source 命令来源；无实际命令来源时使用 CommandSource.NONE
     */
    public void start(CommandSource source) {
        synchronized (lifecycleLock) {
            if (destroyed) {
                utils.debugLog("WebsocketManager 已销毁，忽略启动请求");
                return;
            }
            if (started) {
                // Client 已启动时保持幂等；如果先前 Server 绑定失败，则允许单独重试 Server。
                if (wsServer == null && this.config.get(ConfigKeys.WebSocket.ENABLE)) {
                    startServer(source);
                } else {
                    utils.debugLog("WebsocketManager 已启动，忽略重复启动");
                }
                return;
            }
            started = true;
            if (this.config.get(ConfigKeys.WebSocketClient.ENABLE)) {
                startClients(source);
            }
            if (this.config.get(ConfigKeys.WebSocket.ENABLE)) {
                startServer(source);
            }
        }
    }

    /**
     * 永久停止（幂等）
     *
     * <p>这是唯一会销毁共享调度器的路径。
     *
     * @param code            关闭码
     * @param reason          关闭原因
     * @param source 命令来源；无实际命令来源时使用 CommandSource.NONE
     */
    public void stop(int code, String reason, CommandSource source) {
        synchronized (lifecycleLock) {
            if (destroyed) {
                utils.debugLog("WebsocketManager 已销毁，忽略重复停止");
                return;
            }
            destroyed = true;
            started = false;
            stopClients(code, reason, source);
            stopServer(source, reason);
            shutdownReconnectScheduler();
        }
    }

    /**
     * 重载（保留共享调度器）
     *
     * <p>先替换配置快照，再按新配置重建 Client 与 Server。
     * 不允许 stop scheduler 后再复用已关闭的 scheduler。
     *
     * @param newConfig       新的配置快照（由 QueQiaoRuntime 在 reload 时加载）
     * @param source 命令来源；无实际命令来源时使用 CommandSource.NONE
     */
    public void restart(Config newConfig, CommandSource source) {
        synchronized (lifecycleLock) {
            if (destroyed) {
                utils.debugLog("WebsocketManager 已销毁，忽略重载请求");
                return;
            }
            this.config = Objects.requireNonNull(newConfig, "newConfig");
            restartClients(source);
            restartServer(source);
            started = true;
        }
    }

    /**
     * 关闭共享重连调度器
     */
    private void shutdownReconnectScheduler() {
        if (this.reconnectScheduler.isShutdown()) {
            return;
        }
        this.reconnectScheduler.shutdownNow();
        utils.debugLog("WebsocketManager 共享重连调度器已关闭");
    }

    /**
     * 分发事件到所有接收方
     *
     * <p>线程模型：序列化与分发都在<b>调用线程</b>上同步完成。
     * 这是有意为之——实测单次事件序列化约 1~2.3 µs，每 tick 100 个事件仅占
     * 50 ms tick 预算的 0.5% 左右；而改为异步会引入"队列溢出如何处理"与
     * "同一连接内事件乱序/丢失"两个更难接受的问题。
     *
     * <p>低成本守卫：先取接收方快照，若既无 Client 也无 Server，直接返回，
     * 不做无意义的序列化。
     *
     * @param event 事件
     */
    public void sendEvent(BaseEvent event) {
        if (!this.config.get(ConfigKeys.ENABLE)) {
            return;
        }

        List<WsClient> wsClientSnapshot;
        WsServer wsServerSnapshot;
        synchronized (lifecycleLock) {
            wsClientSnapshot = new ArrayList<>(wsClientList);
            wsServerSnapshot = wsServer;
        }

        // 没有任何接收方时无需序列化
        if (wsClientSnapshot.isEmpty() && wsServerSnapshot == null) {
            return;
        }

        String json = gson.toJson(event);
        wsClientSnapshot.forEach(wsClient -> sendClientEvent(wsClient, json));
        if (wsServerSnapshot != null) {
            broadcastServerEvent(wsServerSnapshot, json);
        }
    }

    /**
     * 向单个 Client 发送事件
     *
     * <p><b>{@code isOpen()} 只是优化判断，不是线程安全保证</b>：
     * 检查与发送之间连接可能刚好关闭（TOCTOU），
     * 因此下面的异常捕获<b>必须保留</b>——不要因为"已经判过 isOpen 了"而删除它。
     *
     * @param wsClient 客户端
     * @param json     已序列化的事件
     */
    private void sendClientEvent(WsClient wsClient, String json) {
        try {
            if (wsClient.isOpen()) {
                wsClient.send(json);
                utils.debugLog("WebSocket Client {} send message {}", wsClient.getURI(), json);
            } else {
                utils.debugLog("WebSocket Client {} is not connected, skip message {}", wsClient.getURI(), json);
            }
        } catch (RuntimeException e) {
            logger.warn("WebSocket Client send failed, uri={}, error={}", wsClient.getURI(), e.getMessage());
        }
    }

    private void broadcastServerEvent(WsServer server, String json) {
        try {
            server.broadcast(json);
            utils.debugLog("WebSocket Server broadcast message: {}", json);
        } catch (RuntimeException e) {
            logger.warn("WebSocket Server broadcast failed, error={}", e.getMessage());
        }
    }

    /**
     * 重连调度器线程工厂
     *
     * <p>daemon 只作为 JVM 退出时的兜底，不代替显式 shutdown。
     */
    private static final class ReconnectThreadFactory implements ThreadFactory {

        private final AtomicInteger threadNumber = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, RECONNECT_THREAD_NAME_PREFIX + threadNumber.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
