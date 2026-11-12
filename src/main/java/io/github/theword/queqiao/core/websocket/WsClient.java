package io.github.theword.queqiao.core.websocket;

import io.github.theword.queqiao.core.constant.WebsocketConstantMessage;
import io.github.theword.queqiao.core.handle.HandleProtocolMessage;
import io.github.theword.queqiao.core.utils.RuntimeUtils;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket 客户端
 *
 * <p>重连生命周期由本类自己掌握，不依赖 Java-WebSocket 的行为细节：
 * <ul>
 *     <li><b>唯一入口</b>：所有重连请求（自动 / 手动）都经 {@link #requestReconnect(ReconnectReason)}</li>
 *     <li><b>generation</b>：让已入队但过期的任务自行失效（{@code Future.cancel} 拦不住已开始执行的任务）</li>
 *     <li><b>reconnectInProgress</b>：非阻塞互斥，防止同一实例并发执行 {@code super.reconnect()}</li>
 *     <li><b>stopped</b>：唯一可靠的"是否还需要维持连接"判据，取代不可靠的 {@code remote} 参数</li>
 * </ul>
 *
 * <p>所有权：调度器由 {@code WebsocketManager} 提供并持有，本类只使用、<b>绝不</b>销毁它。
 *
 * @since 0.6.11
 */
public class WsClient extends WebSocketClient {

    /**
     * 连接丢失检测周期（秒）
     *
     * <p>显式设置，避免依赖 Java-WebSocket 的默认值（60 秒）。
     * 该检测由库自带，会以 {@code remote == false} 关闭连接，进而触发本类的自动重连。
     */
    private static final int CONNECTION_LOST_TIMEOUT_SECONDS = 60;

    private final Logger logger;
    private final boolean enabled;
    private final HandleProtocolMessage handleProtocolMessage;

    /**
     * Runtime 作用域辅助能力（由 WebsocketManager 传递，本类只使用）
     */
    private final RuntimeUtils utils;

    /**
     * 重连调度器（由 WebsocketManager 提供，本类不负责其生命周期）
     */
    private final ScheduledExecutorService reconnectScheduler;

    /**
     * 退避策略（纯逻辑组件）
     */
    private final ReconnectPolicy reconnectPolicy;

    /**
     * 保护重连状态字段的锁
     *
     * <p><b>只保护状态读写，绝不跨越 {@code super.reconnect()}</b>：
     * 该方法内部会阻塞在无超时的 {@code closeBlocking()}，持锁会拖死
     * {@code stopWithoutReconnect()} 与其它重连请求。
     */
    private final Object reconnectLock = new Object();

    /**
     * 当前待执行的重连任务，受 {@link #reconnectLock} 保护
     */
    private ScheduledFuture<?> reconnectFuture;

    /**
     * 重连代际号，受 {@link #reconnectLock} 保护
     */
    private long reconnectGeneration;

    /**
     * 当前连续失败次数，受 {@link #reconnectLock} 保护
     */
    private int reconnectAttempts;

    /**
     * 防止同一实例并发执行 reconnect（非阻塞互斥）
     */
    private final AtomicBoolean reconnectInProgress = new AtomicBoolean(false);

    /**
     * 是否仍需要维持连接
     */
    private volatile boolean stopped = false;

    /**
     * 构造 WebSocket 客户端
     *
     * @param uri                连接地址
     * @param logger             日志实现
     * @param reconnectScheduler 由 WebsocketManager 提供的共享重连调度器
     * @param reconnectPolicy    退避策略
     * @param handleProtocolMessage 协议分发入口（由 QueQiaoRuntime 创建，多连接共享同一实例）
     * @param serverName         服务器名称
     * @param accessToken        访问令牌（为空表示不鉴权）
     * @param enabled            是否启用协议消息处理
     * @param utils              Runtime 作用域辅助能力（由 WebsocketManager 传递）
     */
    public WsClient(
                    URI uri,
                    Logger logger,
                    ScheduledExecutorService reconnectScheduler,
                    ReconnectPolicy reconnectPolicy,
                    HandleProtocolMessage handleProtocolMessage,
                    String serverName,
                    String accessToken,
                    boolean enabled,
                    RuntimeUtils utils) {
        super(uri);
        this.logger = logger;
        this.reconnectScheduler = reconnectScheduler;
        this.reconnectPolicy = reconnectPolicy;
        this.handleProtocolMessage = handleProtocolMessage;
        this.enabled = enabled;
        this.utils = utils;
        try {
            this.addHeader("x-self-name", URLEncoder.encode(serverName, StandardCharsets.UTF_8.toString()));
        } catch (UnsupportedEncodingException e) {
            this.logger.error("WebSocket 客户端初始化失败，服务器名称编码异常", e);
        }
        this.addHeader("x-client-origin", "minecraft");
        if (accessToken != null && !accessToken.isEmpty()) {
            this.addHeader("Authorization", "Bearer " + accessToken);
        }
        this.setConnectionLostTimeout(CONNECTION_LOST_TIMEOUT_SECONDS);
    }

    /**
     * 连接成功
     *
     * <p>必须同时完成四件事：推进代际、取消待执行任务、清空任务引用、重置连续失败计数。
     * 否则一个"迟到的"自动重连任务会把刚建立好的健康连接 {@code reset()} 掉，造成连接抖动。
     *
     * @param serverHandshake 握手信息
     */
    @Override
    public void onOpen(ServerHandshake serverHandshake) {
        // stop 与 reconnect 竞态：super.reconnect() 内部是 reset()（重建 engine）+ connect()，
        // 若 stop() 发生在两者之间，它关闭的是旧 engine，新连接仍会建立并回调到这里。
        // 此处兜底：已停止的实例不允许保留活动连接。
        if (this.stopped) {
            this.logger.warn(WebsocketConstantMessage.Client.STOPPED_DISCARD_CONNECTION, getURI());
            close();
            return;
        }
        this.logger.info(WebsocketConstantMessage.Client.CONNECT_SUCCESSFUL, getURI());
        synchronized (this.reconnectLock) {
            this.reconnectGeneration++;
            cancelPendingReconnect();
            this.reconnectAttempts = 0;
        }
    }

    /**
     * 收到消息
     *
     * <p>业务层异常绝不允许逃出 WebSocket callback：Java-WebSocket 的读循环会捕获
     * {@code RuntimeException} 并执行 {@code closeConnection(ABNORMAL_CLOSE)}，
     * 一条异常消息就会把连接打掉并触发无意义的连接抖动。
     *
     * @param message 原始消息
     */
    @Override
    public void onMessage(String message) {
        // stopped 是唯一可靠的"是否还需要维持连接"判据：停止后即使有迟到帧（例如
        // stop 与 reconnect 并发导致连接短暂建立），也不得进入业务处理。
        if (this.stopped || !this.enabled) {
            return;
        }
        try {
            String response = this.handleProtocolMessage.handleWebsocketJson(this, message);
            if (response != null && !response.isEmpty()) {
                send(response);
            }
        } catch (RuntimeException e) {
            this.logger.error(WebsocketConstantMessage.Client.MESSAGE_HANDLE_FAILED, getURI(), e);
        }
    }

    /**
     * 连接关闭
     *
     * <p><b>不依赖 {@code remote}</b>：Java-WebSocket 在连接失败、异常关闭、心跳超时时
     * 都会以 {@code remote == false} 进入本回调。唯一可靠的判据是 {@code stopped}。
     *
     * @param code   关闭码
     * @param reason 关闭原因
     * @param remote 是否由对端发起（不可作为重连判据）
     */
    @Override
    public void onClose(int code, String reason, boolean remote) {
        this.logger.warn(WebsocketConstantMessage.Client.CLOSING_CONNECTION, getURI(), code, reason);
        requestReconnect(ReconnectReason.REMOTE_CLOSE);
    }

    /**
     * 连接异常
     *
     * <p>只记录错误，<b>不安排重连</b>：连接失败与异常关闭都会随后触发 {@code onClose}，
     * 由 {@code onClose} 走统一 pipeline。否则一次故障会被安排两次重连。
     *
     * @param exception 异常
     */
    @Override
    public void onError(Exception exception) {
        this.logger.warn(
                WebsocketConstantMessage.Client.CONNECTION_ERROR, getURI(), exception.getMessage(), exception);
    }

    /**
     * 统一重连入口
     *
     * <p>自动与手动重连共用同一套机制，不允许各自实现 scheduler 调度。
     *
     * @param reason 重连原因
     */
    private void requestReconnect(ReconnectReason reason) {
        if (this.stopped) {
            utils.debugLog("WebSocket {} 已停止，跳过重连请求（reason={}）", getURI(), reason);
            return;
        }

        long delaySeconds;
        long generation;
        synchronized (this.reconnectLock) {
            if (this.stopped) {
                return;
            }

            if (reason == ReconnectReason.MANUAL) {
                // 语义：手动重连重置连续失败计数。
                // 该重置必须发生在 in-progress 检查之前，否则"重置计数"的意图会丢失。
                this.reconnectAttempts = 0;
                if (this.reconnectInProgress.get()) {
                    this.logger.info("WebSocket {} 已有重连正在执行，跳过本次手动重连", getURI());
                    return;
                }
                delaySeconds = 0L;
            } else {
                if (!this.reconnectPolicy.isAutoReconnectEnabled()) {
                    utils.debugLog("WebSocket {} 未启用自动重连，跳过（reason={}）", getURI(), reason);
                    return;
                }
                if (!this.reconnectPolicy.canAttempt(this.reconnectAttempts)) {
                    this.logger.info(WebsocketConstantMessage.Client.MAX_RECONNECT_ATTEMPTS_REACHED, getURI());
                    return;
                }
                delaySeconds = this.reconnectPolicy.calculateReconnectDelay(this.reconnectAttempts);
                this.reconnectAttempts++;
                this.logger.warn(WebsocketConstantMessage.Client.RECONNECTING, getURI(), this.reconnectAttempts);
            }

            // 同一时刻最多一个逻辑 pending 重连：取消旧的并推进代际，使旧任务自行失效
            cancelPendingReconnect();
            this.reconnectGeneration++;
            generation = this.reconnectGeneration;
        }

        scheduleReconnectTask(generation, delaySeconds, reason);
    }

    /**
     * 把重连任务投递到共享调度器
     *
     * @param generation   代际号
     * @param delaySeconds 延迟秒数，恒为非负
     * @param reason       重连原因（仅用于日志）
     */
    private void scheduleReconnectTask(long generation, long delaySeconds, ReconnectReason reason) {
        if (this.reconnectScheduler.isShutdown()) {
            utils.debugLog("WebSocket {} 的重连调度器已关闭，跳过重连（reason={}）", getURI(), reason);
            return;
        }
        try {
            ScheduledFuture<?> future = this.reconnectScheduler.schedule(
                    () -> executeReconnect(generation, reason), delaySeconds, TimeUnit.SECONDS);
            synchronized (this.reconnectLock) {
                if (generation == this.reconnectGeneration) {
                    this.reconnectFuture = future;
                } else {
                    // 投递期间已被更新的请求取代，直接丢弃
                    future.cancel(false);
                }
            }
        } catch (RejectedExecutionException e) {
            utils.debugLog("WebSocket {} 的重连任务被拒绝（调度器已关闭）：{}", getURI(), e.getMessage());
        }
    }

    /**
     * 在调度线程上执行重连
     *
     * @param generation 代际号
     * @param reason     重连原因（仅用于日志）
     */
    private void executeReconnect(long generation, ReconnectReason reason) {
        synchronized (this.reconnectLock) {
            if (this.stopped) {
                return;
            }
            if (generation != this.reconnectGeneration) {
                utils.debugLog(
                        "WebSocket {} 的重连任务已过期（generation {} != {}），跳过", getURI(), generation, this.reconnectGeneration);
                return;
            }
            this.reconnectFuture = null;
        }

        // 非阻塞互斥：generation 只能拦"过期任务"，拦不住"两个当前有效任务同时执行"。
        // 绝不能用 synchronized 跨越 super.reconnect() —— 它内部会阻塞在无超时的 closeBlocking()。
        if (!this.reconnectInProgress.compareAndSet(false, true)) {
            utils.debugLog("WebSocket {} 已有重连正在执行，跳过本次（reason={}）", getURI(), reason);
            return;
        }

        try {
            super.reconnect();
        } catch (RuntimeException e) {
            // 重连自身异常不得逃出调度线程
            this.logger.warn("WebSocket {} 重连执行异常：{}", getURI(), e.getMessage(), e);
        } finally {
            this.reconnectInProgress.set(false);
        }

        // reconnect() 期间可能已执行 stop()：generation 拦不住已经开始的重连，
        // 而 reset() 重建了 engine，使 stop() 的 close() 作用在旧 engine 上。
        // 这里补一次关闭，避免"已停止的实例仍持有一条活动连接"。
        if (this.stopped) {
            utils.debugLog("WebSocket {} 在重连完成时已停止，关闭本次建立/残留的连接", getURI());
            close();
        }
    }

    /**
     * 主动立即重连（手动重连）
     *
     * <p>与自动重连共用同一 pipeline，只是延迟为 0 且会重置连续失败计数。
     */
    public void reconnectNow() {
        requestReconnect(ReconnectReason.MANUAL);
    }

    /**
     * 停止并不再自动重连
     *
     * <p><b>绝不 shutdown 共享调度器</b>：它是 WebsocketManager 的资源，
     * 单个 Client 的停止不能影响其它 Client。
     *
     * @param code   关闭码
     * @param reason 关闭原因
     */
    public void stopWithoutReconnect(int code, String reason) {
        synchronized (this.reconnectLock) {
            this.stopped = true;
            this.reconnectGeneration++;
            cancelPendingReconnect();
        }
        close(code, reason);
    }

    /**
     * 取消待执行的重连任务
     *
     * <p>调用方必须持有 {@link #reconnectLock}。
     */
    private void cancelPendingReconnect() {
        if (this.reconnectFuture != null) {
            this.reconnectFuture.cancel(false);
            this.reconnectFuture = null;
        }
    }

    /**
     * 是否已有重连正在执行（诊断与测试用）
     *
     * @return true 表示当前有 reconnect 正在执行
     */
    public boolean isReconnectInProgress() {
        return this.reconnectInProgress.get();
    }

    /**
     * 获取当前连续失败次数（诊断与测试用）
     *
     * <p><b>语义边界</b>：该计数在<b>决定重连</b>时自增（{@link #requestReconnect} 内），
     * 早于"任务被提交到调度器"，更早于"调度线程真正启动"。
     * 因此它只能回答"是否已决定重连"，<b>不能</b>作为"重连已安排/线程已存在"的证据——
     * 需要后两者时请等待 {@code scheduler} 的状态或实际观测。
     *
     * @return 连续失败次数
     */
    public int getReconnectAttempts() {
        synchronized (this.reconnectLock) {
            return this.reconnectAttempts;
        }
    }

    /**
     * 是否已停止（诊断与测试用）
     *
     * @return true 表示已停止，不会再自动重连
     */
    public boolean isStopped() {
        return this.stopped;
    }
}
