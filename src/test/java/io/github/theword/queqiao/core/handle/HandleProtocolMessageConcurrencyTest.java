package io.github.theword.queqiao.core.handle;

import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.support.PlatformStubs;
import io.github.theword.queqiao.core.payload.BasePayload;
import io.github.theword.queqiao.core.response.Response;
import com.google.gson.Gson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 共享协议分发入口的并发安全测试
 *
 * <p>背景：{@link HandleProtocolMessage} 由 {@code QueQiaoRuntime} 创建唯一实例，
 * 注入给所有 {@code WsClient} 与 {@code WsServer} 共用。
 * 因此它会被多个连接、多个线程并发调用——这是本次改动最大的风险点，必须显式验证。
 *
 * <p>验证内容：
 * <ul>
 *     <li>处理器分发在并发执行下行为一致（无中间状态污染）</li>
 *     <li>负载解析与异常映射在并发下稳定</li>
 *     <li>共享实例上不存在隐式锁导致的异常或串扰</li>
 * </ul>
 *
 * <p><b>用例选取说明</b>：这里使用的协议路径均不触碰任何全局状态，
 * 使测试不依赖全局状态、可在任意顺序下运行：
 * <ul>
 *     <li>未注册的 api → 处理器表未命中 → 404</li>
 *     <li>{@code send_command} → 处理器必然抛 {@code ProtocolException} → 503</li>
 *     <li>{@code send_title} → 空 title/subtitle → 400</li>
 *     <li>{@code send_private_msg} → 空 nickname/uuid → 400</li>
 * </ul>
 *
 * <p>注："未知 api → 404" 这一分支在 WS-B4 之前<b>无法测试</b>——
 * {@code ProtocolRouter.route} 当时直接读取静态全局日志门面，
 * 未初始化全局上下文时会 NPE。WS-B4 把 Logger 改为构造器注入后该分支才可覆盖。
 */
class HandleProtocolMessageConcurrencyTest {

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(HandleProtocolMessageConcurrencyTest.class);
    private static final Gson GSON = new Gson();

    private static final int THREAD_COUNT = 8;
    private static final int ITERATIONS_PER_THREAD = 300;

    /**
     * 一个可并发执行、且不依赖全局状态的协议调用样例
     */
    private static final class ApiCase {

        private final String api;
        private final int expectedCode;

        private ApiCase(String api, int expectedCode) {
            this.api = api;
            this.expectedCode = expectedCode;
        }
    }

    private static final ApiCase[] CASES = {
            new ApiCase("__unknown_api__", ProtocolConstants.Status.NOT_FOUND),
            new ApiCase("send_command", ProtocolConstants.Status.SERVICE_UNAVAILABLE),
            new ApiCase("send_title", ProtocolConstants.Status.BAD_REQUEST),
            new ApiCase("send_private_msg", ProtocolConstants.Status.BAD_REQUEST),
    };

    @Test
    @DisplayName("同一个协议分发入口可被多线程并发安全使用")
    void sharedDispatcherIsSafeUnderConcurrentUse() throws Exception {
        HandleProtocolMessage sharedDispatcher = PlatformStubs.newDispatcher(LOGGER, GSON);
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int threadIndex = 0; threadIndex < THREAD_COUNT; threadIndex++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    for (int iteration = 0; iteration < ITERATIONS_PER_THREAD; iteration++) {
                        ApiCase apiCase = CASES[iteration % CASES.length];
                        Response response =
                                sharedDispatcher.parseAndHandle(new BasePayload(apiCase.api, null, null));
                        assertNotNull(response, "api=" + apiCase.api + " 不应返回 null");
                        assertEquals(
                                apiCase.expectedCode,
                                response.getCode().intValue(),
                                "api=" + apiCase.api + " 的返回码不符合预期");
                    }
                    return null;
                }));
            }

            startGate.countDown();
            for (Future<?> future : futures) {
                awaitOrRethrowAssertion(future);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 等待任务结束；断言失败时把原始的 {@link AssertionError} 抛出来，避免被 ExecutionException 包裹
     */
    private static void awaitOrRethrowAssertion(Future<?> future) throws Exception {
        try {
            future.get(60L, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AssertionError) {
                throw (AssertionError) cause;
            }
            throw e;
        }
    }
}
