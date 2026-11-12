package io.github.theword.queqiao.core.websocket;

import io.github.theword.queqiao.core.handle.HandleProtocolMessage;
import io.github.theword.queqiao.core.support.PlatformStubs;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WsClient} 停止后的消息与连接语义
 *
 * <p>覆盖两个只在竞态下才会出现、但后果明确的行为：
 * <ol>
 *     <li>停止后仍有迟到帧到达时，不得进入协议处理；</li>
 *     <li>停止与 {@code super.reconnect()} 并发时连接可能仍然建立成功，
 *         此时 {@code onOpen} 必须主动丢弃该连接（{@code generation} 拦不住已开始的重连）。</li>
 * </ol>
 *
 * <p>这里不启动真实 socket：{@code onOpen} / {@code onMessage} 都是库的公开回调，
 * 直接调用即可确定性地复现"竞态发生后的状态"，避免用线程调度去赌时序。
 */
class WsClientStopTest {

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(WsClientStopTest.class);
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
    @DisplayName("停止后收到的消息不再进入协议处理")
    void stoppedClientDoesNotDispatchMessages() {
        AtomicInteger handled = new AtomicInteger();
        client = newClient(handled);

        client.onMessage("{\"api\":\"send_rcon_command\"}");
        assertEquals(1, handled.get(), "正常运行时应进入协议处理");

        client.stopWithoutReconnect(1000, "test stop");

        client.onMessage("{\"api\":\"send_rcon_command\"}");
        assertEquals(1, handled.get(), "停止后不得再分发消息");
        assertTrue(client.isStopped(), "客户端应处于已停止状态");
    }

    @Test
    @DisplayName("停止后仍建立成功的连接会被立即丢弃，且不重置停止状态")
    void stoppedClientDiscardsConnectionEstablishedAfterStop() {
        AtomicInteger handled = new AtomicInteger();
        client = newClient(handled);

        client.stopWithoutReconnect(1000, "test stop");

        // 模拟竞态结果：super.reconnect() 在 stop() 之后完成了连接并回调 onOpen
        client.onOpen(null);

        assertTrue(client.isStopped(), "onOpen 不得把已停止的客户端重新视为运行中");
        assertFalse(client.isOpen(), "已停止的客户端不应保留活动连接");

        client.onMessage("{\"api\":\"send_rcon_command\"}");
        assertEquals(0, handled.get(), "竞态建立的连接也不得进入协议处理");
    }

    private WsClient newClient(AtomicInteger handled) {
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
                URI.create("ws://127.0.0.1:1/not-connected"),
                LOGGER,
                scheduler,
                new ReconnectPolicy(1, 1),
                dispatcher,
                "TestServer",
                "",
                true,
                PlatformStubs.newRuntimeUtils(LOGGER));
    }
}
