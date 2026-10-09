package io.github.theword.queqiao.core.runtime;

import io.github.theword.queqiao.core.api.Api;
import io.github.theword.queqiao.core.command.CommandRouter;
import io.github.theword.queqiao.core.extension.QueQiaoExtension;
import io.github.theword.queqiao.core.extension.QueQiaoExtensionDiscovery;
import io.github.theword.queqiao.core.platform.AbstractPlatformContext;
import io.github.theword.queqiao.core.support.DisabledConfigFixture;
import io.github.theword.queqiao.core.support.PlatformStubs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime 便捷启动 API 测试
 *
 * <p>覆盖 {@code create(platform[, ...])} / {@code start(platform[, ...])} 与
 * {@code createCommandRouter()}：普通平台只应提供"平台上下文"（以及可选的扩展来源），
 * 其余装配由 Runtime 自己完成。
 *
 * <p>涉及 {@code start()} 的用例用 {@link DisabledConfigFixture} 关掉全部传输层，
 * 避免绑定端口或建立连接。
 */
class QueQiaoRuntimeConvenienceTest {

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

    private static final AbstractPlatformContext<?, ?, ?> PLATFORM =
            PlatformStubs.noopPlatformContext();

    private QueQiaoRuntime started;

    @AfterEach
    void tearDown() {
        QueQiaoRuntime current = started;
        started = null;
        if (current != null) {
            current.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // create 便捷重载
    // ------------------------------------------------------------------

    @Test
    @DisplayName("create(platform) 只创建不启动")
    void createWithPlatformOnlyDoesNotStart() {
        QueQiaoRuntime runtime = QueQiaoRuntime.create(PLATFORM);

        assertEquals(RuntimeState.NEW, runtime.getState(), "create 不应自动启动");
    }

    @Test
    @DisplayName("create(platform, extensions) 会安装扩展")
    void createWithExtensionsInstallsThem() {
        AtomicInteger registrations = new AtomicInteger();
        QueQiaoExtension extension = context -> registrations.incrementAndGet();

        QueQiaoRuntime.create(PLATFORM, Collections.singletonList(extension));

        assertEquals(1, registrations.get(), "扩展应被注册一次");
    }

    @Test
    @DisplayName("create(platform, discovery) 会调用 discovery 并安装扩展")
    void createWithDiscoveryInstallsThem() {
        AtomicInteger registrations = new AtomicInteger();
        QueQiaoExtension extension = context -> registrations.incrementAndGet();

        QueQiaoRuntime.create(PLATFORM, () -> Collections.singletonList(extension));

        assertEquals(1, registrations.get());
    }

    @Test
    @DisplayName("discovery 返回空集合 → 正常创建，不安装任何扩展")
    void emptyDiscoveryIsAllowed() {
        QueQiaoRuntime runtime = QueQiaoRuntime.create(PLATFORM, Collections::emptyList);

        assertEquals(RuntimeState.NEW, runtime.getState());
    }

    // ------------------------------------------------------------------
    // discovery 的错误契约
    // ------------------------------------------------------------------

    @Test
    @DisplayName("discovery 返回 null → 快速失败，不静默当成空集合")
    void discoveryReturningNullFailsFast() {
        NullPointerException thrown = assertThrows(
                NullPointerException.class,
                () -> QueQiaoRuntime.create(PLATFORM, () -> null));

        assertTrue(
                thrown.getMessage() != null && thrown.getMessage().contains("discover"),
                "错误信息应指明 discover()，实际=" + thrown.getMessage());
    }

    @Test
    @DisplayName("discovery 为 null → 快速失败")
    void nullDiscoveryFailsFast() {
        assertThrows(
                NullPointerException.class,
                () -> QueQiaoRuntime.create(PLATFORM, (QueQiaoExtensionDiscovery) null));
    }

    @Test
    @DisplayName("discovery 抛异常 → Runtime 创建失败，不产生半成品 Runtime")
    void discoveryThrowingFailsRuntimeCreation() {
        assertThrows(
                IllegalStateException.class,
                () -> QueQiaoRuntime.create(PLATFORM, () -> {
                    throw new IllegalStateException("discovery boom");
                }));
    }

    // ------------------------------------------------------------------
    // start 便捷重载
    // ------------------------------------------------------------------

    @Test
    @DisplayName("start(platform) 等价于 create(platform) + start()")
    void startWithPlatformOnly() throws Exception {
        try (DisabledConfigFixture ignored = new DisabledConfigFixture()) {
            QueQiaoRuntime runtime = QueQiaoRuntime.start(PLATFORM);
            started = runtime;

            assertEquals(RuntimeState.RUNNING, runtime.getState());
        }
    }

    @Test
    @DisplayName("start(platform, extensions) 启动 Runtime 并安装扩展")
    void startWithExtensions() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        QueQiaoExtension extension = context -> registrations.incrementAndGet();

        try (DisabledConfigFixture ignored = new DisabledConfigFixture()) {
            QueQiaoRuntime runtime =
                    QueQiaoRuntime.start(PLATFORM, Collections.singletonList(extension));
            started = runtime;

            assertEquals(RuntimeState.RUNNING, runtime.getState());
            assertEquals(1, registrations.get(), "扩展应在 Runtime 构造阶段安装");
        }
    }

    @Test
    @DisplayName("start(platform, discovery) 启动 Runtime 并安装发现的扩展")
    void startWithDiscovery() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        QueQiaoExtension extension = context -> registrations.incrementAndGet();

        try (DisabledConfigFixture ignored = new DisabledConfigFixture()) {
            QueQiaoRuntime runtime =
                    QueQiaoRuntime.start(PLATFORM, () -> Collections.singletonList(extension));
            started = runtime;

            assertEquals(RuntimeState.RUNNING, runtime.getState());
            assertEquals(1, registrations.get());
        }
    }

    // ------------------------------------------------------------------
    // createCommandRouter
    // ------------------------------------------------------------------

    @Test
    @DisplayName("createCommandRouter() 返回接好协作者的命令路由（带 Core 内置根命令）")
    void createCommandRouterWiresCollaborators() throws Exception {
        try (DisabledConfigFixture ignored = new DisabledConfigFixture()) {
            QueQiaoRuntime runtime = QueQiaoRuntime.start(PLATFORM);
            started = runtime;

            CommandRouter<Object> router = runtime.createCommandRouter();

            assertNotNull(router, "应返回命令路由");
            assertNotNull(router.getRootCommand(), "应带出 Core 内置根命令");
            assertEquals("queqiao", router.getRootCommand().getName());
        }
    }

    // ------------------------------------------------------------------
    // 旧 API 兼容
    // ------------------------------------------------------------------

    @Test
    @DisplayName("旧的 4 参 create 仍然可用，且默认 API 批次不变")
    void legacyCreateStillWorks() {
        AtomicReference<List<Api<?, ?>>> assembled = new AtomicReference<>();

        QueQiaoRuntime runtime = QueQiaoRuntime.create(
                null, PLATFORM, assembled::set, Collections.emptyList());

        assertNotNull(runtime);
        assertEquals(RuntimeState.NEW, runtime.getState());
        assertTrue(
                assembled.get().stream().anyMatch(api -> "broadcast".equals(api.name())),
                "默认 API 批次应仍在");
    }
}
