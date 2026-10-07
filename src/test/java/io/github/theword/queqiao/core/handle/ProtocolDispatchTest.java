package io.github.theword.queqiao.core.handle;

import io.github.theword.queqiao.core.api.Api;
import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.exception.protocol.ProtocolException;
import io.github.theword.queqiao.core.exception.rcon.RconException;
import io.github.theword.queqiao.core.payload.EmptyPayload;
import io.github.theword.queqiao.core.platform.AbstractPlatformContext;
import io.github.theword.queqiao.core.protocol.RconCommandExecutor;
import io.github.theword.queqiao.core.support.PlatformStubs;
import io.github.theword.queqiao.core.response.Response;
import com.google.gson.Gson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        return dispatch(rawJson, PlatformStubs.noopPlatformContext(), PlatformStubs.rconExecutorReturning(""));
    }

    /**
     * 分发一个请求并解析回 {@link Response}（指定平台上下文与 RCON 执行器）
     */
    private static Response dispatch(
            String rawJson,
            AbstractPlatformContext<?, ?, ?> platformContext,
            RconCommandExecutor rconCommandExecutor) {
        String responseJson = PlatformStubs.newDispatcher(LOGGER, GSON, platformContext, rconCommandExecutor).handleHttpJson(rawJson);
        Response response = GSON.fromJson(responseJson, Response.class);
        assertNotNull(response, "分发结果不应为 null，原始响应=" + responseJson);
        assertNotNull(response.getCode(), "响应应带状态码，原始响应=" + responseJson);
        return response;
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
    @DisplayName("已注册但不受支持的 api 返回 503（SERVICE_UNAVAILABLE，而非 500）")
    void unsupportedApiReturnsServiceUnavailable() {
        assertEquals(
                ProtocolConstants.Status.SERVICE_UNAVAILABLE,
                dispatch("{\"api\":\"send_command\"}").getCode().intValue(),
                "「API 存在但当前能力不可用」是 503；500 只表示服务端发生未预期错误");
    }

    // ------------------------------------------------------------------
    // 未预期异常脱敏
    // ------------------------------------------------------------------

    /**
     * 抛未预期异常的探针 Api
     */
    private static final class ThrowingApi extends Api<EmptyPayload, Void> {

        private ThrowingApi() {
            super(EmptyPayload.class, LOGGER);
        }

        @Override
        public String name() {
            return "boom";
        }

        @Override
        protected Void doExecute(EmptyPayload payload) {
            throw new RuntimeException("SECRET_INTERNAL_ERROR_123");
        }
    }

    /**
     * 抛预期协议异常的探针 Api
     */
    private static final class BadRequestApi extends Api<EmptyPayload, Void> {

        private BadRequestApi() {
            super(EmptyPayload.class, LOGGER);
        }

        @Override
        public String name() {
            return "boom400";
        }

        @Override
        protected Void doExecute(EmptyPayload payload) throws ProtocolException {
            throw ProtocolException.badRequest("明确的协议错误");
        }
    }

    @Test
    @DisplayName("未预期异常：回传通用 500，不泄漏异常信息 / 类名 / 堆栈")
    void unexpectedExceptionIsSanitized() {
        HandleProtocolMessage dispatcher = PlatformStubs.newDispatcher(
                LOGGER, GSON, Collections.<Api<?, ?>>singletonList(new ThrowingApi()));

        String responseJson = dispatcher.handleHttpJson("{\"api\":\"boom\"}");
        Response response = GSON.fromJson(responseJson, Response.class);

        assertEquals(INTERNAL_ERROR, response.getCode().intValue(), "未预期异常应为 500");
        assertEquals(
                ProtocolConstants.Message.INTERNAL_ERROR,
                response.getMessage(),
                "应回传稳定的通用文案");
        assertFalse(
                responseJson.contains("SECRET_INTERNAL_ERROR_123"),
                "响应不得泄漏异常信息，实际=" + responseJson);
        assertFalse(
                responseJson.contains("RuntimeException"),
                "响应不得泄漏异常类名，实际=" + responseJson);
    }

    @Test
    @DisplayName("预期协议异常仍保留其明确语义，不被通用 500 吞掉")
    void expectedProtocolExceptionKeepsItsSemantics() {
        HandleProtocolMessage dispatcher = PlatformStubs.newDispatcher(
                LOGGER, GSON, Collections.<Api<?, ?>>singletonList(new BadRequestApi()));

        Response response = GSON.fromJson(dispatcher.handleHttpJson("{\"api\":\"boom400\"}"), Response.class);

        assertEquals(BAD_REQUEST, response.getCode().intValue(), "ProtocolException 应保留自身状态码");
        assertEquals("明确的协议错误", response.getMessage(), "ProtocolException 应保留自身文案");
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
        PlatformStubs.RecordingPlatformContext platformContext = PlatformStubs.recordingPlatformContext();

        Response response = dispatch(body, platformContext, PlatformStubs.rconExecutorReturning(""));

        assertEquals(ProtocolConstants.Status.SUCCESS, response.getCode().intValue(), "合法请求应成功");
        assertEquals(1, platformContext.getTitleCalls().size(), "应真正调用平台实现一次：" + platformContext.getTitleCalls());
        assertTrue(platformContext.getTitleCalls().get(0).contains("fadeIn=10"), "应透传时长参数：" + platformContext.getTitleCalls());
    }

    @Test
    @DisplayName("send_private_msg 的 nickname 为纯空白返回 400")
    void sendPrivateMessageWithBlankNicknameReturnsBadRequest() {
        String body = "{\"api\":\"send_private_msg\",\"data\":{\"nickname\":\"   \",\"message\":\"hi\"}}";
        assertEquals(BAD_REQUEST, dispatch(body).getCode().intValue());
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
    @DisplayName("broadcast 成功路径真正调用平台实现")
    void broadcastReachesPlatform() {
        PlatformStubs.RecordingPlatformContext platformContext = PlatformStubs.recordingPlatformContext();

        Response response = dispatch(
                "{\"api\":\"broadcast\",\"data\":{\"message\":{\"text\":\"hi\"}}}",
                platformContext,
                PlatformStubs.rconExecutorReturning(""));

        assertEquals(ProtocolConstants.Status.SUCCESS, response.getCode().intValue(), "合法请求应成功");
        assertEquals(1, platformContext.getBroadcasts().size(), "应调用平台广播：" + platformContext.getBroadcasts());
    }

    @Test
    @DisplayName("send_private_msg 成功路径调用平台实现并归一化昵称")
    void privateMessageReachesPlatformWithTrimmedNickname() {
        PlatformStubs.RecordingPlatformContext platformContext = PlatformStubs.recordingPlatformContext();

        Response response = dispatch(
                "{\"api\":\"send_private_msg\",\"data\":{\"nickname\":\"  Steve  \",\"message\":{\"text\":\"hi\"}}}",
                platformContext,
                PlatformStubs.rconExecutorReturning(""));

        assertEquals(ProtocolConstants.Status.SUCCESS, response.getCode().intValue(), "合法请求应成功");
        assertEquals(1, platformContext.getPrivateMessages().size(), "应调用平台私聊：" + platformContext.getPrivateMessages());
        assertTrue(
                platformContext.getPrivateMessages().get(0).contains("nickname=Steve"),
                "昵称应被 trim 后传递：" + platformContext.getPrivateMessages());
    }

    @Test
    @DisplayName("send_rcon_command 成功路径返回执行结果")
    void rconCommandReturnsExecutionResult() {
        Response response = dispatch(
                "{\"api\":\"send_rcon_command\",\"data\":{\"command\":\"list\"}}",
                PlatformStubs.noopPlatformContext(),
                PlatformStubs.rconExecutorReturning("There are 3 players"));

        assertEquals(ProtocolConstants.Status.SUCCESS, response.getCode().intValue(), "合法请求应成功");
        assertEquals("There are 3 players", response.getData(), "应返回 Rcon 执行结果");
    }

    @Test
    @DisplayName("Rcon 未启用时返回 503（服务暂不可用）")
    void rconDisabledReturnsServiceUnavailable() {
        Response response = dispatch(
                "{\"api\":\"send_rcon_command\",\"data\":{\"command\":\"list\"}}",
                PlatformStubs.noopPlatformContext(),
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
                PlatformStubs.noopPlatformContext(),
                PlatformStubs.rconExecutorFailing(RconException.Kind.COMMAND_FAILED));

        assertEquals(ProtocolConstants.Status.INTERNAL_ERROR, response.getCode().intValue());
    }

    @Test
    @DisplayName("Rcon 命令参数不合法时返回 400")
    void invalidRconCommandReturnsBadRequest() {
        Response response = dispatch(
                "{\"api\":\"send_rcon_command\",\"data\":{\"command\":\"list\"}}",
                PlatformStubs.noopPlatformContext(),
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
