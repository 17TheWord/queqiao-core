package io.github.theword.queqiao.core.api;

import java.util.Collections;
import java.util.Map;

import com.google.gson.Gson;
import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.exception.protocol.ProtocolException;
import io.github.theword.queqiao.core.handle.HandleProtocolMessage;
import io.github.theword.queqiao.core.payload.BasePayload;
import io.github.theword.queqiao.core.payload.EmptyPayload;
import io.github.theword.queqiao.core.platform.AbstractPlatformContext;
import io.github.theword.queqiao.core.platform.PlatformResult;
import io.github.theword.queqiao.core.platform.PlatformResultCode;
import io.github.theword.queqiao.core.protocol.ProtocolRouter;
import io.github.theword.queqiao.core.protocol.RconCommandExecutor;
import io.github.theword.queqiao.core.response.Response;
import io.github.theword.queqiao.core.support.PlatformStubs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlatformResult → Protocol 映射测试
 *
 * <p>验证 {@link Api#requireSuccess} / {@link Api#mapPlatformResult} 把平台结果翻译成协议状态的
 * 完整链路，以及"API 子类只需覆盖自己关心的结果码"这一扩展点。
 *
 * <p><b>同时锁定迁移前的协议行为不被改变</b>：
 * <ul>
 *     <li>平台未实现 title / actionbar → <b>503</b>（迁移前是直接抛 503）</li>
 *     <li>私聊目标玩家不存在 → <b>200 + playerNotFound 负载</b>（不是 400）</li>
 * </ul>
 */
class PlatformResultMappingTest {

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(PlatformResultMappingTest.class);
    private static final Gson GSON = new Gson();

    private static Response dispatch(AbstractPlatformContext<?, ?, ?, ?> platform, String rawJson) {
        return dispatch(platform, PlatformStubs.rconExecutorReturning(""), rawJson);
    }

    private static Response dispatch(
            AbstractPlatformContext<?, ?, ?, ?> platform,
            RconCommandExecutor rconCommandExecutor,
            String rawJson) {
        HandleProtocolMessage dispatcher =
                PlatformStubs.newDispatcher(LOGGER, GSON, platform, rconCommandExecutor);
        Response response = GSON.fromJson(dispatcher.handleHttpJson(rawJson), Response.class);
        assertNotNull(response, "分发结果不应为 null");
        assertNotNull(response.getCode(), "响应应带状态码");
        return response;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(Response response) {
        Object data = response.getData();
        assertTrue(data instanceof Map, "data 应为 JSON 对象，实际=" + data);
        return (Map<String, Object>) data;
    }

    // ------------------------------------------------------------------
    // broadcast：SUCCESS / FAILED / UNSUPPORTED
    // ------------------------------------------------------------------

    @Test
    @DisplayName("broadcast 成功 → 200，且平台确实被调用")
    void broadcastSuccess() {
        PlatformStubs.RecordingPlatformContext platform = PlatformStubs.recordingPlatformContext();

        Response response = dispatch(platform, "{\"api\":\"broadcast\",\"data\":{\"message\":{\"text\":\"hi\"}}}");

        assertEquals(ProtocolConstants.Status.SUCCESS, response.getCode().intValue());
        assertEquals(1, platform.getBroadcasts().size());
    }

    @Test
    @DisplayName("broadcast 平台失败 → 500")
    void broadcastFailed() {
        PlatformStubs.RecordingPlatformContext platform = PlatformStubs.recordingPlatformContext();
        platform.failBroadcastWith(PlatformResultCode.FAILED);

        Response response = dispatch(platform, "{\"api\":\"broadcast\",\"data\":{\"message\":{\"text\":\"hi\"}}}");

        assertEquals(ProtocolConstants.Status.INTERNAL_ERROR, response.getCode().intValue());
    }

    @Test
    @DisplayName("broadcast 平台不支持 → 503")
    void broadcastUnsupported() {
        PlatformStubs.RecordingPlatformContext platform = PlatformStubs.recordingPlatformContext();
        platform.failBroadcastWith(PlatformResultCode.UNSUPPORTED);

        Response response = dispatch(platform, "{\"api\":\"broadcast\",\"data\":{\"message\":{\"text\":\"hi\"}}}");

        assertEquals(ProtocolConstants.Status.SERVICE_UNAVAILABLE, response.getCode().intValue());
    }

    // ------------------------------------------------------------------
    // title / actionbar：UNSUPPORTED → 503（与迁移前一致）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("平台未实现标题 → 503（迁移前也是 503，行为不变）")
    void titleUnsupported() {
        PlatformStubs.RecordingPlatformContext platform = PlatformStubs.recordingPlatformContext();
        platform.setTitleSupported(false);

        Response response = dispatch(platform, "{\"api\":\"send_title\",\"data\":{\"title\":{\"text\":\"hi\"}}}");

        assertEquals(ProtocolConstants.Status.SERVICE_UNAVAILABLE, response.getCode().intValue());
        assertTrue(platform.getTitleCalls().isEmpty(), "平台不支持时不应记录调用");
    }

    @Test
    @DisplayName("平台未实现 ActionBar → 503（迁移前也是 503，行为不变）")
    void actionBarUnsupported() {
        PlatformStubs.RecordingPlatformContext platform = PlatformStubs.recordingPlatformContext();
        platform.setActionBarSupported(false);

        Response response = dispatch(platform, "{\"api\":\"send_actionbar\",\"data\":{\"message\":{\"text\":\"hi\"}}}");

        assertEquals(ProtocolConstants.Status.SERVICE_UNAVAILABLE, response.getCode().intValue());
        assertTrue(platform.getActionBars().isEmpty(), "平台不支持时不应记录调用");
    }

    @Test
    @DisplayName("平台支持标题 → 200，且时长参数透传")
    void titleSupported() {
        PlatformStubs.RecordingPlatformContext platform = PlatformStubs.recordingPlatformContext();

        Response response = dispatch(
                platform,
                "{\"api\":\"send_title\",\"data\":{\"title\":{\"text\":\"t\"},\"fade_in\":10,\"stay\":20,\"fade_out\":5}}");

        assertEquals(ProtocolConstants.Status.SUCCESS, response.getCode().intValue());
        assertEquals(1, platform.getTitleCalls().size());
        assertTrue(platform.getTitleCalls().get(0).contains("fadeIn=10"));
    }

    // ------------------------------------------------------------------
    // 私聊：PLAYER_NOT_FOUND 保持 200；发送失败 → 500
    // ------------------------------------------------------------------

    @Test
    @DisplayName("私聊目标玩家不存在 → 200 + playerNotFound 负载（迁移前语义，不是 400）")
    void privateMessagePlayerNotFoundKeepsSuccessStatus() {
        PlatformStubs.RecordingPlatformContext platform = PlatformStubs.recordingPlatformContext();

        Response response = dispatch(
                platform,
                "{\"api\":\"send_private_msg\",\"data\":{\"nickname\":\"Nobody\",\"message\":{\"text\":\"hi\"}}}");

        assertEquals(
                ProtocolConstants.Status.SUCCESS,
                response.getCode().intValue(),
                "目标玩家不存在不是协议错误，必须保持 200");
        assertEquals("Target player not found.", dataOf(response).get("message"));
        assertNull(dataOf(response).get("target_player"));
        assertTrue(platform.getPrivateMessages().isEmpty(), "未找到目标时不应发送");
    }

    @Test
    @DisplayName("私聊成功 → 200，data 含 target_player 快照")
    void privateMessageSuccess() {
        PlatformStubs.RecordingPlatformContext platform = PlatformStubs.recordingPlatformContext();

        Response response = dispatch(
                platform,
                "{\"api\":\"send_private_msg\",\"data\":{\"nickname\":\"Steve\",\"message\":{\"text\":\"hi\"}}}");

        assertEquals(ProtocolConstants.Status.SUCCESS, response.getCode().intValue());
        assertEquals(1, platform.getPrivateMessages().size());

        Object target = dataOf(response).get("target_player");
        assertNotNull(target, "成功响应应带目标玩家快照");
        assertTrue(target instanceof Map, "target_player 应为 JSON 对象");
        assertEquals("Steve", ((Map<?, ?>) target).get("nickname"));
    }

    @Test
    @DisplayName("私聊发送失败 → 500")
    void privateMessageSendFailed() {
        PlatformStubs.RecordingPlatformContext platform = PlatformStubs.recordingPlatformContext();
        platform.failSendWith(PlatformResultCode.FAILED);

        Response response = dispatch(
                platform,
                "{\"api\":\"send_private_msg\",\"data\":{\"nickname\":\"Steve\",\"message\":{\"text\":\"hi\"}}}");

        assertEquals(ProtocolConstants.Status.INTERNAL_ERROR, response.getCode().intValue());
    }

    // ------------------------------------------------------------------
    // mapPlatformResult 扩展点
    // ------------------------------------------------------------------

    /**
     * 只覆盖自己关心的结果码，其余交回 {@code super}
     */
    private static final class OverridingApi extends Api<EmptyPayload, Void> {

        private final PlatformResult<Void> result;

        private OverridingApi(PlatformResult<Void> result) {
            super(EmptyPayload.class, LOGGER);
            this.result = result;
        }

        @Override
        public String name() {
            return "test.override";
        }

        @Override
        protected ProtocolException mapPlatformResult(PlatformResult<?> result) {
            if (result.getCode() == PlatformResultCode.PLAYER_NOT_FOUND) {
                return ProtocolException.badRequest("custom: " + result.getMessage());
            }
            return super.mapPlatformResult(result);
        }

        @Override
        protected Void doExecute(EmptyPayload payload) throws ProtocolException {
            requireSuccess(result);
            return null;
        }
    }

    private static Response routeWithOverride(PlatformResult<Void> result) {
        ProtocolRouter router = new ProtocolRouter(
                LOGGER, Collections.<Api<?, ?>>singletonList(new OverridingApi(result)));
        BasePayload payload = GSON.fromJson("{\"api\":\"test.override\"}", BasePayload.class);
        return router.route(payload);
    }

    @Test
    @DisplayName("Api 子类可只覆盖自己关心的结果码")
    void subclassCanOverrideSingleResultCode() {
        Response overridden = routeWithOverride(
                PlatformResult.failure(PlatformResultCode.PLAYER_NOT_FOUND, "nf"));

        assertEquals(ProtocolConstants.Status.BAD_REQUEST, overridden.getCode().intValue());
        assertEquals("custom: nf", overridden.getMessage());

        // 未覆盖的结果码仍走基类映射 → 500
        Response inherited = routeWithOverride(PlatformResult.failure(PlatformResultCode.FAILED, "boom"));

        assertEquals(ProtocolConstants.Status.INTERNAL_ERROR, inherited.getCode().intValue());
        assertEquals("boom", inherited.getMessage());
    }
}
