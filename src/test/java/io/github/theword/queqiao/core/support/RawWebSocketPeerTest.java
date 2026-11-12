package io.github.theword.queqiao.core.support;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RawWebSocketPeer} 的清理路径
 *
 * <p>重点：连接线程在收到 close 帧后会阻塞等待 {@code releaseClose()}。
 * 若测试断言失败走 {@link RawWebSocketPeer#close()}，必须能唤醒该线程——
 * 仅关闭 Socket 不会唤醒 CountDownLatch，线程会一直挂到 JVM 退出。
 */
class RawWebSocketPeerTest {

    @Test
    @DisplayName("close() 能唤醒阻塞在 close 帧等待上的连接线程")
    void closeWakesConnectionBlockedOnRelease() throws Exception {
        RawWebSocketPeer peer = new RawWebSocketPeer();
        WebSocketClient client = newClient(peer.getPort());
        try {
            client.connect();
            assertTrue(peer.awaitConnections(1, 5_000L), "应建立连接");
            assertTrue(awaitCondition(client::isOpen, 5_000L), "客户端应完成握手");

            client.close(1000, "bye");
            assertTrue(peer.awaitCloseFrame(0, 5_000L), "对端应收到 close 帧");
            // 此刻连接线程停在 awaitRelease()：只有 releaseClose() 或 close() 能放行

            peer.close();

            assertTrue(
                    peer.awaitDisconnected(0, 2_000L),
                    "close() 必须释放 closeReleased 并关闭 Socket，否则连接线程会永久阻塞");
        } finally {
            peer.close();
            client.close();
        }
    }

    private static WebSocketClient newClient(int port) {
        return new WebSocketClient(URI.create("ws://127.0.0.1:" + port + "/probe")) {
            @Override
            public void onOpen(ServerHandshake serverHandshake) {
            }

            @Override
            public void onMessage(String message) {
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
            }

            @Override
            public void onError(Exception exception) {
            }
        };
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
