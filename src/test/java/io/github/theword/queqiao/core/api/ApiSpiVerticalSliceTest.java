package io.github.theword.queqiao.core.api;

import io.github.theword.queqiao.core.api.platform.PlayerMessageSender;
import io.github.theword.queqiao.core.api.platform.PlayerProvider;
import io.github.theword.queqiao.core.api.standard.PrivateMessageApi;
import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.event.model.PlayerModel;
import io.github.theword.queqiao.core.handle.HandleProtocolMessage;
import io.github.theword.queqiao.core.response.Response;
import io.github.theword.queqiao.core.service.PrivateMessageService;
import io.github.theword.queqiao.core.support.PlatformStubs;
import io.github.theword.queqiao.core.support.PlayerStubs;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * API SPI 垂直切片测试
 *
 * <p>用可执行的方式证明本实验的核心结论：
 * <ol>
 *     <li><b>场景 A</b>：注册 {@code PrivateMessageApi} 后 {@code send_private_msg} 可用；</li>
 *     <li><b>场景 B</b>：不注册时 {@code send_private_msg} 返回 404——"有没有这个 API"
 *         由<b>注册集合</b>决定，而不是由 Core 的固定 API 表决定；</li>
 *     <li><b>场景 C</b>：自定义 API 注册即可用，Core 完全不需要知道它的存在；</li>
 *     <li><b>场景 D</b>：不同平台只实现 {@link PlayerProvider} / {@link PlayerMessageSender}
 *         两个原子能力，查找、not found、成功响应全部由 Core 复用。</li>
 * </ol>
 *
 * <p>测试直接走 {@code HandleProtocolMessage.handleHttpJson}（与 WebSocket 入口共用同一套
 * 解析与路由逻辑），因此不需要构造 WebSocket、无网络依赖。
 */
class ApiSpiVerticalSliceTest {

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiSpiVerticalSliceTest.class);
    private static final Gson GSON = new Gson();

    private static final UUID STEVE_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    /**
     * 场景 C 的自定义 API：Core 侧不存在任何对它的引用
     */
    private static final class EchoApi implements Api {

        @Override
        public String getName() {
            return "test.echo";
        }

        @Override
        public Object handle(JsonElement data) {
            return data;
        }
    }

    /**
     * 一个"平台适配器"：只实现平台 SPI 的原子能力，<b>不含任何查找逻辑</b>
     */
    private static final class FakePlatform implements PlayerProvider, PlayerMessageSender {

        private final List<PlayerModel> players;
        private final List<PlayerModel> sentTargets = new ArrayList<>();

        private FakePlatform(PlayerModel... players) {
            this.players = Arrays.asList(players);
        }

        @Override
        public List<PlayerModel> getOnlinePlayers() {
            return new ArrayList<>(players);
        }

        @Override
        public void sendPrivateMessage(PlayerModel player, JsonElement message) {
            sentTargets.add(player);
        }

        private int sentCount() {
            return sentTargets.size();
        }

        private PlayerModel lastTarget() {
            return sentTargets.isEmpty() ? null : sentTargets.get(sentTargets.size() - 1);
        }
    }

    /**
     * 构造一个协议分发入口，并把 Core 默认 API 注册进给定注册中心
     */
    private static HandleProtocolMessage newDispatcher(ApiRegistry registry) {
        return PlatformStubs.newDispatcher(
                LOGGER, GSON, PlatformStubs.noopApiService(), PlatformStubs.rconExecutorReturning(""), registry);
    }

    private static Response dispatch(HandleProtocolMessage dispatcher, String rawJson) {
        Response response = GSON.fromJson(dispatcher.handleHttpJson(rawJson), Response.class);
        assertNotNull(response, "分发结果不应为 null");
        assertNotNull(response.getCode(), "响应应带状态码");
        return response;
    }

    /**
     * 走一次完整的 send_private_msg（每次都用全新的注册中心，避免重复注册）
     */
    private static Response sendPrivateMessage(PlayerProvider provider, PlayerMessageSender sender, String nickname) {
        ApiRegistry registry = new ApiRegistry();
        HandleProtocolMessage dispatcher = newDispatcher(registry);
        registry.register(new PrivateMessageApi(new PrivateMessageService(provider, sender)));
        return dispatch(dispatcher, "{\"api\":\"send_private_msg\",\"data\":{\"nickname\":\"" + nickname
                + "\",\"message\":{\"text\":\"hi\"}}}");
    }

    private static String dataMessage(Response response) {
        Object data = response.getData();
        if (!(data instanceof Map)) {
            return null;
        }
        Object message = ((Map<?, ?>) data).get("message");
        return message == null ? null : String.valueOf(message);
    }

    // ------------------------------------------------------------------
    // 场景 A：Paper 形态——注册了私聊能力
    // ------------------------------------------------------------------

    @Test
    @DisplayName("场景 A：注册 PrivateMessageApi 后 send_private_msg 成功")
    void scenarioAPrivateMessageWorksWhenRegistered() {
        PlayerModel steve = new PlayerModel("Steve", STEVE_UUID);
        PlayerStubs.FakePlayerProvider provider = new PlayerStubs.FakePlayerProvider(steve);
        PlayerStubs.RecordingPlayerMessageSender sender = new PlayerStubs.RecordingPlayerMessageSender();

        Response response = sendPrivateMessage(provider, sender, "Steve");

        assertEquals(ProtocolConstants.Status.SUCCESS, response.getCode().intValue());
        assertEquals(1, sender.getSendCount(), "应真正调用平台发送能力一次");
        assertSame(steve, sender.getLastTarget(), "平台收到的必须是 Core 查找出的目标玩家");
        assertNotNull(response.getData());
    }

    // ------------------------------------------------------------------
    // 场景 B：Velocity 形态——没有该能力，就不注册
    // ------------------------------------------------------------------

    @Test
    @DisplayName("场景 B：未注册 PrivateMessageApi 时 send_private_msg 返回 404")
    void scenarioBUnregisteredPrivateMessageReturnsNotFound() {
        ApiRegistry registry = new ApiRegistry();
        HandleProtocolMessage dispatcher = newDispatcher(registry);

        assertFalse(
                registry.contains(ProtocolConstants.Api.SEND_PRIVATE_MSG),
                "Core 默认注册不应包含 send_private_msg");

        Response response = dispatch(
                dispatcher,
                "{\"api\":\"send_private_msg\",\"data\":{\"nickname\":\"Steve\",\"message\":{\"text\":\"hi\"}}}");

        assertEquals(
                ProtocolConstants.Status.NOT_FOUND,
                response.getCode().intValue(),
                "平台没有注册该能力时应返回 404，而不是运行时回一句\"不支持\"");
    }

    @Test
    @DisplayName("场景 B 补充：Core 默认集合只包含不依赖平台能力差异的 API")
    void scenarioBDefaultSetExcludesPlatformDependentApis() {
        ApiRegistry registry = new ApiRegistry();
        newDispatcher(registry);

        // 默认包含：不依赖平台能力差异
        assertTrue(registry.contains(ProtocolConstants.Api.SEND_TITLE));
        assertTrue(registry.contains(ProtocolConstants.Api.SEND_ACTIONBAR));
        assertTrue(registry.contains(ProtocolConstants.Api.SEND_COMMAND));
        assertTrue(registry.contains(ProtocolConstants.Api.SEND_RCON_COMMAND));
        assertTrue(registry.contains(ProtocolConstants.Api.GET_STATUS));

        // 默认不包含：由平台按自身能力决定是否注册
        assertFalse(
                registry.contains(ProtocolConstants.Api.BROADCAST),
                "broadcast 需要平台提供 BroadcastService");
        assertFalse(
                registry.contains(ProtocolConstants.Api.SEND_MSG),
                "send_msg 需要平台提供 BroadcastService");
        assertFalse(
                registry.contains(ProtocolConstants.Api.SEND_PRIVATE_MSG),
                "send_private_msg 需要平台提供玩家能力");
    }

    // ------------------------------------------------------------------
    // 场景 C：LLM 自定义 API——Core 不需要知道
    // ------------------------------------------------------------------

    @Test
    @DisplayName("场景 C：自定义 Api 注册即可用，Core 无需修改")
    void scenarioCCustomApiWorksWithoutCoreChanges() {
        ApiRegistry registry = new ApiRegistry();
        HandleProtocolMessage dispatcher = newDispatcher(registry);

        registry.register(new EchoApi());

        Response response = dispatch(dispatcher, "{\"api\":\"test.echo\",\"data\":{\"text\":\"hello\"}}");

        assertEquals(ProtocolConstants.Status.SUCCESS, response.getCode().intValue());
        JsonObject data = GSON.toJsonTree(response.getData()).getAsJsonObject();
        assertEquals("hello", data.get("text").getAsString(), "自定义 Api 的返回值应原样进入响应 data");
    }

    @Test
    @DisplayName("场景 C 补充：自定义 Api 未注册时同样返回 404")
    void scenarioCCustomApiUnregisteredReturnsNotFound() {
        ApiRegistry registry = new ApiRegistry();
        HandleProtocolMessage dispatcher = newDispatcher(registry);

        Response response = dispatch(dispatcher, "{\"api\":\"test.echo\",\"data\":{\"text\":\"hello\"}}");

        assertEquals(ProtocolConstants.Status.NOT_FOUND, response.getCode().intValue());
    }

    // ------------------------------------------------------------------
    // 场景 D：Core 复用逻辑——平台只实现两个原子能力
    // ------------------------------------------------------------------

    @Test
    @DisplayName("场景 D：两个平台只实现 2 个 SPI 接口，行为由同一份 Core 逻辑决定")
    void scenarioDCoreLogicIsReusedAcrossPlatforms() {
        FakePlatform paper = new FakePlatform(new PlayerModel("Steve", STEVE_UUID));
        FakePlatform fabric = new FakePlatform(new PlayerModel("Steve", STEVE_UUID));

        // 成功路径：两个平台表现一致
        Response paperSuccess = sendPrivateMessage(paper, paper, "Steve");
        Response fabricSuccess = sendPrivateMessage(fabric, fabric, "Steve");

        assertEquals(ProtocolConstants.Status.SUCCESS, paperSuccess.getCode().intValue());
        assertEquals(ProtocolConstants.Status.SUCCESS, fabricSuccess.getCode().intValue());
        assertEquals(1, paper.sentCount());
        assertEquals(1, fabric.sentCount());
        assertEquals("Steve", paper.lastTarget().getNickname());
        assertEquals("Steve", fabric.lastTarget().getNickname());

        // 找不到玩家：两个平台的提示文案完全一致，因为它来自同一份 Core 代码
        Response paperMissing = sendPrivateMessage(paper, paper, "Nobody");
        Response fabricMissing = sendPrivateMessage(fabric, fabric, "Nobody");

        assertEquals(ProtocolConstants.Status.SUCCESS, paperMissing.getCode().intValue(),
                "\"找不到玩家\"是业务结果，不是协议错误");
        assertEquals(dataMessage(paperMissing), dataMessage(fabricMissing),
                "两个平台的 not found 文案应完全相同（由 Core 统一决定）");
        assertEquals(1, paper.sentCount(), "找不到时不应再发送");
    }

    @Test
    @DisplayName("场景 D 补充：平台只实现原子能力，查找规则不落在平台侧")
    void scenarioDLookupRulesLiveInCore() {
        // 平台只提供"有哪些玩家"与"怎么发消息"
        FakePlatform platform = new FakePlatform(new PlayerModel("Steve", STEVE_UUID));

        // 昵称两侧带空白仍然命中——归一化规则由 Core 决定，平台不需要知道
        Response response = sendPrivateMessage(platform, platform, "  Steve  ");

        assertEquals(ProtocolConstants.Status.SUCCESS, response.getCode().intValue());
        assertEquals(1, platform.sentCount(), "trim 后的昵称应命中目标玩家");
    }

    // ------------------------------------------------------------------
    // 参数校验（注册后仍保持迁移前语义）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("注册后 nickname 为纯空白且无 uuid：返回 400")
    void blankNicknameReturnsBadRequestWhenRegistered() {
        FakePlatform platform = new FakePlatform(new PlayerModel("Steve", STEVE_UUID));

        Response response = sendPrivateMessage(platform, platform, "   ");

        assertEquals(ProtocolConstants.Status.BAD_REQUEST, response.getCode().intValue());
        assertEquals(0, platform.sentCount(), "参数不合法时不应发送");
    }

    @Test
    @DisplayName("注册后请求体无法解析：返回 400")
    void malformedDataReturnsBadRequestWhenRegistered() {
        ApiRegistry registry = new ApiRegistry();
        HandleProtocolMessage dispatcher = newDispatcher(registry);
        registry.register(new PrivateMessageApi(new PrivateMessageService(
                new PlayerStubs.FakePlayerProvider(), new PlayerStubs.RecordingPlayerMessageSender())));

        Response response = dispatch(dispatcher, "{\"api\":\"send_private_msg\",\"data\":\"not-an-object\"}");

        assertEquals(ProtocolConstants.Status.BAD_REQUEST, response.getCode().intValue());
    }
}
