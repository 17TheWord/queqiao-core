package io.github.theword.queqiao.core.websocket;

import io.github.theword.queqiao.core.support.PlatformStubs;
import com.google.gson.Gson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WsServer} 有界停止语义测试
 *
 * <p>Java-WebSocket 1.5.3 的 {@code stop(timeout, closeMessage)} 有两个陷阱：
 * <ol>
 *     <li>内部是 {@code selectorthread.join(timeout)}，<b>传 0 等于无限等待</b>；</li>
 *     <li>它<b>不</b>关闭 ServerSocketChannel——那发生在 selector 线程退出时的
 *         {@code doServerShutdown()} 里（{@code DefaultWebSocketServerFactory#close()}
 *         只关闭解码线程池）。因此线程卡住时端口会一直可连。</li>
 * </ol>
 *
 * <p>本类用<b>确定性</b>方式制造"selector 线程被占住"：在 {@code onStart()}（由 selector
 * 线程回调）里阻塞，分别覆盖"可中断"与"不可中断"两种阻塞。
 */
class WsServerStopTest {

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(WsServerStopTest.class);
    private static final Gson GSON = new Gson();

    /** 停止时使用的等待上限，与 {@code WebsocketManager} 的生产取值同一量级 */
    private static final int STOP_TIMEOUT_MILLIS = 1_000;

    @Test
    @DisplayName("selector 线程可中断时：stopBounded 有界返回并确认终止，端口已释放")
    void stopBoundedTerminatesInterruptibleSelector() throws Exception {
        int port = findFreePort();
        CountDownLatch selectorStarted = new CountDownLatch(1);

        WsServer server = new WsServer(
                new InetSocketAddress("127.0.0.1", port),
                LOGGER,
                PlatformStubs.newDispatcher(LOGGER, GSON),
                "Server",
                "",
                true) {

            @Override
            public void onStart() {
                super.onStart();
                selectorStarted.countDown();
                try {
                    // 占住 selector 线程；停止流程会中断它
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    // 预期路径：中断后返回，让 run() 走到 doServerShutdown() 关闭通道
                }
            }
        };

        try {
            server.start();
            assertTrue(selectorStarted.await(5, TimeUnit.SECONDS), "selector 线程应已启动并进入 onStart");
            assertTrue(awaitPortAccepting(port), "启动后端口应可连");

            long startedAt = System.nanoTime();
            boolean terminated = stopWithBound(server, 5_000L);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertTrue(elapsedMillis < 3_000L, "stopBounded 应有界返回，实际耗时 " + elapsedMillis + "ms");
            assertTrue(terminated, "中断后应确认 selector 线程已退出");
            assertTrue(server.isTerminated(), "isTerminated 应与返回值一致");
            assertFalse(isPortAccepting(port), "线程退出后 ServerSocketChannel 应已关闭");
        } finally {
            stopWithBound(server, 5_000L);
        }
    }

    @Test
    @DisplayName("selector 线程不可中断时：stopBounded 有界返回 false，不谎称已终止")
    void stopBoundedReportsFailureForUninterruptibleSelector() throws Exception {
        int port = findFreePort();
        CountDownLatch selectorStarted = new CountDownLatch(1);
        CountDownLatch releaseSelector = new CountDownLatch(1);

        WsServer server = new WsServer(
                new InetSocketAddress("127.0.0.1", port),
                LOGGER,
                PlatformStubs.newDispatcher(LOGGER, GSON),
                "Server",
                "",
                true) {

            @Override
            public void onStart() {
                super.onStart();
                selectorStarted.countDown();
                // 刻意忽略中断：模拟"卡在无法打断的回调里"
                awaitUninterruptibly(releaseSelector);
            }
        };

        try {
            server.start();
            assertTrue(selectorStarted.await(5, TimeUnit.SECONDS), "selector 线程应已启动并进入 onStart");

            long startedAt = System.nanoTime();
            boolean terminated = stopWithBound(server, 5_000L);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertTrue(elapsedMillis < 3_000L, "stopBounded 应有界返回，实际耗时 " + elapsedMillis + "ms");
            assertFalse(terminated, "线程未退出时不得声称已完全终止");
            assertFalse(server.isTerminated());

            // 释放后仍应能正常退出：停止请求已经生效（isclosed），只差线程走到收尾
            releaseSelector.countDown();
            assertTrue(awaitTerminated(server, 5_000L), "释放后 selector 线程应退出");
            assertTrue(awaitPortClosed(port), "线程退出后端口应释放");
        } finally {
            releaseSelector.countDown();
            stopWithBound(server, 5_000L);
        }
    }

    /**
     * 在独立线程上调用 {@code stopBounded}，并给整个调用一个有界上限
     *
     * <p>这样即使实现回退成无限等待，测试也会失败而不是永久挂起。
     */
    private static boolean stopWithBound(WsServer server, long timeoutMillis) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> stopping = executor.submit(() -> server.stopBounded(STOP_TIMEOUT_MILLIS, "test stop"));
            return stopping.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean awaitTerminated(WsServer server, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (server.isTerminated()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return server.isTerminated();
    }

    private static boolean awaitPortAccepting(int port) throws InterruptedException {
        return awaitPortState(port, true, 5_000L);
    }

    private static boolean awaitPortClosed(int port) throws InterruptedException {
        return awaitPortState(port, false, 5_000L);
    }

    private static boolean awaitPortState(int port, boolean accepting, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (isPortAccepting(port) == accepting) {
                return true;
            }
            Thread.sleep(10L);
        }
        return isPortAccepting(port) == accepting;
    }

    private static boolean isPortAccepting(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
