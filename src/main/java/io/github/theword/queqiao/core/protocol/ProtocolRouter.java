package io.github.theword.queqiao.core.protocol;

import io.github.theword.queqiao.core.api.Api;
import io.github.theword.queqiao.core.api.ApiRegistry;
import io.github.theword.queqiao.core.constant.BaseConstant;
import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.exception.protocol.ProtocolException;
import io.github.theword.queqiao.core.handle.HandleApiService;
import io.github.theword.queqiao.core.payload.BasePayload;
import io.github.theword.queqiao.core.protocol.handler.GetStatusHandler;
import io.github.theword.queqiao.core.protocol.handler.SendActionBarHandler;
import io.github.theword.queqiao.core.protocol.handler.SendCommandHandler;
import io.github.theword.queqiao.core.protocol.handler.SendRconCommandHandler;
import io.github.theword.queqiao.core.protocol.handler.SendTitleHandler;
import io.github.theword.queqiao.core.protocol.handler.status.ServerStatusCollector;
import io.github.theword.queqiao.core.response.Response;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;

/**
 * 协议路由器
 *
 * <p>把 {@code api} 字段映射到对应的 {@link Api}。映射关系<b>不</b>由本类内置的
 * 固定表决定，而是来自 {@link ApiRegistry}——因此 Core 自带的 API、Addon 的 API
 * 与平台按自身能力注册的 API 走的是同一条路由。
 *
 * <p><b>本类注册什么</b>：Core 自带的<b>默认</b> API（broadcast / send_msg / send_title /
 * send_actionbar / send_command / send_rcon_command / get_status）由本类在构造阶段注册。
 * <b>不</b>注册 {@code send_private_msg}——它已迁到
 * {@code io.github.theword.queqiao.core.api.standard.PrivateMessageApi}，
 * 由平台按"自己有没有玩家能力"决定是否注册；未注册时客户端会得到 404。
 *
 * <p><b>线程安全</b>：注册只在构造阶段发生（此时 {@link ApiRegistry} 尚未冻结），
 * 之后路由只做无锁读取，因此本类构造后即不可变、可被多连接并发使用。
 *
 * <p><b>不依赖全局状态</b>：日志实现、平台 API 实现、RCON 执行器与状态采集器
 * 均由构造器注入，不再访问任何静态全局状态——使协议层可脱离全局上下文独立测试
 * （包括此前无法验证的"成功路径"）。
 *
 * @since 0.6.11
 */
public class ProtocolRouter {

    private final Logger logger;

    /**
     * 平台 API 实现，允许为 null（运行时空对象状态）
     */
    private final HandleApiService handleApiService;

    private final RconCommandExecutor rconCommandExecutor;

    /**
     * API 注册中心，由 QueQiaoRuntime 创建并注入
     */
    private final ApiRegistry apiRegistry;

    /**
     * 构造路由器
     *
     * @param logger               日志实现，不得为 null
     * @param handleApiService     平台 API 实现，允许为 null（未初始化状态）
     * @param rconCommandExecutor  RCON 命令执行器，不得为 null
     * @param serverStatusCollector 状态采集器（Runtime 实例级），不得为 null
     * @param apiRegistry          API 注册中心，不得为 null；本类会把 Core 默认 API 注册进去
     */
    public ProtocolRouter(
            Logger logger,
            HandleApiService handleApiService,
            RconCommandExecutor rconCommandExecutor,
            ServerStatusCollector serverStatusCollector,
            ApiRegistry apiRegistry) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.handleApiService = handleApiService;
        this.rconCommandExecutor = Objects.requireNonNull(rconCommandExecutor, "rconCommandExecutor");
        this.apiRegistry = Objects.requireNonNull(apiRegistry, "apiRegistry");

        registerDefaultApis(serverStatusCollector);
    }

    /**
     * 注册 Core 默认 API
     *
     * <p><b>默认集合刻意保持最小</b>：只包含"不依赖平台能力差异"的 API。
     * {@code broadcast} / {@code send_msg} 与 {@code send_private_msg} 都<b>不在</b>默认集合内，
     * 由平台按自身能力决定是否注册——未注册时协议层返回 404。
     * 这正是"对外开放哪些 API 由 Runtime / 平台决定，而不是由 Core 硬编码"的落地。
     *
     * <p>相关实现：
     * {@code io.github.theword.queqiao.core.api.standard.BroadcastApi}（需平台提供
     * {@code BroadcastService}）、
     * {@code io.github.theword.queqiao.core.api.standard.PrivateMessageApi}（需平台提供
     * {@code PlayerProvider} 与 {@code PlayerMessageSender}）。
     */
    private void registerDefaultApis(ServerStatusCollector serverStatusCollector) {
        apiRegistry.register(new SendTitleHandler(ProtocolConstants.Api.SEND_TITLE, logger, handleApiService));
        apiRegistry.register(new SendActionBarHandler(ProtocolConstants.Api.SEND_ACTIONBAR, logger, handleApiService));
        apiRegistry.register(new SendCommandHandler(ProtocolConstants.Api.SEND_COMMAND, logger, handleApiService));
        apiRegistry.register(new SendRconCommandHandler(
                ProtocolConstants.Api.SEND_RCON_COMMAND, logger, handleApiService, rconCommandExecutor));
        apiRegistry.register(new GetStatusHandler(
                ProtocolConstants.Api.GET_STATUS, logger, handleApiService, serverStatusCollector));
    }

    /**
     * 路由并处理一个请求
     *
     * <p>状态码语义：
     * <ul>
     *     <li>{@code 400} —— 请求本身不合法（payload 为 null、缺少 api 字段、负载解析失败）</li>
     *     <li>{@code 404} —— api 未注册（包含"平台没有注册该能力"的情况）</li>
     *     <li>处理器抛出的 {@link ProtocolException} —— 使用其自带状态码（如 400 / 500 / 503）</li>
     *     <li>{@code 500} —— 真正的服务端未预期异常</li>
     * </ul>
     *
     * @param payload 请求负载，允许为 null
     * @return 响应，永不为 null
     */
    public Response route(BasePayload payload) {
        if (payload == null) {
            this.logger.warn("请求负载为空，已拒绝");
            return Response.failed(ProtocolConstants.Status.BAD_REQUEST, ProtocolConstants.Message.PARSE_MESSAGE_FAILED);
        }

        String api = payload.getApi();
        if (api == null || api.trim().isEmpty()) {
            this.logger.warn("请求缺少 api 字段，已拒绝");
            return Response.failed(ProtocolConstants.Status.BAD_REQUEST, ProtocolConstants.Message.MISSING_API);
        }

        Optional<Api> registered = apiRegistry.find(api);
        if (registered.isEmpty()) {
            this.logger.warn(BaseConstant.UNKNOWN_API + "{}", api);
            return Response.failed(ProtocolConstants.Status.NOT_FOUND, BaseConstant.UNKNOWN_API + api);
        }

        try {
            Object data = registered.get().handle(payload.getData());
            return Response.success(data);
        } catch (ProtocolException e) {
            return Response.failed(e.getCode(), e.getMessage(), e.getData());
        } catch (Exception e) {
            // 只有真正的内部异常才归 500；此处记录堆栈但不回传任何请求内容
            this.logger.error("处理 api={} 的请求时发生未预期异常", api, e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return Response.failed(ProtocolConstants.Status.INTERNAL_ERROR, message);
        }
    }
}
