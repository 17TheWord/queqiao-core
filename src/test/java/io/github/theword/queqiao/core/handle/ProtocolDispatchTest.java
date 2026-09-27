package io.github.theword.queqiao.core.handle;

import io.github.theword.queqiao.core.api.ApiRegistry;
import io.github.theword.queqiao.core.api.platform.PlayerMessageSender;
import io.github.theword.queqiao.core.api.platform.PlayerProvider;
import io.github.theword.queqiao.core.api.standard.PrivateMessageApi;
import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.event.model.PlayerModel;
import io.github.theword.queqiao.core.exception.rcon.RconException;
import io.github.theword.queqiao.core.protocol.RconCommandExecutor;
import io.github.theword.queqiao.core.service.PrivateMessageService;
import io.github.theword.queqiao.core.support.PlatformStubs;
import io.github.theword.queqiao.core.support.PlayerStubs;
import io.github.theword.queqiao.core.response.Response;
import com.google.gson.Gson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协议分发矩阵测试
 *
 * <p>覆盖 WS-B2（状态码语义）、WS-B3（错误响应不回传原始请求体）、
 * WS-B5（Payload 输入校验）以及 B4 解耦后新增可测的"未知 api"分支。
 *
 * <p>统一走 {@code handleHttpJson} 入口：它与 WebSocket 入口共用同一套
 * 解析与路由逻辑，但不需要构造 {@code WebSocket}，因此测试无网络依赖。
 */
class ProtocolDispatchTest {

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ProtocolDispatchTest.class);
    private static final Gson GSON = new Gson();

    private static final int BAD_REQUEST = ProtocolConstants.Status.BAD_REQUEST;
    private static final int NOT_FOUND = ProtocolConstants.Status.NOT_FOUND;
    private static final int INTERNAL_ERROR = ProtocolConstants.Status.INTERNAL_ERROR;

    /**
     * 分发一个请求并解析回 {@link Response}（使用空平台实现）
     */
    private static Response dispatch(String rawJson) {
        return dispatch(rawJson, PlatformStubs.noopApiService(), PlatformStubs.rconExecutorReturning(""));
    }

    /**
     * 分发一个请求并解析回 {@link Response}（指定平台实现与 RCON 执行器）
     */
    private static Response dispatch(String rawJson, HandleApiService apiService, RconCommandExecutor rconCommandExecutor) {
        return dispatch(rawJson, apiService, rconCommandExecutor, new ApiRegistry());
    }

    /**
     * 分发一个请求并解析回 {@link Response}（指定平台实现、RCON 执行器与 API 注册中心）
     */
    private static Response dispatch(
            String rawJson,
            HandleApiService apiService,
            RconCommandExecutor rconCommandExecutor,
            ApiRegistry apiRegistry) {
        String responseJson = PlatformStubs.newDispatcher(LOGGER, GSON, apiService, rconCommandExecutor, apiRegistry)
                .handleHttpJson(rawJson);
        Response response = GSON.fromJson(responseJson, Response.class);
        assertNotNull(response, "分发结果不应为 null，原始响应=" + responseJson);
        assertNotNull(response.getCode(), "响应应带状态码，原始响应=" + responseJson);
        return response;
    }

    /**
     * 构造一个已注册私聊 API 的注册中心
     *
     * <p>私聊已迁移到新的 API SPI：Core <b>不再默认注册</b> {@code send_private_msg}，
     * 因此要验证它的协议语义，必须像平台适配器那样先注册
     * {@link PrivateMessageApi}（未注册时协议层返回 404）。
     */
    private static ApiRegistry privateMessageRegistry(PlayerProvider provider, PlayerMessageSender sender) {
        ApiRegistry registry = new ApiRegistry();
        registry.register(new PrivateMessageApi(new PrivateMessageService(provider, sender)));
        return registry;
    }

    // ------------------------------------------------------------------
    // 解析阶段：调用方错误一律 400，不再伪装成 500
    // ------------------------------------------------------------------

    @Test
    @DisplayName("非法 JSON 请求体返回 400")
    void malformedJsonReturnsBadRequest() {
        assertEquals(BAD_REQUEST, dispatch("{\"api\":\"broadcast\"").getCode().intValue());
    }

    @Test
    @DisplayName("JSON 数组作为请求体返回 400")
    void jsonArrayBodyReturnsBadRequest() {
        assertEquals(BAD_REQUEST, dispatch("[1,2,3]").getCode().intValue());
    }

    @Test
    @DisplayName("字面量 null 请求体返回 400")
    void nullLiteralBodyReturnsBadRequest() {
        assertEquals(BAD_REQUEST, dispatch("null").getCode().intValue());
    }

    @Test
    @DisplayName("缺少 api 字段返回 400")
    void missingApiReturnsBadRequest() {
        assertEquals(BAD_REQUEST, dispatch("{\"data\":{}}").getCode().intValue());
    }

    @Test
    @DisplayName("api 为纯空白返回 400")
    void blankApiReturnsBadRequest() {
        assertEquals(BAD_REQUEST, dispatch("{\"api\":\"   \"}").getCode().intValue());
    }

    // ------------------------------------------------------------------
    // 路由阶段
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未注册的 api 返回 404")
    void unknownApiReturnsNotFound() {
        assertEquals(NOT_FOUND, dispatch("{\"api\":\"no_such_api\"}").getCode().intValue());
    }

    @Test
    @DisplayName("已注册但不受支持的 api 返回 500")
    void unsupportedApiReturnsInternalError() {
        assertEquals(INTERNAL_ERROR, dispatch("{\"api\":\"send_command\"}").getCode().intValue());
    }

    @Test
    @DisplayName("send_title 缺少 title 与 subtitle 返回 400")
    void sendTitleWithoutContentReturnsBadRequest() {
        assertEquals(BAD_REQUEST, dispatch("{\"api\":\"send_title\",\"data\":{}}").getCode().intValue());
    }

    // ------------------------------------------------------------------
    // WS-B5 输入校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("send_title 的 fade_in 为负数返回 400")
    void sendTitleWithNegativeDurationReturnsBadRequest() {
        String body = "{\"api\":\"send_title\",\"data\":{\"title\":{\"text\":\"hi\"},\"fade_in\":-1}}";
        Response response = dispatch(body);

        assertEquals(BAD_REQUEST, response.getCode().intValue());
        assertEquals(ProtocolConstants.Message.TITLE_DURATION_NEGATIVE, response.getMessage());
    }

    @Test
    @DisplayName("send_title 的 stay 超出上限返回 400")
    void sendTitleWithTooLargeDurationReturnsBadRequest() {
        String body = "{\"api\":\"send_title\",\"data\":{\"title\":{\"text\":\"hi\"},\"stay\":999999}}";
        Response response = dispatch(body);

        assertEquals(BAD_REQUEST, response.getCode().intValue());
        assertEquals(ProtocolConstants.Message.TITLE_DURATION_TOO_LARGE, response.getMessage());
    }

    @Test
    @DisplayName("send_title 的合法时长通过校验并真正调用平台实现")
    void sendTitleWithValidDurationReachesPlatform() {
        String body = "{\"api\":\"send_title\",\"data\":{\"title\":{\"text\":\"hi\"},\"fade_in\":10,\"stay\":70,\"fade_out\":20}}";
        PlatformStubs.RecordingApiService apiService = PlatformStubs.recordingApiService();

        Response response = dispatch(body, apiService, PlatformStubs.rconExecutorReturning(""));

        assertEquals(ProtocolConstants.Status.SUCCESS, response.getCode().intValue(), "合法请求应成功");
        assertEquals(1, apiService.getTitleCalls().size(), "应真正调用平台实现一次：" + apiService.getTitleCalls());
        assertTrue(apiService.getTitleCalls().get(0).contains("fadeIn=10"), "应透传时长参数：" + apiService.getTitleCalls());
    }

    @Test
    @DisplayName("send_private_msg 的 nickname 为纯空白返回 400（平台已注册私聊 API）")
    void sendPrivateMessageWithBlankNicknameReturnsBadRequest() {
        String body = "{\"api\":\"send_private_msg\",\"data\":{\"nickname\":\"   \",\"message\":\"hi\"}}";
        ApiRegistry registry = privateMessageRegistry(
                new PlayerStubs.FakePlayerProvider(), new PlayerStubs.RecordingPlayerMessageSender());

        assertEquals(
                BAD_REQUEST,
                dispatch(body, PlatformStubs.noopApiService(), PlatformStubs.rconExecutorReturning(""), registry)
                        .getCode().intValue());
    }

    @Test
    @DisplayName("send_rcon_command 的命令为空白返回 400")
    void sendRconCommandWithBlankCommandReturnsBadRequest() {
        String body = "{\"api\":\"send_rcon_command\",\"data\":{\"command\":\"   \"}}";
        Response response = dispatch(body);

        assertEquals(BAD_REQUEST, response.getCode().intValue());
        assertEquals(ProtocolConstants.Message.RCON_COMMAND_EMPTY, response.getMessage());
    }

    // ------------------------------------------------------------------
    // 成功路径（WS-F：协议层注入平台能力后才可验证）
    //
    // 在依赖静态全局状态的时期，平台实现只能为 null，
    // 这些用例最多只能断言"不是 400"（实际落到 500）——无法验证成功路径。
    // ------------------------------------------------------------------

    @Test
    @DisplayName("broadcast 成功路径真正调用平台 BroadcastService（已注册广播 API）")
    void broadcastReachesPlatform() {
        PlatformStubs.RecordingBroadcastService broadcastService = new PlatformStubs.RecordingBroadcastService();

        Response response = dispatch(
                "{\"api\":\"broadcast\",\"data\":{\"message\":{\"text\":\"hi\"}}}",
                PlatformStubs.noopApiService(),
                PlatformStubs.rconExecutorReturning(""),
                PlatformStubs.registryWithBroadcast(broadcastService));

        assertEquals(ProtocolConstants.Status.SUCCESS, response.getCode().intValue(), "合法请求应成功");
        assertEquals(1, broadcastService.getCount(), "应调用平台广播：" + broadcastService.getBroadcasts());
    }

    @Test
    @DisplayName("send_private_msg 成功路径调用平台 SPI 并归一化昵称")
    void privateMessageReachesPlatformWithTrimmedNickname() {
        PlayerModel steve = new PlayerModel("Steve", UUID.randomUUID());
        PlayerStubs.FakePlayerProvider provider = new PlayerStubs.FakePlayerProvider(steve);
        PlayerStubs.RecordingPlayerMessageSender sender = new PlayerStubs.RecordingPlayerMessageSender();
        ApiRegistry registry = privateMessageRegistry(provider, sender);

        Response response = dispatch(
                "{\"api\":\"send_private_msg\",\"data\":{\"nickname\":\"  Steve  \",\"message\":{\"text\":\"hi\"}}}",
                PlatformStubs.noopApiService(),
                PlatformStubs.rconExecutorReturning(""),
                registry);

        assertEquals(ProtocolConstants.Status.SUCCESS, response.getCode().intValue(), "合法请求应成功");
        assertEquals(1, sender.getSendCount(), "应调用平台发送能力一次：" + sender.getTargets());
        assertSame(
                steve,
                sender.getLastTarget(),
                "昵称 trim 后应命中 Steve —— 归一化规则由 Core 决定");
    }

    @Test
    @DisplayName("send_rcon_command 成功路径返回执行结果")
    void rconCommandReturnsExecutionResult() {
        Response response = dispatch(
                "{\"api\":\"send_rcon_command\",\"data\":{\"command\":\"list\"}}",
                PlatformStubs.noopApiService(),
                PlatformStubs.rconExecutorReturning("There are 3 players"));

        assertEquals(ProtocolConstants.Status.SUCCESS, response.getCode().intValue(), "合法请求应成功");
        assertEquals("There are 3 players", response.getData(), "应返回 Rcon 执行结果");
    }

    @Test
    @DisplayName("Rcon 未启用时返回 503（服务暂不可用）")
    void rconDisabledReturnsServiceUnavailable() {
        Response response = dispatch(
                "{\"api\":\"send_rcon_command\",\"data\":{\"command\":\"list\"}}",
                PlatformStubs.noopApiService(),
                PlatformStubs.rconExecutorFailing(RconException.Kind.DISABLED));

        assertEquals(
                ProtocolConstants.Status.SERVICE_UNAVAILABLE,
                response.getCode().intValue(),
                "未启用属服务不可用，不应判为调用方参数错误");
    }

    @Test
    @DisplayName("Rcon 命令执行失败时返回 500")
    void rconCommandFailedReturnsInternalError() {
        Response response = dispatch(
                "{\"api\":\"send_rcon_command\",\"data\":{\"command\":\"list\"}}",
                PlatformStubs.noopApiService(),
                PlatformStubs.rconExecutorFailing(RconException.Kind.COMMAND_FAILED));

        assertEquals(ProtocolConstants.Status.INTERNAL_ERROR, response.getCode().intValue());
    }

    @Test
    @DisplayName("Rcon 命令参数不合法时返回 400")
    void invalidRconCommandReturnsBadRequest() {
        Response response = dispatch(
                "{\"api\":\"send_rcon_command\",\"data\":{\"command\":\"list\"}}",
                PlatformStubs.noopApiService(),
                PlatformStubs.rconExecutorFailing(RconException.Kind.INVALID_COMMAND));

        assertEquals(ProtocolConstants.Status.BAD_REQUEST, response.getCode().intValue());
    }

    // ------------------------------------------------------------------
    // 响应契约
    // ------------------------------------------------------------------

    @Test
    @DisplayName("echo 原样回传")
    void echoIsReturnedAsIs() {
        assertEquals("req-42", dispatch("{\"api\":\"no_such_api\",\"echo\":\"req-42\"}").getEcho());
    }

    @Test
    @DisplayName("api 原样回传")
    void apiIsReturnedAsIs() {
        assertEquals("no_such_api", dispatch("{\"api\":\"no_such_api\"}").getApi());
    }

    @Test
    @DisplayName("post_type 固定为 response")
    void postTypeIsAlwaysResponse() {
        assertEquals("response", dispatch("{\"api\":\"no_such_api\"}").getPostType());
    }

    /**
     * WS-B3 回归：错误响应不得回传原始请求体
     *
     * <p>修复前解析失败会把 {@code rawJsonMessage} 塞进 {@code data} 原样返回，
     * 既放大了响应体积，也可能把敏感内容回显给调用方。
     */
    @Test
    @DisplayName("解析失败时响应不回传原始请求体（WS-B3 回归）")
    void parseFailureResponseDoesNotEchoRawRequest() {
        String marker = "s3cr3t-payload-marker";
        String malformedBody = "{\"api\":\"broadcast\",\"data\":{\"note\":\"" + marker + "\"";

        String responseJson = PlatformStubs.newDispatcher(LOGGER, GSON).handleHttpJson(malformedBody);

        assertFalse(responseJson.contains(marker), "响应不得包含原始请求内容：" + responseJson);
        assertFalse(responseJson.contains("rawJsonMessage"), "响应不得包含 rawJsonMessage 字段：" + responseJson);
    }
}
