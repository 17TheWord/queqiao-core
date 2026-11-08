package io.github.theword.queqiao.core.utils;

import io.github.theword.queqiao.core.config.ConfigKeys;
import io.github.theword.queqiao.core.config.Config;
import io.github.theword.queqiao.core.constant.WebsocketConstantMessage;
import io.github.theword.queqiao.core.event.base.BaseEvent;
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
import java.util.Collections;
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
 * 因此即使未启用 Client 也无实际线程开销），只在 {@link #stop(int, String)} 这一永久销毁路径
 * 关闭；{@code stopClients()} / {@code restartClients()} / {@link #suspend()} / {@link #restart(Config)}
 * 绝不触碰它。
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

    /**
     * 停止 WebSocket Server 时等待 selector 线程退出的上限（毫秒）
     *
     * <p>Java-WebSocket 的 {@code stop(timeout, closeMessage)} 内部是
     * {@code selectorthread.join(timeout)}，<b>传 0 等于无限等待</b>。而 selector 线程
     * 可能阻塞在缓冲区等待或同步回调里，此时"等它退出"会变成死锁：主线程等 selector，
     * selector 等主线程。
     *
     * <p>正常关闭时该线程会在 {@code isclosed} 后的 5 次 5ms 轮询内退出（约 25ms），
     * 1 秒是它的 40 倍，足够吸收调度抖动；超时后由 {@link WsServer#stopBounded(int, String)}
     * 补一次中断，避免线程卡住导致端口一直处于监听状态。
     */
    private static final int SERVER_STOP_JOIN_TIMEOUT_MILLIS = 1_000;

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
     * <p>由 {@code QueQiaoRuntime} 注入；reload 时通过 {@link #restart(Config)} 整体替换。
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
     * 是否已启动（保证 {@link #start()} 幂等）
     */
    private boolean started = false;

    /**
     * 是否已永久销毁（保证 {@link #stop(int, String)} 幂等）
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
     */
    private void startClients(List<String> messages) {
        messages.add(WebsocketConstantMessage.Client.LAUNCHING);

        WebSocketUrlNormalizer.Result normalized =
                WebSocketUrlNormalizer.normalize(this.config.get(ConfigKeys.WebSocketClient.URL_LIST));

        for (String rejectedUrl : normalized.getRejected()) {
            this.logger.warn("WebSocket URL scheme 不受支持（仅支持 ws:// 与 wss://），已跳过：{}", rejectedUrl);
            messages.add(buildUriErrorMessage(rejectedUrl));
        }

        ReconnectPolicy reconnectPolicy = new ReconnectPolicy(
                this.config.get(ConfigKeys.WebSocketClient.RECONNECT_INTERVAL),
                this.config.get(ConfigKeys.WebSocketClient.RECONNECT_MAX_TIMES));
        if (!reconnectPolicy.isAutoReconnectEnabled()) {
            this.logger.warn("WebSocket 自动重连未启用（reconnect_max_times <= 0），连接断开后不会自动重连");
        }

        for (String websocketUrl : normalized.getAccepted()) {
            startClient(websocketUrl, reconnectPolicy, messages);
        }
    }

    /**
     * 启动单个 endpoint
     *
     * @param websocketUrl    已归一化的 URL
     * @param reconnectPolicy 退避策略
     */
    private void startClient(String websocketUrl, ReconnectPolicy reconnectPolicy, List<String> messages) {
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
            messages.add(buildUriErrorMessage(websocketUrl));
        } catch (RuntimeException e) {
            discardFailedClient(wsClient);
            this.logger.warn(
                    "WebSocket 客户端启动失败，url={}，error={}", WebSocketUrlNormalizer.sanitizeForLog(websocketUrl), e.getMessage());
            messages.add(buildUriErrorMessage(websocketUrl));
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
     */
    private void stopClients(int code, String reason, List<String> messages) {
        for (WsClient wsClient : wsClientList) {
            wsClient.stopWithoutReconnect(code, reason);
            messages.add(Tool.format(WebsocketConstantMessage.Client.CLOSING_CONNECTION, wsClient.getURI(), code, reason));
        }
        wsClientList.clear();
        messages.add(WebsocketConstantMessage.Client.CLEAR_WEBSOCKET_CLIENT_LIST);
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
     */
    private void restartClients(List<String> messages) {
        messages.add(WebsocketConstantMessage.Client.RELOADING);
        stopClients(CLOSE_CODE_NORMAL, WebsocketConstantMessage.CLOSE_BY_RELOAD, messages);
        if (this.config.get(ConfigKeys.WebSocketClient.ENABLE)) {
            startClients(messages);
        }
        messages.add(WebsocketConstantMessage.Client.RELOADED);
    }

    private void startServer(List<String> messages) {
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
        messages.add(Tool.format(
                        WebsocketConstantMessage.Server.SERVER_STARTING,
                        this.config.get(ConfigKeys.WebSocket.HOST),
                        this.config.get(ConfigKeys.WebSocket.PORT)
                )
        );
    }

    /**
     * 服务端级异常回调（{@code onError(null, ...)}）
     *
     * <p><b>只记录，不清理引用</b>：Java-WebSocket 的服务端级异常回调（bind 失败、
     * selector 级错误）<b>不等于</b> selector 线程已经退出，也不等于监听 Socket 已经释放。
     * 若在这里清空引用，后续 restart 会误以为"没有旧 Server"，在端口仍被占用时 bind
     * 一个新实例——结果是既没有服务，又失去了对旧实例的跟踪。
     *
     * <p>引用的清理只由 {@link #stopServer(List, String)} 在 {@code stopBounded()}
     * 明确确认终止后进行。
     *
     * @param failedServer 报告异常的服务端实例
     */
    private void onServerFailure(WsServer failedServer) {
        utils.debugLog("WebSocket Server 报告服务端级异常，保留实例引用等待停止确认：{}:{}",
                failedServer.getAddress().getHostString(), failedServer.getPort());
    }

    /**
     * 停止 WebSocket Server
     *
     * <p><b>有界等待 + 中断兜底</b>：绝不使用 {@code stop(0, ...)}（等于无限等待 selector
     * 线程），也不假设 {@code stop(timeout)} 已经关闭监听端口——ServerSocketChannel 是在
     * selector 线程退出时才关闭的。具体策略见 {@link WsServer#stopBounded(int, String)}。
     *
     * <p><b>停止未确认时保留引用</b>：只有确认 selector 线程退出后才清空 {@code wsServer}。
     * 否则旧实例会失去跟踪（既无法再停止、也无法判断端口是否仍被占用），
     * 而调用方会误以为端口已释放并继续 bind。
     *
     * @param messages 进度消息收集器
     * @param reason   关闭原因
     * @return true 表示没有 Server，或已确认 selector 线程退出（端口已释放）；
     *         false 表示已中断但仍未确认线程退出，<b>引用被保留</b>，端口可能仍在监听
     */
    private boolean stopServer(List<String> messages, String reason) {
        WsServer current = wsServer;
        if (current == null) {
            return true;
        }
        boolean terminated = current.stopBounded(SERVER_STOP_JOIN_TIMEOUT_MILLIS, reason);
        messages.add(reason);
        if (!terminated) {
            logger.warn(
                    "WebSocket Server 未在 {} ms 内确认停止，selector 线程可能仍阻塞在协议回调中；"
                            + "已发出中断，但端口可能仍在监听（保留旧实例以便再次停止）",
                    SERVER_STOP_JOIN_TIMEOUT_MILLIS);
            messages.add(Tool.format(
                    WebsocketConstantMessage.Server.STOP_TIMEOUT, SERVER_STOP_JOIN_TIMEOUT_MILLIS));
            return false;
        }
        // 身份检查：只清除本次停止的实例，避免迟到的停止流程误清掉新 Server
        if (wsServer == current) {
            wsServer = null;
        }
        return true;
    }

    /**
     * 按新配置重建 Server
     *
     * <p><b>旧 Server 未确认停止时不启动新 Server</b>：此时监听端口可能仍被旧实例占用，
     * 直接 bind 只会异步失败（{@code onError} → 清空引用），结果既没有服务、
     * 又失去了对旧实例的跟踪。这里改为保留旧实例、明确报告失败，并让下一次
     * {@code restart()} / {@code stop()} 再次尝试停止它。
     */
    private void restartServer(List<String> messages) {
        if (!stopServer(messages, WebsocketConstantMessage.Server.RELOADING)) {
            messages.add(WebsocketConstantMessage.Server.RESTART_SKIPPED);
            return;
        }
        if (this.config.get(ConfigKeys.WebSocket.ENABLE)) {
            startServer(messages);
        }
        messages.add(WebsocketConstantMessage.Server.RELOADED);
    }

    /**
     * 启动（幂等）
     *
     * <p>重复调用不会创建第二套 Client，也不会创建第二个调度器。
     *
     * <p><b>不回执</b>：本方法只负责执行生命周期操作与记录日志。
     * WebsocketManager <b>不知道</b>命令上下文的存在，返回的进度消息由调用方决定如何处理。
     *
     * @return 本应展示给用户的进度消息，按发生顺序排列
     */
    public List<String> start() {
        synchronized (lifecycleLock) {
            List<String> messages = new ArrayList<>();
            if (destroyed) {
                utils.debugLog("WebsocketManager 已销毁，忽略启动请求");
                return messages;
            }
            if (started) {
                // 上次 Server 启动失败时引用会被保留（异常回调不再清引用）：只有确认其
                // selector 线程已退出（与 stopBounded 同一判据）才清理，然后允许重试绑定。
                if (wsServer != null && wsServer.isTerminated()) {
                    stopServer(messages, WebsocketConstantMessage.Server.STALE_SERVER_CLEANED);
                }
                // Client 已启动时保持幂等；Server 不存在（或刚被清理）时允许单独重试。
                if (wsServer == null && this.config.get(ConfigKeys.WebSocket.ENABLE)) {
                    startServer(messages);
                } else {
                    utils.debugLog("WebsocketManager 已启动，忽略重复启动");
                }
                return messages;
            }
            started = true;
            if (this.config.get(ConfigKeys.WebSocketClient.ENABLE)) {
                startClients(messages);
            }
            if (this.config.get(ConfigKeys.WebSocket.ENABLE)) {
                startServer(messages);
            }
            return messages;
        }
    }

    /**
     * 永久停止（幂等）
     *
     * <p>这是唯一会销毁共享调度器的路径。
     *
     * <p>若上一次停止未能确认 Server 终止（引用被保留），重复调用本方法会<b>再次尝试</b>
     * 停止它——否则旧实例与监听端口会永久失联。
     *
     * <p><b>不回执</b>：见 {@link #start()}。
     *
     * @param code   关闭码
     * @param reason 关闭原因
     * @return 本应展示给用户的进度消息，按发生顺序排列
     */
    public List<String> stop(int code, String reason) {
        return stopWithResult(code, reason).messages();
    }

    /**
     * 永久停止，并返回"WebSocket Server 是否确认终止"的明确结果
     *
     * <p>与 {@link #stop(int, String)} 的差别只在返回值：{@code stop} 的进度消息里
     * 虽然带有超时提示，但调用方只能靠解析文本判断成败。需要据此上报关闭结果的
     * 调用方（例如 {@code QueQiaoRuntime#shutdownResources()}）应使用本方法。
     *
     * <p><b>对 WebSocket Client 的语义</b>：只发出停止请求并禁止后续自动重连
     * （{@code WsClient#stopWithoutReconnect} → {@code close(code, reason)}），
     * <b>不等待</b>对端关闭握手完成，也不确认底层连接已完全释放。
     * 因此返回值中的"成功"只覆盖 Server 侧。
     *
     * <p><b>停止未确认时不会丢弃引用</b>：{@code wsServer} 仍被保留，
     * 因此重复调用本方法会再次尝试停止旧实例（见 {@link #stopServer(List, String)}）。
     *
     * @param code   关闭码
     * @param reason 关闭原因
     * @return 停止结果，包含"WebSocket Server 是否确认终止"与进度消息
     */
    public StopResult stopWithResult(int code, String reason) {
        synchronized (lifecycleLock) {
            List<String> messages = new ArrayList<>();
            if (destroyed && wsServer == null) {
                utils.debugLog("WebsocketManager 已销毁，忽略重复停止");
                return new StopResult(true, messages);
            }
            destroyed = true;
            started = false;
            handleProtocolMessage.closeDispatch();
            stopClients(code, reason, messages);
            boolean serverStopped = stopServer(messages, reason);
            shutdownReconnectScheduler();
            return new StopResult(serverStopped, messages);
        }
    }

    /**
     * 停止结果
     *
     * <p>只表达"这一次停止操作观察到的状态"，不承担历史实例管理：
     * 失败时旧 Server 引用仍留在 Manager 内，由下一次 {@code stop} / {@code restart} 重试。
     *
     * <p><b>范围限定</b>：本结果只描述 WebSocket <b>Server</b>，
     * 不描述 WebSocket Client（Client 是异步关闭，见 {@link #stopWithResult(int, String)}）。
     */
    public static final class StopResult {

        private final boolean serverStopped;
        private final List<String> messages;

        private StopResult(boolean serverStopped, List<String> messages) {
            this.serverStopped = serverStopped;
            this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
        }

        /**
         * WebSocket Server 是否不存在或已确认终止（selector 线程已退出）
         *
         * <p>只覆盖 Server：<b>不</b>表示所有 WebSocket Client 已完成关闭，
         * 也不表示它们的关闭握手已经结束。
         *
         * @return true 表示没有 Server，或已确认 selector 线程退出（端口已释放）
         */
        public boolean isServerStopped() {
            return serverStopped;
        }

        /**
         * @return 本应展示给用户的进度消息（不可修改）
         */
        public List<String> messages() {
            return messages;
        }
    }

    /**
     * 临时暂停所有 endpoint（幂等，不销毁共享调度器）
     *
     * <p>与 {@link #stop(int, String)} 的区别：本方法只停止 Client 与 Server，
     * <b>保留</b> Manager 实例、配置能力与共享重连调度器，因此可以在配置切换过程中
     * "先停止接入 → 换掉依赖配置的组件 → 再按新配置重启"。
     *
     * <p>暂停期间不会有新的协议请求进入 Runtime。调用方必须在流程结束后调用
     * {@link #restart(Config)} 恢复（即使中间步骤失败），否则 WebSocket 会一直停服。
     *
     * <p><b>停止是"有界"的</b>：最多等待 {@link #SERVER_STOP_JOIN_TIMEOUT_MILLIS} 毫秒，
     * 超时后由 {@link WsServer#stopBounded(int, String)} 中断 selector 线程。
     * 超时不会抛出异常——调用方应继续执行后续步骤（例如强制中止 RCON），
     * 正是那些步骤能让阻塞在协议回调里的工作线程退出。超时信息会作为一条进度消息返回，
     * 不会静默吞掉。
     *
     * <p>本方法<b>不会</b>设置 {@code destroyed} 标记，也不关闭 {@code reconnectScheduler}——
     * 那两件事只属于 {@link #stop(int, String)} 这条永久销毁路径。
     *
     * @return 本应展示给用户的进度消息，按发生顺序排列
     */
    public List<String> suspend() {
        synchronized (lifecycleLock) {
            List<String> messages = new ArrayList<>();
            if (destroyed) {
                utils.debugLog("WebsocketManager 已销毁，忽略暂停请求");
                return messages;
            }
            // 先关闭分发门控，再停止连接：门控关闭后到达的报文不再进入协议处理
            handleProtocolMessage.closeDispatch();
            stopClients(CLOSE_CODE_NORMAL, WebsocketConstantMessage.CLOSE_BY_RELOAD, messages);
            // 停止未确认时 stopServer 会保留旧实例并自行追加超时消息，这里不再重复处理
            stopServer(messages, WebsocketConstantMessage.Server.RELOADING);
            started = false;
            return messages;
        }
    }

    /**
     * 重载（保留共享调度器）
     *
     * <p>先替换配置快照，再按新配置重建 Client 与 Server。
     * 不允许 stop scheduler 后再复用已关闭的 scheduler。
     *
     * <p><b>不回执</b>：见 {@link #start()}。
     *
     * @param newConfig 新的配置快照（由 QueQiaoRuntime 在 reload 时加载）
     * @return 本应展示给用户的进度消息，按发生顺序排列
     */
    public List<String> restart(Config newConfig) {
        synchronized (lifecycleLock) {
            List<String> messages = new ArrayList<>();
            if (destroyed) {
                utils.debugLog("WebsocketManager 已销毁，忽略重载请求");
                return messages;
            }
            this.config = Objects.requireNonNull(newConfig, "newConfig");
            // 恢复分发：暂停时关闭的门控在这里重新开放
            handleProtocolMessage.openDispatch();
            restartClients(messages);
            restartServer(messages);
            started = true;
            return messages;
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
