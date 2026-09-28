package io.github.theword.queqiao.core.runtime;

import io.github.theword.queqiao.core.config.exception.ConfigValidationException;
import io.github.theword.queqiao.core.config.io.ConfigStore;
import io.github.theword.queqiao.core.platform.AbstractPlatformContext;
import io.github.theword.queqiao.core.protocol.handler.status.ServerStatusCollector;
import io.github.theword.queqiao.core.support.PlatformStubs;
import io.github.theword.queqiao.core.response.PrivateMessageResponse;
import com.google.gson.JsonElement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Runtime 启动失败回滚测试
 *
 * <p>验证 {@link QueQiaoRuntime#start()} 的失败语义：
 * <ol>
 *     <li>抛出的必须是<b>原始异常</b>，不被包装；</li>
 *     <li>状态落到 {@link RuntimeState#FAILED}（而不是 {@code STOPPED}）；</li>
 *     <li>已创建的资源被清理，不留残留；</li>
 *     <li>{@code FAILED} 状态下不允许再次 {@code start()}；</li>
 *     <li>{@code FAILED} 状态下 {@code shutdown()} 为 no-op，状态保持 FAILED；</li>
 *     <li>{@code FAILED} 状态下不允许 {@code reload()}；</li>
 *     <li>启动失败的 Runtime 不会被平台发布（按 create → start → publish 顺序）。</li>
 * </ol>
 *
 * <p><b>失败注入方式</b>：当前启动序列中唯一会同步抛异常的阶段是配置加载
 * （配置非法时抛出 {@link ConfigValidationException}）。由于 Runtime 只能启动一次
 * （{@code NEW → STARTING → RUNNING/FAILED}），失败必须在<b>首次</b> start 时注入。
 *
 * <p>"正常启动后资源被释放"这条性质由 {@link QueQiaoRuntimeLifecycleTest} 覆盖——
 * 启动失败回滚与正常关闭走的是同一个 {@code shutdownResources()}，因此覆盖等价。
 *
 * <p><b>断言对象是实例自身状态</b>：直接检查该 Runtime 的资源引用与其采集器持有的
 * executor，而不是按线程名统计 JVM 内线程数——后者会被其它用例正在退出的线程污染，
 * 导致断言不稳定。
 */
@Isolated
class QueQiaoRuntimeStartupRollbackTest {

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(QueQiaoRuntimeStartupRollbackTest.class);

    /**
     * 空平台上下文：本用例只关心 Runtime 的启动失败语义，不需要平台行为
     */
    private static final AbstractPlatformContext<?, ?, ?, ?> NOOP_PLATFORM_CONTEXT =
            PlatformStubs.noopPlatformContext();

    private QueQiaoRuntime runtime;

    @AfterEach
    void tearDown() {
        QueQiaoRuntime current = runtime;
        runtime = null;
        if (current != null) {
            current.shutdown();
        }
    }

    @Test
    @DisplayName("首次启动失败：原始异常透传、状态 FAILED、资源无残留、后续 shutdown 为 no-op")
    void firstStartFailureLeavesFailedStateWithoutResources() throws Exception {
        try (ConfigFixture fixture = new ConfigFixture()) {
            fixture.writeInvalidConfig();
            QueQiaoRuntime created = newRuntime();
            runtime = created;

            assertEquals(RuntimeState.NEW, created.getState(), "初始状态应为 NEW");

            ConfigValidationException failure =
                    assertThrows(ConfigValidationException.class, created::start);

            // 1. 原始异常透传：类型与字段路径都保留，不被包装
            assertNotNull(failure.getFieldPath(), "应保留原始异常的信息，实际=" + failure.getMessage());

            // 2. 状态落到 FAILED（而不是 STOPPED）——调用方需要知道"曾经启动失败"
            assertEquals(RuntimeState.FAILED, created.getState(), "启动失败后状态应为 FAILED");

            // 3. 资源无残留
            assertNull(created.getWebsocketManager(), "失败后不应持有 WebsocketManager");
            assertNull(
                    readRefreshExecutor(created.getServerStatusCollector()),
                    "失败后不应残留采集调度器");

            // 4. FAILED 不允许再次 start
            assertThrows(IllegalStateException.class, created::start);
            assertEquals(RuntimeState.FAILED, created.getState(), "重复 start 被拒后状态应保持 FAILED");

            // 5. shutdown 为 no-op，状态保持 FAILED
            created.shutdown();
            assertEquals(RuntimeState.FAILED, created.getState(), "FAILED 状态 shutdown 应为 no-op");
        }
    }

    @Test
    @DisplayName("FAILED 状态不允许 reload")
    void failedRuntimeRejectsReload() throws Exception {
        try (ConfigFixture fixture = new ConfigFixture()) {
            fixture.writeInvalidConfig();
            QueQiaoRuntime created = newRuntime();
            runtime = created;

            assertThrows(ConfigValidationException.class, created::start);
            assertThrows(IllegalStateException.class, () -> created.reload(null));
            assertEquals(RuntimeState.FAILED, created.getState(), "被拒的 reload 不应改变状态");
        }
    }

    @Test
    @DisplayName("按 create → start → publish 顺序，启动失败的 Runtime 不会被发布")
    void failedRuntimeIsNotPublished() throws Exception {
        try (ConfigFixture fixture = new ConfigFixture()) {
            fixture.writeInvalidConfig();

            QueQiaoRuntime published = null;
            QueQiaoRuntime candidate = newRuntime();
            runtime = candidate;
            try {
                candidate.start();
                // 只有 start() 成功才会执行到这里
                published = candidate;
            } catch (ConfigValidationException expected) {
                // 平台侧不发布失败的 Runtime
                LOGGER.info("启动按预期失败，Runtime 未被发布：{}", expected.getMessage());
            }

            assertNull(published, "启动失败的 Runtime 不应被平台发布");
            assertEquals(RuntimeState.FAILED, candidate.getState(), "未被发布的 Runtime 状态应为 FAILED");
            assertNull(candidate.getWebsocketManager(), "未发布的 Runtime 不应持有 WebsocketManager");
        }
    }

    private static QueQiaoRuntime newRuntime() {
        return QueQiaoRuntime.create(null, NOOP_PLATFORM_CONTEXT, null);
    }

    /**
     * 通过反射读取采集器的私有调度器，避免为测试污染生产 API
     */
    private static ScheduledThreadPoolExecutor readRefreshExecutor(ServerStatusCollector collector) throws Exception {
        Field field = ServerStatusCollector.class.getDeclaredField("refreshExecutor");
        field.setAccessible(true);
        return (ScheduledThreadPoolExecutor) field.get(collector);
    }

    /**
     * 配置文件夹具：写入非法配置以触发启动失败，结束时还原原文件
     */
    private static final class ConfigFixture implements AutoCloseable {

        private static final Path CONFIG_PATH = ConfigStore.resolveConfigPath(false);
        private static final Path BACKUP_PATH = CONFIG_PATH.resolveSibling(CONFIG_PATH.getFileName() + ".bak");

        /**
         * 根节点不是 Mapping，会被判定为 INVALID（而非 EMPTY），从而让配置加载抛异常
         */
        private static final String INVALID_CONFIG = "this-is-not-a-mapping";

        private final byte[] previousContent;

        private ConfigFixture() throws IOException {
            this.previousContent = Files.exists(CONFIG_PATH) ? Files.readAllBytes(CONFIG_PATH) : null;
            Files.createDirectories(CONFIG_PATH.getParent());
        }

        private void writeInvalidConfig() throws IOException {
            Files.write(CONFIG_PATH, INVALID_CONFIG.getBytes(StandardCharsets.UTF_8));
            LOGGER.info("已写入非法测试配置：{}", CONFIG_PATH.toAbsolutePath());
        }

        @Override
        public void close() throws IOException {
            if (previousContent != null) {
                Files.write(CONFIG_PATH, previousContent);
                return;
            }
            Files.deleteIfExists(CONFIG_PATH);
            Files.deleteIfExists(BACKUP_PATH);
        }
    }
}
