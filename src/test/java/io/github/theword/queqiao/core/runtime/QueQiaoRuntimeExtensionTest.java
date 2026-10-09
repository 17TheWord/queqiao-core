package io.github.theword.queqiao.core.runtime;

import io.github.theword.queqiao.core.api.Api;
import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.extension.QueQiaoExtension;
import io.github.theword.queqiao.core.extension.QueQiaoExtensionContext;
import io.github.theword.queqiao.core.handle.HandleProtocolMessage;
import io.github.theword.queqiao.core.payload.EmptyPayload;
import io.github.theword.queqiao.core.platform.AbstractPlatformContext;
import io.github.theword.queqiao.core.response.Response;
import io.github.theword.queqiao.core.support.PlatformStubs;
import com.google.gson.Gson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extension SPI 契约测试（0.7.0-beta.3）
 *
 * <p>验证"外部附属模块在不创建第二个 Runtime / 第二套 WebSocket 的前提下，
 * 把自己的 API 接入 QueQiao 已有协议系统"这条链路。
 *
 * <p><b>如何在不反射内部字段的前提下验证 dispatch</b>：Extension 注册用的
 * {@code apiConfigurer} 回调与 Runtime 交给 {@code HandleProtocolMessage} 的是
 * <b>同一份</b> API 列表引用。因此测试用 {@code apiConfigurer} 捕获这份列表，
 * 既证明了"Extension API 已进入 Runtime 装配出的最终集合"，又可以用它构造协议入口
 * 验证真实 dispatch —— 不需要触碰 Runtime 的私有字段。
 *
 * <p><b>关于 {@code start()}</b>：Extension 注册完全发生在构造阶段，与 {@code start()}
 * 无关；旧 3 参 {@code create} 的启动兼容性由 {@link QueQiaoRuntimeLifecycleTest} 覆盖
 * （它同样使用 3 参 {@code create} 并调用 {@code start()}）。
 */
class QueQiaoRuntimeExtensionTest {

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(QueQiaoRuntimeExtensionTest.class);
    private static final Gson GSON = new Gson();

    private static final AbstractPlatformContext<?, ?, ?> PLATFORM =
            PlatformStubs.noopPlatformContext();

    /**
     * Core 默认 API 批次的名字（{@code DefaultApis}）
     */
    private static final Set<String> DEFAULT_API_NAMES = new HashSet<>(Arrays.asList(
            ProtocolConstants.Api.BROADCAST,
            ProtocolConstants.Api.SEND_TITLE,
            ProtocolConstants.Api.SEND_ACTIONBAR,
            ProtocolConstants.Api.SEND_PRIVATE_MSG,
            ProtocolConstants.Api.SEND_COMMAND,
            ProtocolConstants.Api.SEND_RCON_COMMAND,
            ProtocolConstants.Api.GET_STATUS));

    // ------------------------------------------------------------------
    // 核心链路：注册 → 进入最终集合 → 经既有协议栈 dispatch
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Extension API 进入 Runtime 最终集合，并经既有协议链成功 dispatch（echo 原样回传）")
    void extensionApiIsDispatchedThroughExistingProtocolStack() {
        AtomicReference<List<Api<?, ?>>> assembled = new AtomicReference<>();
        QueQiaoExtension extension = context -> context.registerApi(new NamedApi("extension_test", LOGGER));

        QueQiaoRuntime.create(null, PLATFORM, assembled::set, Collections.singletonList(extension));

        List<Api<?, ?>> apis = assembled.get();
        assertNotNull(apis, "apiConfigurer 应收到 Runtime 装配的 API 列表");
        assertTrue(
                apis.stream().anyMatch(api -> "extension_test".equals(api.name())),
                "Extension 注册的 API 应出现在 Runtime 装配的最终 API 集合中");

        // 用 Runtime 装配出来的同一份集合构造协议入口：
        // Extension API 走的必须是既有 ProtocolRouter / Response / echo，而不是旁路
        HandleProtocolMessage dispatcher = PlatformStubs.newDispatcher(LOGGER, GSON, apis);
        Response response = GSON.fromJson(
                dispatcher.handleHttpJson("{\"api\":\"extension_test\",\"echo\":\"extension-echo\"}"),
                Response.class);

        assertEquals(200, response.getCode().intValue(), "Extension API 应成功返回");
        assertEquals("extension-echo", response.getEcho(), "echo 应由既有实现原样回传");
        assertEquals("extension_test-ok", response.getData(), "返回值应来自 Extension 注册的 API");
    }

    @Test
    @DisplayName("多个 Extension 注册的 API 都能 dispatch，不建立顺序依赖")
    void multipleExtensionsAreAllRegistered() {
        AtomicReference<List<Api<?, ?>>> assembled = new AtomicReference<>();
        QueQiaoExtension first = context -> context.registerApi(new NamedApi("api_a", LOGGER));
        QueQiaoExtension second = context -> context.registerApi(new NamedApi("api_b", LOGGER));

        QueQiaoRuntime.create(null, PLATFORM, assembled::set, Arrays.asList(first, second));

        HandleProtocolMessage dispatcher = PlatformStubs.newDispatcher(LOGGER, GSON, assembled.get());
        assertEquals(200, dispatch(dispatcher, "api_a").getCode().intValue());
        assertEquals(200, dispatch(dispatcher, "api_b").getCode().intValue());
    }

    // ------------------------------------------------------------------
    // 装配失败：fail-fast，不产生部分可用的 Runtime
    // ------------------------------------------------------------------

    @Test
    @DisplayName("extensions 含 null 元素时 fail-fast，不静默跳过")
    void nullExtensionElementFailsFast() {
        QueQiaoExtension ok = context -> {
        };

        assertThrows(
                NullPointerException.class,
                () -> QueQiaoRuntime.create(null, PLATFORM, null, Arrays.asList(ok, null)));
    }

    @Test
    @DisplayName("extensions 集合本身为 null 时 fail-fast（无扩展应传空集合）")
    void nullExtensionsCollectionFailsFast() {
        assertThrows(
                NullPointerException.class,
                () -> QueQiaoRuntime.create(null, PLATFORM, null, null));
    }

    @Test
    @DisplayName("Extension 注册抛异常时 Runtime 构造整体失败，并保留原始 cause")
    void registrationExceptionFailsRuntimeCreation() {
        QueQiaoExtension failing = context -> {
            throw new IllegalStateException("boom");
        };

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> QueQiaoRuntime.create(null, PLATFORM, null, Collections.singletonList(failing)));

        assertNotNull(thrown.getCause(), "包装异常必须保留原始 cause");
        assertEquals("boom", thrown.getCause().getMessage());
        assertTrue(
                thrown.getMessage() != null && thrown.getMessage().contains("Failed to register extension"),
                "错误信息应指明注册失败的 Extension，实际=" + thrown.getMessage());
    }

    // ------------------------------------------------------------------
    // Context seal：注册阶段结束后不可再注册
    // ------------------------------------------------------------------

    @Test
    @DisplayName("注册阶段结束后 Context 被 seal，扩展再 registerApi 抛 IllegalStateException")
    void contextIsSealedAfterRegistrationPhase() {
        AtomicReference<QueQiaoExtensionContext> saved = new AtomicReference<>();
        QueQiaoExtension extension = context -> {
            saved.set(context);
            context.registerApi(new NamedApi("sealed_probe", LOGGER));
        };

        QueQiaoRuntime.create(null, PLATFORM, null, Collections.singletonList(extension));

        QueQiaoExtensionContext context = saved.get();
        assertNotNull(context, "Extension 应已收到 Context");
        assertThrows(
                IllegalStateException.class,
                () -> context.registerApi(new NamedApi("too_late", LOGGER)),
                "装配结束后再注册必须失败");
    }

    // ------------------------------------------------------------------
    // RuntimeExtensionContext 直接契约（同包可见）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RuntimeExtensionContext.registerApi(null) 抛 NullPointerException")
    void registerNullApiIsRejected() {
        RuntimeExtensionContext context = new RuntimeExtensionContext(new ArrayList<>());

        assertThrows(NullPointerException.class, () -> context.registerApi(null));
    }

    @Test
    @DisplayName("RuntimeExtensionContext 注册成功后把 API 追加进同一份列表")
    void registerApiAppendsToBackingList() {
        List<Api<?, ?>> apis = new ArrayList<>();
        RuntimeExtensionContext context = new RuntimeExtensionContext(apis);
        NamedApi api = new NamedApi("appended", LOGGER);

        context.registerApi(api);

        assertEquals(1, apis.size());
        assertSame(api, apis.get(0));
    }

    @Test
    @DisplayName("RuntimeExtensionContext.seal() 幂等，且 seal 后注册一律失败")
    void sealIsIdempotentAndBlocksFurtherRegistration() {
        RuntimeExtensionContext context = new RuntimeExtensionContext(new ArrayList<>());

        context.seal();
        context.seal();

        assertThrows(IllegalStateException.class, () -> context.registerApi(new NamedApi("x", LOGGER)));
    }

    // ------------------------------------------------------------------
    // 重复名：由 ProtocolRouter 单一真相来源拒绝，不静默覆盖
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Extension 不能静默覆盖内置 API：重名导致 Runtime 构造失败")
    void extensionCannotSilentlyOverrideBuiltInApi() {
        QueQiaoExtension extension =
                context -> context.registerApi(new NamedApi(ProtocolConstants.Api.BROADCAST, LOGGER));

        assertThrows(
                IllegalArgumentException.class,
                () -> QueQiaoRuntime.create(null, PLATFORM, null, Collections.singletonList(extension)));
    }

    @Test
    @DisplayName("两个 Extension 注册同名 API 也会被拒绝")
    void extensionsCannotSilentlyOverrideEachOther() {
        QueQiaoExtension first = context -> context.registerApi(new NamedApi("duplicated", LOGGER));
        QueQiaoExtension second = context -> context.registerApi(new NamedApi("duplicated", LOGGER));

        assertThrows(
                IllegalArgumentException.class,
                () -> QueQiaoRuntime.create(null, PLATFORM, null, Arrays.asList(first, second)));
    }

    // ------------------------------------------------------------------
    // 向后兼容：旧 3 参 create
    // ------------------------------------------------------------------

    @Test
    @DisplayName("旧 create（3 参，无 extensions）保持 source-compatible，默认 API 批次不变")
    void legacyCreateOverloadStaysCompatible() {
        AtomicReference<List<Api<?, ?>>> assembled = new AtomicReference<>();

        QueQiaoRuntime runtime = QueQiaoRuntime.create(null, PLATFORM, assembled::set);

        assertNotNull(runtime);
        assertEquals(RuntimeState.NEW, runtime.getState(), "create 不应自动启动");

        Set<String> names = assembled.get().stream().map(Api::name).collect(Collectors.toSet());
        assertTrue(
                names.containsAll(DEFAULT_API_NAMES),
                "默认 API 批次应保持不变，实际=" + names);
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    private static Response dispatch(HandleProtocolMessage dispatcher, String api) {
        return GSON.fromJson(
                dispatcher.handleHttpJson("{\"api\":\"" + api + "\"}"), Response.class);
    }

    /**
     * 测试用 API：名字可配置，成功时返回 {@code <name>-ok}
     *
     * <p>刻意实现为一个普通的 {@link Api} —— Extension 注册的 API 与内置 API 没有类型差异，
     * 这正是"天然进入现有协议系统"的体现。
     */
    private static final class NamedApi extends Api<EmptyPayload, String> {

        private final String apiName;

        NamedApi(String apiName, Logger logger) {
            super(EmptyPayload.class, logger);
            this.apiName = apiName;
        }

        @Override
        public String name() {
            return apiName;
        }

        @Override
        protected String doExecute(EmptyPayload payload) {
            return apiName + "-ok";
        }
    }
}
