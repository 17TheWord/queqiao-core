package io.github.theword.queqiao.core.runtime;

import io.github.theword.queqiao.core.config.ConfigKeys;
import io.github.theword.queqiao.core.platform.AbstractPlatformContext;
import io.github.theword.queqiao.core.protocol.handler.status.ServerStatusCollector;
import io.github.theword.queqiao.core.support.PlatformStubs;
import io.github.theword.queqiao.core.response.PrivateMessageResponse;
import com.google.gson.JsonElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双 Runtime 隔离测试
 *
 * <p>本次改造把 {@code RuntimeUtils} 与 {@code ServerStatusCollector} 从静态门面
 * 改为 Runtime 实例级对象，因此必须显式验证：
 * <b>同一 JVM 中并存的两个 Runtime 不共享任何可变状态</b>。
 *
 * <p>本用例全部使用内存构造的配置（不读配置文件、不启动传输层），
 * 因此可以在同一测试进程内安全地并存两个 Runtime。
 */
@Isolated
class QueQiaoRuntimeIsolationTest {

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(QueQiaoRuntimeIsolationTest.class);

    /**
     * 空平台上下文：不实现任何行为，仅用于验证 Runtime 自身的状态隔离
     */
    private static final AbstractPlatformContext<?, ?, ?, ?> NOOP_PLATFORM_CONTEXT =
            PlatformStubs.noopPlatformContext();

    private static QueQiaoRuntime newRuntime() {
        return QueQiaoRuntime.create(null, NOOP_PLATFORM_CONTEXT, null);
    }

    @Test
    @DisplayName("两个 Runtime 的 utils / 状态采集器 / 配置互相独立")
    void twoRuntimesShareNoMutableState() {
        QueQiaoRuntime a = newRuntime();
        QueQiaoRuntime b = newRuntime();
        try {
            assertNotSame(a, b);
            assertNotSame(a.utils, b.utils, "utils 不得共享");
            assertNotSame(
                    a.getServerStatusCollector(),
                    b.getServerStatusCollector(),
                    "状态采集器不得共享");

            // 配置独立性：改 A 的忽略命令与 debug 开关，B 不受影响
            a.getConfig().set(ConfigKeys.IGNORED_COMMANDS, Arrays.asList("tp"));
            a.getConfig().set(ConfigKeys.DEBUG, true);

            assertEquals("", a.utils.isIgnoredCommand("tp Steve"), "A 应忽略 tp");
            assertEquals("tp Steve", b.utils.isIgnoredCommand("tp Steve"), "B 不应受 A 的配置影响");

            assertTrue(a.utils.isDebugEnabled(), "A 的 debug 应已开启");
            assertFalse(b.utils.isDebugEnabled(), "B 的 debug 不应被 A 影响");
        } finally {
            // 两个 Runtime 都未 start（状态 NEW），因此 shutdown() 是 no-op；本用例没有启动任何资源
            a.shutdown();
            b.shutdown();
        }
    }

    @Test
    @DisplayName("状态采集器的线程池属于各自实例，不共享 JVM 静态状态")
    void statusCollectorSchedulersAreNotShared() throws Exception {
        QueQiaoRuntime a = newRuntime();
        QueQiaoRuntime b = newRuntime();
        try {
            ServerStatusCollector collectorA = a.getServerStatusCollector();
            ServerStatusCollector collectorB = b.getServerStatusCollector();

            // 启动前：两个采集器都没有自己的调度器
            assertNull(readRefreshExecutor(collectorA), "A 未启动时不应持有调度器");
            assertNull(readRefreshExecutor(collectorB), "B 未启动时不应持有调度器");

            // 只启动 A 的采集调度器
            collectorA.startRefreshScheduler(5);
            ScheduledThreadPoolExecutor executorA = readRefreshExecutor(collectorA);
            assertNotNull(executorA, "启动后 A 应持有自己的调度器");
            assertFalse(executorA.isShutdown(), "A 的调度器应可用");

            // 关键断言：A 的启动没有让 B 获得调度器——说明不存在 JVM 级静态共享
            assertNull(readRefreshExecutor(collectorB), "B 不应因 A 的启动而获得调度器");

            collectorA.stopRefreshScheduler();
            assertTrue(executorA.isShutdown(), "停止后 A 的调度器应被关闭（否则线程泄漏）");
            assertNull(readRefreshExecutor(collectorA), "停止后应清空 A 的调度器引用");

            // B 仍然独立可用（走同步回退分支），与 A 的启停无关
            assertTrue(
                    collectorB.collectStatusSnapshot().containsKey("timestamp"),
                    "B 的采集器应独立可用");
        } finally {
            // Runtime 未 start（状态 NEW），shutdown() 是 no-op；
            // 本用例为了隔离采集器而绕过 Runtime.start() 直接驱动它，故调度器必须在此前显式 stop
            a.shutdown();
            b.shutdown();
        }
    }

    /**
     * 通过反射读取采集器的私有调度器
     *
     * <p>断言对象是"该实例自己持有的 executor"，而不是线程名——后者在 JVM 内全局可见，
     * 会被其它用例正在退出的线程污染，导致断言不稳定。
     */
    private static ScheduledThreadPoolExecutor readRefreshExecutor(ServerStatusCollector collector) throws Exception {
        Field field = ServerStatusCollector.class.getDeclaredField("refreshExecutor");
        field.setAccessible(true);
        return (ScheduledThreadPoolExecutor) field.get(collector);
    }
}
