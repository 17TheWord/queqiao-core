package io.github.theword.queqiao.core.api.standard;

import io.github.theword.queqiao.core.api.ApiRegistry;
import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.handle.HandleProtocolMessage;
import io.github.theword.queqiao.core.response.Response;
import io.github.theword.queqiao.core.support.PlatformStubs;
import com.google.gson.Gson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link BroadcastApi} 垂直切片测试
 *
 * <p>回答本阶段唯一的问题：
 * <b>Core 提供标准 API 实现，但平台可以自由选择注册；真正的平台注册能否正常工作？</b>
 *
 * <p>覆盖：
 * <ol>
 *     <li>未注册 → 404；</li>
 *     <li>注册 {@code broadcast} → 调用平台 {@code BroadcastService} 并返回 200；</li>
 *     <li>注册 {@code send_msg} → 走同一实现；</li>
 *     <li>{@code broadcast} 与 {@code send_msg} 指向<b>同一个</b> service 实例。</li>
 * </ol>
 *
 * <p>测试直接走 {@code HandleProtocolMessage.handleHttpJson}（与 WebSocket 入口共用同一套
 * 解析与路由逻辑），无网络依赖。
 */
class BroadcastApiTest {

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(BroadcastApiTest.class);
    private static final Gson GSON = new Gson();

    private static HandleProtocolMessage newDispatcher(ApiRegistry registry) {
        return PlatformStubs.newDispatcher(
                LOGGER, GSON, PlatformStubs.noopApiService(), PlatformStubs.rconExecutorReturning(""), registry);
    }

    private static Response dispatch(HandleProtocolMessage dispatcher, String api, String messageJson) {
        String raw = "{\"api\":\"" + api + "\",\"data\":{\"message\":" + messageJson + "}}";
        Response response = GSON.fromJson(dispatcher.handleHttpJson(raw), Response.class);
        assertNotNull(response, "分发结果不应为 null");
        assertNotNull(response.getCode(), "响应应带状态码");
        return response;
    }

    @Test
    @DisplayName("未注册 broadcast：返回 404")
    void unregisteredBroadcastReturnsNotFound() {
        ApiRegistry registry = new ApiRegistry();
        HandleProtocolMessage dispatcher = newDispatcher(registry);

        assertFalse(
                registry.contains(ProtocolConstants.Api.BROADCAST),
                "Core 默认不应注册 broadcast");

        Response response = dispatch(dispatcher, "broadcast", "{\"text\":\"hi\"}");

        assertEquals(
                ProtocolConstants.Status.NOT_FOUND,
                response.getCode().intValue(),
                "平台没有注册该能力时应返回 404");
    }

    @Test
    @DisplayName("注册 broadcast：调用平台 BroadcastService 并返回 200")
    void registeredBroadcastReachesService() {
        PlatformStubs.RecordingBroadcastService service = new PlatformStubs.RecordingBroadcastService();
        ApiRegistry registry = new ApiRegistry();
        HandleProtocolMessage dispatcher = newDispatcher(registry);
        registry.register(new BroadcastApi(ProtocolConstants.Api.BROADCAST, service));

        Response response = dispatch(dispatcher, "broadcast", "{\"text\":\"hi\"}");

        assertEquals(ProtocolConstants.Status.SUCCESS, response.getCode().intValue());
        assertEquals(1, service.getCount(), "应调用平台广播能力一次");
        assertEquals("{\"text\":\"hi\"}", service.getBroadcasts().get(0), "消息应原样传递");
    }

    @Test
    @DisplayName("注册 send_msg：走同一个实现")
    void registeredSendMsgReachesService() {
        PlatformStubs.RecordingBroadcastService service = new PlatformStubs.RecordingBroadcastService();
        ApiRegistry registry = new ApiRegistry();
        HandleProtocolMessage dispatcher = newDispatcher(registry);
        registry.register(new BroadcastApi(ProtocolConstants.Api.SEND_MSG, service));

        Response response = dispatch(dispatcher, "send_msg", "{\"text\":\"hi\"}");

        assertEquals(ProtocolConstants.Status.SUCCESS, response.getCode().intValue());
        assertEquals(1, service.getCount());
        assertEquals("{\"text\":\"hi\"}", service.getBroadcasts().get(0));
    }

    @Test
    @DisplayName("broadcast 与 send_msg 指向同一个 BroadcastService 实例")
    void bothNamesShareSameService() {
        PlatformStubs.RecordingBroadcastService service = new PlatformStubs.RecordingBroadcastService();
        ApiRegistry registry = new ApiRegistry();
        HandleProtocolMessage dispatcher = newDispatcher(registry);
        registry.register(new BroadcastApi(ProtocolConstants.Api.BROADCAST, service));
        registry.register(new BroadcastApi(ProtocolConstants.Api.SEND_MSG, service));

        assertEquals(
                ProtocolConstants.Status.SUCCESS,
                dispatch(dispatcher, "broadcast", "{\"text\":\"a\"}").getCode().intValue());
        assertEquals(
                ProtocolConstants.Status.SUCCESS,
                dispatch(dispatcher, "send_msg", "{\"text\":\"b\"}").getCode().intValue());

        assertEquals(2, service.getCount(), "两次调用应落在同一个 service 实例上");
        assertEquals(
                Arrays.asList("{\"text\":\"a\"}", "{\"text\":\"b\"}"),
                service.getBroadcasts(),
                "同一个 service 应记录到两次广播");
    }

    @Test
    @DisplayName("构造器拒绝 null / 空白名称与 null service")
    void constructorValidatesArguments() {
        PlatformStubs.RecordingBroadcastService service = new PlatformStubs.RecordingBroadcastService();

        assertThrows(NullPointerException.class, () -> new BroadcastApi(null, service));
        assertThrows(NullPointerException.class, () -> new BroadcastApi(ProtocolConstants.Api.BROADCAST, null));
        assertThrows(IllegalArgumentException.class, () -> new BroadcastApi("   ", service));
    }

    @Test
    @DisplayName("注册后请求体无法解析：返回 400，且不调用平台")
    void malformedDataReturnsBadRequest() {
        PlatformStubs.RecordingBroadcastService service = new PlatformStubs.RecordingBroadcastService();
        ApiRegistry registry = new ApiRegistry();
        HandleProtocolMessage dispatcher = newDispatcher(registry);
        registry.register(new BroadcastApi(ProtocolConstants.Api.BROADCAST, service));

        Response response = GSON.fromJson(
                dispatcher.handleHttpJson("{\"api\":\"broadcast\",\"data\":\"not-an-object\"}"), Response.class);

        assertNotNull(response);
        assertEquals(ProtocolConstants.Status.BAD_REQUEST, response.getCode().intValue());
        assertEquals(0, service.getCount(), "参数不合法时不应调用平台");
    }
}
