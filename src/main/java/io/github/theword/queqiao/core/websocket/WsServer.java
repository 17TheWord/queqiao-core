package io.github.theword.queqiao.core.websocket;

import io.github.theword.queqiao.core.constant.WebsocketConstantMessage;
import io.github.theword.queqiao.core.handle.HandleProtocolMessage;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;

/**
 * WebSocket 服务端
 *
 * <p><b>握手校验</b>：{@link #onOpen(WebSocket, ClientHandshake)} 依次校验
 * {@code x-self-name} → {@code x-client-origin} → {@code Authorization}，
 * 任一项失败即关闭连接。
 *
 * <p><b>为什么 onOpen 必须自己兜住异常</b>：Java-WebSocket 的 {@code WebSocketImpl.open()} 实现为
 * <pre>
 * readyState = OPEN;
 * try { wsl.onWebsocketOpen(this, d); }
 * catch (RuntimeException e) { wsl.onWebsocketError(this, e); }   // 只上报，不关闭、不重抛
 * </pre>
 * 也就是说 {@code onOpen} 抛出的异常会被库吞掉、连接保持 {@code OPEN}，
 * 于是"未通过鉴权"的连接仍会被 {@link #onMessage(WebSocket, String)} 正常处理，
 * 构成<b>鉴权旁路</b>。因此 {@code onOpen} 内部任何异常都必须主动关闭连接，
 * 绝不允许异常逃出去。（该行为已用一次性实验实证：客户端未被关闭、服务端仍持有该连接。）
 *
 * <p><b>关于连接数与帧大小上限（已评估，决定不设置）</b>
 * <ul>
 *     <li><b>总连接数上限</b>：{@code WebSocketServer.setMaxPendingConnections(int)} 限制的是
 *         "尚未完成握手的挂起连接"而非总连接数，语义容易被误解；本项目默认绑定回环地址
 *         且强制 token 鉴权，暴露面很小，故不新增该配置。</li>
 *     <li><b>帧大小上限</b>：Java-WebSocket 的 {@code Draft_6455} 默认 {@code maxFrameSize}
 *         为 {@code Integer.MAX_VALUE}（即实际无上限）。评估后仍不设置，理由：
 *         需要有效 token 才能连上；默认仅监听回环地址；
 *         且帧大小上限<b>无法</b>阻止已鉴权客户端改用大量小消息实施 DoS。
 *         代价则是新增一条拒绝路径与一个配置项，收益无法证明。</li>
 * </ul>
 * 若将来需要对外网暴露，应重新评估这两项。
 *
 * @since 0.6.11
 */
public class WsServer extends WebSocketServer {

    /**
     * 连接丢失检测周期（秒）
     *
     * <p>显式设置，避免依赖 Java-WebSocket 的默认值。该检测会向客户端发送 ping，
     * 超时未收到 pong 时以 {@code remote == false} 关闭连接，属正常的失联回收。
     */
    private static final int CONNECTION_LOST_TIMEOUT_SECONDS = 60;

    /**
     * 握手校验失败使用的关闭码（策略违规）
     */
    private static final int CLOSE_CODE_POLICY_VIOLATION = 1008;

    /**
     * 服务端内部错误使用的关闭码
     */
    private static final int CLOSE_CODE_INTERNAL_ERROR = 1011;

    /**
     * 服务端级错误（无具体客户端）时用于日志的地址占位符
     */
    private static final String SERVER_SIDE_ADDRESS = "<server>";

    /**
     * 无法获取远端地址时用于日志的占位符
     */
    private static final String UNKNOWN_ADDRESS = "<unknown>";

    private final String hostName;
    private final int port;
    private final Logger logger;
    private final String serverName;
    private final String accessToken;
    private final boolean enabled;
    private final HandleProtocolMessage handleProtocolMessage;
    private final Consumer<WsServer> serverFailureHandler;

    /**
     * selector 线程引用
     *
     * <p>Java-WebSocket 把 {@code selectorthread} 声明为 private，外部无法直接判断
     * "Server 是否已经完全终止"。这里在 {@link #run()} 入口登记当前线程——
     * <b>不能等到 {@code onStart()}</b>：那之前还要 bind 与创建 selector，
     * 在这段窗口里 {@link #isTerminated()} 会误报"已终止"。
     *
     * <p>标记 {@code volatile}：写入发生在 selector 线程，读取发生在关闭方线程。
     */
    private volatile Thread selectorThread;

    /**
     * 是否已经请求过启动
     *
     * <p>{@code selectorThread} 仍为 null 时用它区分两种情况：
     * "从未启动"（没有可等待的线程）与"已请求启动但线程还没跑到 {@link #run()}"（不能视为已终止）。
     */
    private volatile boolean startRequested;

    /**
     * 中断 selector 线程后，等待其退出的宽限时间（毫秒）
     *
     * <p>线程被中断后只需走完当前循环 → {@code doServerShutdown()} → 退出，
     * 通常是个位数毫秒；500ms 足够吸收调度抖动。
     */
    private static final int SELECTOR_TERMINATION_GRACE_MILLIS = 500;

    /**
     * 构造函数
     *
     * @param address              地址
     * @param logger               日志实现
     * @param handleProtocolMessage 协议分发入口（由 QueQiaoRuntime 创建，与 Client 共享同一实例）
     * @param serverName           服务器名称，为 null 时按空串处理
     * @param accessToken          访问令牌（可选）；为 null 或空串表示不鉴权
     * @param enabled              是否启用协议消息处理
     */
    public WsServer(
                    InetSocketAddress address, Logger logger, HandleProtocolMessage handleProtocolMessage, String serverName, String accessToken, boolean enabled) {
        this(address, logger, handleProtocolMessage, serverName, accessToken, enabled, null);
    }

    /**
     * 构造可向生命周期所有者报告服务端级启动/运行错误的 WebSocket Server。
     */
    public WsServer(
            InetSocketAddress address,
            Logger logger,
            HandleProtocolMessage handleProtocolMessage,
            String serverName,
            String accessToken,
            boolean enabled,
            Consumer<WsServer> serverFailureHandler) {
        super(address);
        super.setReuseAddr(true);
        this.logger = logger;
        this.hostName = address.getHostName();
        this.port = address.getPort();
        // 归一化 null：onOpen 会直接对这两个字段调用 isEmpty()，
        // 若平台实现传入 null（而非空串）会在握手阶段抛 NPE，
        // 而该异常会被库吞掉并保留连接 —— 即上面类注释所说的鉴权旁路。
        this.serverName = serverName == null ? "" : serverName;
        this.accessToken = accessToken == null ? "" : accessToken;
        this.enabled = enabled;
        this.handleProtocolMessage = handleProtocolMessage;
        this.serverFailureHandler = serverFailureHandler;
        this.setConnectionLostTimeout(CONNECTION_LOST_TIMEOUT_SECONDS);
        warnIfExposedWithoutToken(address);
    }

    /**
     * 当监听地址为非回环地址且未配置访问令牌时输出告警
     *
     * <p>该组合意味着<b>任何能访问该端口的人都可以调用 {@code send_rcon_command}</b>，
     * 等价于在 MC 服务器上执行任意命令。这里只告警不拒绝，
     * 因为"服务端置于反向代理 / 私有网络之后、由外层负责鉴权"是合法部署，直接拒绝会误伤。
     *
     * @param address 监听地址
     */
    private void warnIfExposedWithoutToken(InetSocketAddress address) {
        if (!this.accessToken.isEmpty() || isLoopbackAddress(address)) {
            return;
        }
        this.logger.warn(
                "WebSocket Server 绑定在非回环地址 {} 且未配置 access_token："
                        + "任何能访问该端口的人都可发送消息并执行 Rcon 命令。"
                        + "请设置 access_token，或将 websocket_server.host 改回 127.0.0.1。",
                address);
    }

    /**
     * 判断监听地址是否仅限本机回环
     *
     * <p>解析失败（{@code getAddress()} 为 null）时按"非回环"处理——
     * 宁可多告警，也不漏报。
     *
     * <p>包级可见以便单元测试（该方法只做地址判定，无副作用）。
     *
     * @param address 监听地址
     * @return true 表示仅本机可访问
     */
    static boolean isLoopbackAddress(InetSocketAddress address) {
        if (address == null) {
            return false;
        }
        InetAddress resolved = address.getAddress();
        return resolved != null && resolved.isLoopbackAddress();
    }

    /**
     * 常量时间比较
     *
     * <p>{@link String#equals(Object)} 会在首个不同字符处提前返回，耗时随内容变化。
     * 网络场景下实际可利用性很低（抖动远大于逐字节时间差），
     * 但 {@link MessageDigest#isEqual} 是零成本的标准做法，没有理由不用。
     *
     * @param provided 客户端提供的值
     * @param expected 期望值
     * @return 是否相等
     */
    private static boolean constantTimeEquals(String provided, String expected) {
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 获取客户端地址
     *
     * <p>用 {@link InetSocketAddress#getHostString()} 与 {@link InetSocketAddress#getPort()}
     * 直接取地址，不再依赖 {@code InetSocketAddress.toString()} 的具体格式做字符串清理。
     *
     * <p>两级空值防护：
     * <ul>
     *     <li>{@code webSocket} 为 null —— 服务端级致命错误（如 selector 异常）时，
     *         库会以 {@code onError(null, e)} 回调，此时没有具体客户端</li>
     *     <li>远端地址为 null —— {@code WebSocketImpl} 在 {@code channel} 为空时返回 null</li>
     * </ul>
     *
     * @param webSocket 客户端，允许为 null
     * @return 形如 {@code 127.0.0.1:49271} 的地址；无法获取时返回占位符
     */
    private static String getClientAddress(WebSocket webSocket) {
        if (webSocket == null) {
            return SERVER_SIDE_ADDRESS;
        }
        InetSocketAddress remoteAddress = webSocket.getRemoteSocketAddress();
        if (remoteAddress == null) {
            return UNKNOWN_ADDRESS;
        }
        return remoteAddress.getHostString() + ":" + remoteAddress.getPort();
    }

    /**
     * 读取请求头
     *
     * <p>头部名<b>大小写不敏感</b>（Java-WebSocket 内部使用
     * {@code TreeMap} + {@code String.CASE_INSENSITIVE_ORDER}）。
     * 头部缺失时返回空串，不会返回 null。
     *
     * @param clientHandshake 握手信息
     * @param name            头部名
     * @return 头部值（未解码），缺失时为空串
     */
    private static String getHeader(ClientHandshake clientHandshake, String name) {
        String value = clientHandshake.getFieldValue(name);
        return value == null ? "" : value;
    }

    /**
     * 读取 URL query 参数
     *
     * <p><b>为什么保留 query 兜底</b>：浏览器的 WebSocket API <b>无法设置自定义请求头</b>
     * （{@code new WebSocket(url)} 不接受 headers 选项），
     * 因此浏览器客户端只能把 {@code x-self-name}、{@code Authorization} 等字段放进 URL。
     * 去掉 query 支持会让浏览器客户端完全无法接入。
     *
     * <p><b>安全提示（重要）</b>：URL 会出现在反向代理访问日志、监控 / APM 系统、
     * 浏览器历史与抓包工具的默认视图里。
     * 因此<b>通过 query 传递 {@code Authorization} 会让 token 扩散到这些渠道</b>——
     * 能设置请求头的客户端（服务端程序、桌面应用、命令行工具）应当优先使用请求头；
     * 只有浏览器这类确实无法设置请求头的客户端才使用 query，并请自行评估日志暴露面。
     *
     * <p><b>本方法不对值做 URL 解码</b>：解码策略由各字段在使用处显式决定，
     * 因为不同字段的编码约定并不相同（见 {@link #decodeQueryValue(String)}）。
     *
     * @param clientHandshake 握手信息
     * @param name            参数名
     * @return 参数值（未解码），缺失时为空串
     */
    private static String getQueryParameter(ClientHandshake clientHandshake, String name) {
        String resource = clientHandshake.getResourceDescriptor();
        int queryStart = resource == null ? -1 : resource.indexOf('?');
        if (queryStart < 0 || queryStart + 1 >= resource.length()) {
            return "";
        }

        String query = resource.substring(queryStart + 1);
        for (String pair : query.split("&")) {
            int splitIndex = pair.indexOf('=');
            String key = splitIndex >= 0 ? pair.substring(0, splitIndex) : pair;
            String value = splitIndex >= 0 ? pair.substring(splitIndex + 1) : "";
            try {
                if (URLDecoder.decode(key, StandardCharsets.UTF_8.name()).equals(name)) {
                    return value;
                }
            } catch (IllegalArgumentException | UnsupportedEncodingException ignored) {
            }
        }
        return "";
    }

    /**
     * 解码来自 URL query 的值
     *
     * <p><b>为什么 query 值需要解码而请求头值不需要</b>：query 值在 URL 中必然经过一次编码
     * （例如 {@code "Bearer xxx"} 中的空格会变成 {@code +}），必须解码一次才能还原；
     * 而请求头值由客户端按原样发送，若也解码，token 中合法的 {@code %} / {@code +} 反而会被破坏。
     *
     * <p>这正是原清单 #12 的教训：<b>不同字段的解码策略本就不同，不应共用一套解析行为</b>。
     * 因此本类不提供"统一取值"的复合方法，而是在每个字段的使用处显式写出其解码策略。
     *
     * @param value query 原始值
     * @return 解码结果；为空或解码失败时返回空串（调用方按"该字段缺失/无效"处理）
     */
    private static String decodeQueryValue(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (IllegalArgumentException | UnsupportedEncodingException e) {
            return "";
        }
    }

    /**
     * 当客户端连接时执行 连接将依次检验 x-self-name；x-client-origin；Authorization字段
     *
     * <p>本方法<b>不允许任何异常逃出</b>，原因见类注释（库会吞掉异常并保留连接）。
     *
     * @param webSocket       客户端
     * @param clientHandshake 客户端握手信息
     */
    @Override
    public void onOpen(WebSocket webSocket, ClientHandshake clientHandshake) {
        try {
            if (!validateHandshake(webSocket, clientHandshake)) {
                return;
            }
            this.logger.info(WebsocketConstantMessage.Server.CLIENT_CONNECTED, getClientAddress(webSocket));
        } catch (RuntimeException e) {
            this.logger.error("处理连接握手时发生未预期异常，已主动关闭该连接。来自：{}", getClientAddress(webSocket), e);
            closeQuietly(webSocket, CLOSE_CODE_INTERNAL_ERROR, "Internal server error during handshake");
        }
    }

    /**
     * 校验握手字段
     *
     * <p><b>各字段的取值与解码策略（显式写出，不共用统一解析）</b>：
     * <table border="1">
     *     <tr><th>字段</th><th>请求头</th><th>URL query</th><th>原因</th></tr>
     *     <tr>
     *         <td>{@code x-self-name}</td>
     *         <td>解码 1 次</td><td>解码 1 次</td>
     *         <td>客户端约定对该字段做 URL 编码（服务器名可能含中文 / 空格），故两个来源都需解码</td>
     *     </tr>
     *     <tr>
     *         <td>{@code x-client-origin}</td>
     *         <td>不解码</td><td>解码 1 次</td>
     *         <td>纯 ASCII 标识；query 来源可能被编码，解码可避免用编码绕过 minecraft 校验</td>
     *     </tr>
     *     <tr>
     *         <td>{@code Authorization}</td>
     *         <td>不解码</td><td>解码 1 次</td>
     *         <td>请求头由客户端原样发送；query 值在 URL 中必然被编码（空格会变成 {@code +}）</td>
     *     </tr>
     * </table>
     *
     * @param webSocket       客户端
     * @param clientHandshake 客户端握手信息
     * @return true 表示校验通过；false 表示校验失败且已关闭连接
     */
    private boolean validateHandshake(WebSocket webSocket, ClientHandshake clientHandshake) {
        String rawServerName = getHeader(clientHandshake, "x-self-name");
        if (rawServerName.isEmpty()) {
            rawServerName = getQueryParameter(clientHandshake, "x-self-name");
        }
        if (rawServerName.isEmpty()) {
            this.logger.warn(
                    WebsocketConstantMessage.Server.MISSING_SERVER_NAME_HEADER, getClientAddress(webSocket));
            closeQuietly(webSocket, CLOSE_CODE_POLICY_VIOLATION, "Missing X-Self-name Header");
            return false;
        }

        // 该字段用于拒绝"来源为 minecraft"的连接（防自连），因此 query 来源也要读并解码，
        // 否则一个通过 query 声明 minecraft 的客户端反而会被接受。
        String clientOrigin = getHeader(clientHandshake, "x-client-origin");
        if (clientOrigin.isEmpty()) {
            clientOrigin = decodeQueryValue(getQueryParameter(clientHandshake, "x-client-origin"));
        }
        if (clientOrigin.equalsIgnoreCase("minecraft")) {
            this.logger.warn(
                    WebsocketConstantMessage.Server.INVALID_CLIENT_ORIGIN_HEADER, getClientAddress(webSocket));
            closeQuietly(webSocket, CLOSE_CODE_POLICY_VIOLATION, "X-Client-Origin Header cannot be minecraft");
            return false;
        }

        String decodedServerName;
        try {
            decodedServerName = URLDecoder.decode(rawServerName, StandardCharsets.UTF_8.name());
        } catch (IllegalArgumentException | UnsupportedEncodingException e) {
            this.logger.error(
                    WebsocketConstantMessage.Server.SERVER_NAME_DECODE_FAILED_HEADER, getClientAddress(webSocket), rawServerName, e.getMessage());
            closeQuietly(webSocket, CLOSE_CODE_POLICY_VIOLATION, "X-Self-name Header decode failed");
            return false;
        }

        if (decodedServerName.isEmpty()) {
            this.logger.warn(
                    WebsocketConstantMessage.Server.SERVER_NAME_PARSE_FAILED_HEADER, getClientAddress(webSocket));
            closeQuietly(webSocket, CLOSE_CODE_POLICY_VIOLATION, "X-Self-name Header cannot be empty");
            return false;
        }

        if (!decodedServerName.equals(this.serverName)) {
            this.logger.warn(
                    WebsocketConstantMessage.Server.INVALID_SERVER_NAME_HEADER, getClientAddress(webSocket), decodedServerName);
            closeQuietly(webSocket, CLOSE_CODE_POLICY_VIOLATION, "X-Self-name Header is wrong");
            return false;
        }

        if (!this.accessToken.isEmpty()) {
            // 请求头按原样取值；query 来源需要解码（URL 中的空格会变成 '+'）
            String accessToken = getHeader(clientHandshake, "Authorization");
            boolean credentialFromHeader = !accessToken.isEmpty();
            if (!credentialFromHeader) {
                accessToken = decodeQueryValue(getQueryParameter(clientHandshake, "Authorization"));
            }

            if (accessToken.isEmpty()) {
                // 明确区分"未携带凭据"与"凭据错误"：此前两者共用同一条日志，排查时难以定位
                this.logger.warn("连接未携带 Authorization 凭据，已拒绝。来自：{}", getClientAddress(webSocket));
                closeQuietly(webSocket, CLOSE_CODE_POLICY_VIOLATION, "Authorization is required");
                return false;
            }

            if (!constantTimeEquals(accessToken, "Bearer " + this.accessToken)) {
                // 安全：绝不记录客户端提交的 Authorization / accessToken / Bearer token 内容，仅记录来源地址。
                this.logger.warn(
                        WebsocketConstantMessage.Server.INVALID_ACCESS_TOKEN_HEADER, getClientAddress(webSocket));
                if (this.logger.isDebugEnabled()) {
                    // 仅记录"凭据来自哪里"，不记录内容；便于运维发现 token 被放进 URL 的情况
                    this.logger.debug(
                            "认证失败详情：凭据来源 = {}",
                            credentialFromHeader ? "请求头" : "URL query（URL 可能进入反向代理日志，建议改用请求头）");
                }
                closeQuietly(webSocket, CLOSE_CODE_POLICY_VIOLATION, "Authorization is wrong");
                return false;
            }
        }

        return true;
    }

    /**
     * 关闭连接且不抛出异常
     *
     * <p>用于异常兜底路径：此时再抛异常会掩盖原始问题。
     *
     * @param webSocket 客户端，允许为 null
     * @param code      关闭码
     * @param reason    关闭原因
     */
    private static void closeQuietly(WebSocket webSocket, int code, String reason) {
        if (webSocket == null) {
            return;
        }
        try {
            webSocket.close(code, reason);
        } catch (RuntimeException ignored) {
            // 关闭失败不再上抛：调用方已在异常兜底路径上
        }
    }

    /**
     * 当客户端断开连接时执行
     *
     * @param webSocket 客户端
     * @param code      关闭码
     * @param reason    关闭原因
     * @param remote    是否是远程关闭
     */
    @Override
    public void onClose(WebSocket webSocket, int code, String reason, boolean remote) {
        String closeReason = remote ? WebsocketConstantMessage.Server.CLIENT_DISCONNECTED : WebsocketConstantMessage.Server.CLIENT_HAD_BEEN_DISCONNECTED;
        this.logger.info(closeReason, getClientAddress(webSocket));
    }

    /**
     * 当接收到客户端的消息时执行
     *
     * <p><b>协议约定：只支持文本帧。</b>二进制帧由 Java-WebSocket 的
     * {@code onMessage(WebSocket, ByteBuffer)} 空实现静默忽略——本项目<b>有意不覆写</b>它：
     * 覆写只能额外打一条日志、不改变任何行为，反而会在客户端持续发送二进制帧时刷屏。
     * 若排查"客户端称已发送但服务端无响应"，这是一个需要确认的方向。
     *
     * @param webSocket 客户端
     * @param message   消息
     */
    @Override
    public void onMessage(WebSocket webSocket, String message) {
        if (this.enabled) {
            String response = this.handleProtocolMessage.handleWebsocketJson(webSocket, message);
            if (response != null && !response.isEmpty()) {
                webSocket.send(response);
            }
        }
    }

    /**
     * 当连接出现异常时执行
     *
     * @param webSocket 客户端，服务端级致命错误时为 null
     * @param exception 异常
     */
    @Override
    public void onError(WebSocket webSocket, Exception exception) {
        this.logger.warn(
                WebsocketConstantMessage.Server.CONNECTION_ERROR,
                getClientAddress(webSocket),
                resolveErrorMessage(exception));
        if (webSocket == null && serverFailureHandler != null) {
            try {
                serverFailureHandler.accept(this);
            } catch (RuntimeException callbackError) {
                this.logger.error("处理 WebSocket Server 生命周期错误时发生异常", callbackError);
            }
        }
    }

    /**
     * 解析异常消息
     *
     * <p>{@code getMessage()} 可能为 null，此时回退到类名，避免日志出现无意义的 {@code null}。
     *
     * @param exception 异常，允许为 null
     * @return 可读的错误信息
     */
    private static String resolveErrorMessage(Exception exception) {
        if (exception == null) {
            return "unknown";
        }
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? exception.getClass().getSimpleName() : message;
    }

    /** 当服务器启动时执行 */
    @Override
    public void onStart() {
        this.logger.info(WebsocketConstantMessage.Server.SERVER_STARTING, hostName, port);
    }

    /**
     * 启动服务器（登记启动意图）
     *
     * <p>{@code WebSocketServer#start()} 只是新建线程后立即返回，因此"已调用 start"与
     * "线程已在运行"之间存在窗口；登记启动意图后，{@link #isTerminated()} 才不会在
     * 该窗口里误报"已终止"。
     */
    @Override
    public void start() {
        this.startRequested = true;
        super.start();
    }

    /**
     * selector 线程入口（登记线程）
     *
     * <p>在 {@code super.run()} 之前登记：此时还没有 bind，但线程已经存在，
     * 关闭方可以据此判断"还有活着的 selector 线程需要等待/中断"。
     */
    @Override
    public void run() {
        this.selectorThread = Thread.currentThread();
        super.run();
    }

    /**
     * 判断 selector 线程是否已经退出
     *
     * <p><b>只表示"线程是否还活着"</b>。{@code WebSocketServer#stop(timeout)} 会关闭已建立的
     * 连接与解码线程池，但 <b>ServerSocketChannel 是在 selector 线程退出时的
     * {@code doServerShutdown()} 里才关闭的</b>（{@code DefaultWebSocketServerFactory#close()}
     * 只关闭线程池）。因此线程还活着时端口可能仍处于监听状态——这正是
     * {@link #stopBounded(int, String)} 需要"中断兜底"的原因。
     *
     * <p>从未请求过启动时返回 {@code true}（没有可等待的线程）；已请求启动但线程尚未
     * 进入 {@link #run()} 时返回 {@code false}（不能提前宣称已终止）。
     *
     * @return true 表示 selector 线程已终止
     */
    public boolean isTerminated() {
        Thread thread = this.selectorThread;
        if (thread != null) {
            return !thread.isAlive();
        }
        return !this.startRequested;
    }

    /**
     * 有界停止服务器
     *
     * <p>为什么要分两步：
     * <ol>
     *     <li>{@link org.java_websocket.server.WebSocketServer#stop(int, String)} 内部是
     *         {@code selectorthread.join(timeout)}，<b>timeout 传 0 等于无限等待</b>；</li>
     *     <li>它并不会关闭 ServerSocketChannel——那发生在 selector 线程退出的
     *         {@code doServerShutdown()} 里。线程卡住时端口会一直可连。</li>
     * </ol>
     * 因此这里先按给定上限等待，超时则<b>中断 selector 线程</b>（{@code select()} 与
     * 缓冲区等待都可中断），再等一小段宽限时间让它走到 {@code doServerShutdown()}。
     *
     * <p>本方法不会抛出 {@link InterruptedException}：调用方（reload）随后还要读取配置、
     * 建立新的 RCON 连接，中断标记会让这些可中断操作立即失败。中断只作为"停止失败"记录。
     *
     * @param timeoutMillis 等待 selector 线程自行退出的上限（毫秒），必须大于 0
     * @param reason        关闭原因（纯文本）
     * @return true 表示 selector 线程已退出（ServerSocketChannel 已关闭）；
     *         false 表示已发出中断但线程仍未退出，端口可能仍在监听
     */
    public boolean stopBounded(int timeoutMillis, String reason) {
        if (timeoutMillis <= 0) {
            // 防回归：timeout 为 0 会让 WebSocketServer#stop 无限等待 selector 线程
            throw new IllegalArgumentException("timeoutMillis 必须大于 0，实际 " + timeoutMillis);
        }
        try {
            stop(timeoutMillis, reason);
        } catch (InterruptedException e) {
            // 有意不恢复中断标记，理由见方法注释
            this.logger.warn("等待 WebSocket Server 停止时被中断，改为中断 selector 线程");
        }
        if (isTerminated()) {
            return true;
        }

        Thread thread = this.selectorThread;
        if (thread == null) {
            // 已请求启动但线程还没进入 run()：它一旦进入就会看到 isclosed 并立即退出，
            // 这里只需要有界等待状态翻转。
            return awaitTerminatedState(SELECTOR_TERMINATION_GRACE_MILLIS);
        }

        thread.interrupt();
        try {
            thread.join(SELECTOR_TERMINATION_GRACE_MILLIS);
        } catch (InterruptedException e) {
            // 同上：不恢复中断标记
            this.logger.warn("等待 selector 线程退出时被中断");
        }
        return !thread.isAlive();
    }

    /**
     * 有界轮询等待"已终止"状态翻转
     *
     * <p>只在拿不到 selector 线程引用时使用（线程尚未进入 {@link #run()}），
     * 此时无法 {@code join}，只能轮询状态。
     *
     * @param timeoutMillis 等待上限
     * @return 是否已终止
     */
    private boolean awaitTerminatedState(int timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (isTerminated()) {
                return true;
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                return isTerminated();
            }
        }
        return isTerminated();
    }
}
