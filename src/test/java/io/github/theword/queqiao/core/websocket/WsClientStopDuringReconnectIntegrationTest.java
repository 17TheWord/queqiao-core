package io.github.theword.queqiao.core.websocket;

import io.github.theword.queqiao.core.handle.HandleProtocolMessage;
import io.github.theword.queqiao.core.support.PlatformStubs;
import io.github.theword.queqiao.core.support.RawWebSocketPeer;
import com.google.gson.Gson;
import org.java_websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Collections;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * stop 与 reconnect 并发的真实集成测试
 *
 * <p><b>为什么要真实 socket</b>：{@code WebSocketClient.reconnect()} 的实现是
 * {@code reset(); connect();}，其中 {@code reset()} 调用无超时的 {@code closeBlocking()}。
 * 只有让对端<b>延迟回应 close 帧</b>，才能把"重连已经开始执行"这个窗口稳定地撑开；
 * 直接调用回调（{@code onOpen(null)}）只能验证回调内的保护，无法证明竞态时序。
 *
 * <p>时序：
 * <pre>
 * 1. 建立连接（连接 #0），并验证消息能正常进入业务处理
 * 2. reconnectNow() → reset() 发出 close 帧后阻塞在 closeBlocking()（对端不回应）
 * 3. 此时 stop：stopped=true，generation++，但重连已在执行中，无法被取消
 * 4. 对端回应 close → reset() 继续 → connect() → 建立连接 #1
 * 5. onOpen 发现 stopped → 立即关闭连接 #1
 * </pre>
 */
class WsClientStopDuringReconnectIntegrationTest {

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(WsClientStopDuringReconnectIntegrationTest.class);
    private static final Gson GSON = new Gson();

    private ScheduledThreadPoolExecutor scheduler;
    private WsClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.stopWithoutReconnect(1000, "test cleanup");
            client = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Test
    @DisplayName("重连执行期间 stop：竞态建立的连接会被立即关闭，且不再处理消息")
    void stopDuringReconnectDiscardsEstablishedConnection() throws Exception {
        AtomicInteger handled = new AtomicInteger();
        try (RawWebSocketPeer peer = new RawWebSocketPeer()) {
            client = newClient(peer.getPort(), handled);

            client.connect();
            assertTrue(peer.awaitConnections(1, 5_000L), "客户端应建立第一条连接");
            assertTrue(awaitCondition(client::isOpen, 5_000L), "客户端应完成握手");

            peer.sendText(0, "{\"api\":\"send_rcon_command\"}");
            assertTrue(awaitCondition(() -> handled.get() == 1, 5_000L), "正常运行时应处理消息");

            // 重连开始：reset() 发出 close 帧后阻塞，对端此时故意不回应
            client.reconnectNow();
            assertTrue(
                    awaitCondition(client::isReconnectInProgress, 5_000L),
                    "重连应已开始执行（generation 已无法撤销这次重连）");

            client.stopWithoutReconnect(1000, "stop during reconnect");
            assertTrue(client.isStopped(), "客户端应已停止");

            assertTrue(peer.awaitCloseFrame(0, 5_000L), "reset() 应向旧连接发出 close 帧");
            peer.releaseClose(0);

            // 竞态结果：重连在 stop 之后仍然建立了第二条连接
            assertTrue(peer.awaitConnections(2, 5_000L), "竞态下重连确实建立了第二条连接");
            assertTrue(peer.awaitCloseFrame(1, 5_000L), "已停止的客户端必须关闭这条新连接");
            assertTrue(awaitCondition(() -> !client.isOpen(), 5_000L), "客户端不应保留活动连接");

            assertEquals(1, handled.get(), "停止后不得再有消息进入协议处理");
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private WsClient newClient(int port, AtomicInteger handled) {
        scheduler = new ScheduledThreadPoolExecutor(1);
        HandleProtocolMessage dispatcher = new HandleProtocolMessage(
                LOGGER, GSON, Collections.emptyList(), PlatformStubs.newRuntimeUtils(LOGGER)) {

            @Override
            public String handleWebsocketJson(WebSocket webSocket, String rawJsonMessage) {
                handled.incrementAndGet();
                return null;
            }
        };
        return new WsClient(
                URI.create("ws://127.0.0.1:" + port + "/queqiao"),
                LOGGER,
                scheduler,
                new ReconnectPolicy(1, 5),
                dispatcher,
                "TestServer",
                "",
                true,
                PlatformStubs.newRuntimeUtils(LOGGER));
    }

    private static boolean awaitCondition(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return condition.getAsBoolean();
    }
}
