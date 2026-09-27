package io.github.theword.queqiao.core.handle;

import io.github.theword.queqiao.core.api.ApiRegistry;
import io.github.theword.queqiao.core.constant.CommonConstants;
import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.payload.BasePayload;
import io.github.theword.queqiao.core.protocol.ProtocolRouter;
import io.github.theword.queqiao.core.protocol.RconCommandExecutor;
import io.github.theword.queqiao.core.protocol.handler.status.ServerStatusCollector;
import io.github.theword.queqiao.core.response.Response;
import io.github.theword.queqiao.core.utils.LogSanitizer;
import io.github.theword.queqiao.core.utils.RuntimeUtils;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import org.java_websocket.WebSocket;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Objects;

/**
 * 处理协议消息
 *
 * <p>这是<b>传输无关</b>的协议分发入口：WebSocket（以及未来的 HTTP）都复用同一套解析与路由。
 *
 * <p><b>生命周期与线程安全</b>：本类由 {@code QueQiaoRuntime} 创建唯一实例并注入给各传输层
 * （{@code WebsocketManager} → 各 {@code WsClient} / {@code WsServer}），
 * 因此会被多个连接、多个线程并发调用。
 *
 * <p>本类构造后即不可变：字段全部 {@code final}，
 * {@link ProtocolRouter} 的处理器表也只在构造阶段写入、之后只读；
 * 各处理器实现必须无状态（见 {@code AbstractProtocolHandler}）。
 * 因此并发调用是安全的，且<b>不存在锁</b>——不同连接之间不会相互串行化。
 *
 * <p>顺序说明：单个连接内的请求处理顺序由该连接的读线程串行保证，
 * 与本类实例数量无关。
 *
 * <p><b>状态码语义</b>：请求体不是合法 JSON 或为 null 时返回 400（调用方错误），
 * 而不是把调用方错误伪装成服务端内部错误。
 *
 * <p><b>日志安全</b>：debug 日志会先对 token / password 等敏感字段脱敏、再按长度截断；
 * warn / error 日志不输出请求体内容，只输出长度。
 *
 * @since 0.6.11
 */
public class HandleProtocolMessage {

    private final Gson gson;
    private final Logger logger;
    private final ProtocolRouter protocolRouter;

    /**
     * Runtime 作用域辅助能力（由 QueQiaoRuntime 注入）
     *
     * <p>本类的 debug 日志门控与输出都通过它完成，因此不再读取任何全局状态。
     */
    private final RuntimeUtils utils;

    public HandleProtocolMessage(
            Logger logger,
            Gson gson,
            HandleApiService handleApiService,
            RconCommandExecutor rconCommandExecutor,
            RuntimeUtils utils,
            ServerStatusCollector serverStatusCollector,
            ApiRegistry apiRegistry) {
        this.logger = logger;
        this.gson = gson;
        // 与 WebsocketManager 一致：注入依赖为 null 属接线缺陷，在此快速失败
        this.utils = Objects.requireNonNull(utils, "utils");
        this.protocolRouter = new ProtocolRouter(
                logger,
                handleApiService,
                rconCommandExecutor,
                serverStatusCollector,
                Objects.requireNonNull(apiRegistry, "apiRegistry"));
    }

    /**
     * 消息来源枚举
     */
    private enum MessageSource {
        HTTP, WEBSOCKET;

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * WebSocket入口，处理JSON字符串
     *
     * @param webSocket      WebSocket连接
     * @param rawJsonMessage 收到的JSON字符串
     * @return 响应的JSON字符串
     */
    public String handleWebsocketJson(WebSocket webSocket, String rawJsonMessage) {
        Response response = this.handle(rawJsonMessage, webSocket.getRemoteSocketAddress().toString(), MessageSource.WEBSOCKET);
        return gson.toJson(response);
    }

    /**
     * Http，处理JSON字符串
     *
     * <p><b>保留入口</b>：当前版本尚未接入 HTTP 传输层，本方法暂无调用方。
     * 它是有意保留的扩展点——协议分发与传输方式无关，
     * 后续接入 HTTP 时应直接复用本入口，而不是另起一套解析与路由。
     * 因此请勿将其当作"死代码"清理。
     *
     * @param rawJsonMessage 收到的JSON字符串
     * @return 响应的JSON字符串
     */
    public String handleHttpJson(String rawJsonMessage) {
        Response response = this.handle(rawJsonMessage, CommonConstants.Text.EMPTY, MessageSource.HTTP);
        return gson.toJson(response);
    }

    private Response handle(String rawJsonMessage, String address, MessageSource source) {
        // 脱敏与截断只在开启 debug 时执行，避免每条消息都多解析一次 JSON
        if (utils.isDebugEnabled()) {
            utils.debugLog(
                    "收到来自 {} 的 {} 消息（原始长度 {}）：{}",
                    address, source, lengthOf(rawJsonMessage), LogSanitizer.sanitize(rawJsonMessage));
        }

        BasePayload basePayload;
        try {
            basePayload = gson.fromJson(rawJsonMessage, BasePayload.class);
        } catch (JsonParseException e) {
            // 请求体不是合法 JSON → 调用方错误（400），不是服务端内部错误
            this.logger.warn(
                    "解析来自 {} 的 {} 消息失败：请求体不是合法 JSON（原始长度 {}）", address, source, lengthOf(rawJsonMessage));
            return Response.failed(ProtocolConstants.Status.BAD_REQUEST, ProtocolConstants.Message.PARSE_MESSAGE_FAILED);
        } catch (RuntimeException e) {
            this.logger.error("解析来自 {} 的 {} 消息时发生未预期异常", address, source, e);
            return Response.failed(ProtocolConstants.Status.INTERNAL_ERROR, ProtocolConstants.Message.PARSE_MESSAGE_FAILED);
        }

        if (basePayload == null) {
            this.logger.warn("解析来自 {} 的 {} 消息失败：请求体为 null", address, source);
            return Response.failed(ProtocolConstants.Status.BAD_REQUEST, ProtocolConstants.Message.PARSE_MESSAGE_FAILED);
        }

        Response response = this.parseAndHandle(basePayload);
        response.setApi(basePayload.getApi());
        response.setEcho(basePayload.getEcho());
        return response;
    }

    /**
     * 业务处理核心：接收原始 JSON 字符串并解析与处理
     *
     * @return Response 处理结果
     */
    public Response parseAndHandle(BasePayload basePayload) {
        return protocolRouter.route(basePayload);
    }

    private static int lengthOf(String rawJsonMessage) {
        return rawJsonMessage == null ? 0 : rawJsonMessage.length();
    }

    /**
     * 生成可安全写入 debug 日志的消息描述：先按字段脱敏，再按长度截断
     *
     * @param rawJsonMessage 原始消息
     * @return 脱敏并截断后的文本
     */
    private String describeForLog(String rawJsonMessage) {
        return LogSanitizer.sanitize(rawJsonMessage);
    }
}
