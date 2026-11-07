package io.github.theword.queqiao.core.rcon;

import io.github.theword.queqiao.core.exception.rcon.RconException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RconClientTest {

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(RconClientTest.class);

    /** 断言"某个操作没有阻塞"时使用的宽松上限（避免慢机器上的抖动） */
    private static final long NON_BLOCKING_UPPER_BOUND_MILLIS = 2_000L;

    // ------------------------------------------------------------------
    // 连接
    // ------------------------------------------------------------------

    @Test
    @DisplayName("已连接时重复 connect 不创建第二条连接")
    void connectIsIdempotent() {
        AtomicInteger opened = new AtomicInteger();
        RconClient client = client((host, port, password) -> {
            opened.incrementAndGet();
            return new FakeConnection();
        });

        client.connect();
        client.connect();

        assertEquals(1, opened.get());
        assertTrue(client.isConnected());
    }

    @Test
    @DisplayName("Host / 端口 / 密码按构造参数原样传给连接工厂")
    void passesHostPortAndPasswordToFactory() {
        AtomicReference<String> host = new AtomicReference<>();
        AtomicReference<Integer> port = new AtomicReference<>();
        AtomicReference<String> password = new AtomicReference<>();
        RconClient client = new RconClient(LOGGER, "mc.example.com", 25566, "pw", (h, p, s) -> {
            host.set(h);
            port.set(p);
            password.set(s);
            return new FakeConnection();
        });

        client.connect();

        assertEquals("mc.example.com", host.get(), "不应把 host 硬编码为 localhost");
        assertEquals(Integer.valueOf(25566), port.get());
        assertEquals("pw", password.get());
    }

    @Test
    @DisplayName("旧三参数构造器保留，并使用 127.0.0.1 作为默认 Host")
    void legacyConstructorKeepsDefaultHost() {
        assertEquals("127.0.0.1", RconClient.DEFAULT_HOST, "默认地址必须是 IPv4 回环地址");

        // 旧构造器仍然可用（源码兼容），且不会因为缺少 host 参数而编译失败
        RconClient legacy = new RconClient(LOGGER, 25575, "pw");
        assertFalse(legacy.isConnected(), "未 connect 前不应报告已连接");
    }

    @Test
    @DisplayName("空白 Host 在构造阶段即被拒绝")
    void blankHostIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RconClient(LOGGER, "   ", 25575, "pw", (h, p, s) -> new FakeConnection()));
    }

    // ------------------------------------------------------------------
    // 命令
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未连接时拒绝命令，空命令具有明确错误类型")
    void rejectsDisconnectedAndInvalidCommands() {
        RconClient client = client((host, port, password) -> new FakeConnection());

        RconException disconnected = assertThrows(RconException.class, () -> client.sendCommand("list"));
        assertEquals(RconException.Kind.DISCONNECTED, disconnected.getKind());

        RconException invalid = assertThrows(RconException.class, () -> client.sendCommand(" \t "));
        assertEquals(RconException.Kind.INVALID_COMMAND, invalid.getKind());
        assertThrows(RconException.class, () -> client.sendCommand(null));

        StringBuilder oversized = new StringBuilder();
        for (int index = 0; index < 500; index++) {
            oversized.append('界');
        }
        RconException tooLong = assertThrows(RconException.class, () -> client.sendCommand(oversized.toString()));
        assertEquals(RconException.Kind.INVALID_COMMAND, tooLong.getKind());
    }

    @Test
    @DisplayName("并发命令在同一连接上串行执行")
    void commandsAreSerialized() throws Exception {
        FakeConnection connection = new FakeConnection();
        RconClient client = client((host, port, password) -> connection);
        client.connect();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> client.sendCommand("first"));
            Future<String> second = executor.submit(() -> client.sendCommand("second"));

            assertEquals("first", first.get(2, TimeUnit.SECONDS));
            assertEquals("second", second.get(2, TimeUnit.SECONDS));
            assertEquals(1, connection.maxConcurrentCommands.get());
        } finally {
            executor.shutdownNow();
            client.stop();
        }
    }

    // ------------------------------------------------------------------
    // 强制中止（替代旧的"关闭等待在途命令"契约）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("stop 不等待在途命令：直接中止 Socket，在途命令以 COMMAND_FAILED 结束")
    void stopAbortsInFlightCommandWithoutWaiting() throws Exception {
        CountDownLatch commandEntered = new CountDownLatch(1);
        // 只由 abort() 释放：模拟"服务器迟迟不返回响应"，命令只能靠 Socket 关闭被打断
        CountDownLatch releaseCommand = new CountDownLatch(1);
        FakeConnection connection = new FakeConnection(commandEntered, releaseCommand);
        RconClient client = client((host, port, password) -> connection);
        client.connect();

        ExecutorService executor = Executors.newFixedThreadPool(1);
        AtomicReference<Throwable> commandFailure = new AtomicReference<>();
        try {
            Future<?> command = executor.submit(() -> {
                try {
                    client.sendCommand("list");
                } catch (Throwable error) {
                    commandFailure.set(error);
                }
            });
            assertTrue(commandEntered.await(2, TimeUnit.SECONDS), "命令应开始执行");

            long startedAt = System.nanoTime();
            client.stop();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            assertTrue(
                    elapsedMillis < NON_BLOCKING_UPPER_BOUND_MILLIS,
                    "stop 不应等待在途命令，实际耗时 " + elapsedMillis + "ms");

            command.get(2, TimeUnit.SECONDS);
            assertNotNull(commandFailure.get(), "在途命令应因连接被中止而失败");
            assertEquals(
                    RconException.Kind.COMMAND_FAILED,
                    ((RconException) commandFailure.get()).getKind(),
                    "中止导致的失败应映射为 COMMAND_FAILED，而不是 DISCONNECTED");

            assertFalse(client.isConnected(), "stop 之后不应再报告已连接");
            assertTrue(connection.aborted.get(), "stop 必须中止底层连接");

            assertEquals(
                    RconException.Kind.DISCONNECTED,
                    assertThrows(RconException.class, () -> client.sendCommand("after-stop")).getKind(),
                    "关闭后的新命令应得到明确的断开错误");
        } finally {
            releaseCommand.countDown();
            executor.shutdownNow();
            client.stop();
        }
    }

    @Test
    @DisplayName("重复 stop 幂等：连接只被中止一次")
    void stopIsIdempotent() {
        FakeConnection connection = new FakeConnection();
        RconClient client = client((host, port, password) -> connection);
        client.connect();

        client.stop();
        client.stop();

        assertEquals(1, connection.abortCount.get(), "重复 stop 不得重复中止连接");
        assertFalse(client.isConnected());
    }

    @Test
    @DisplayName("旧连接的失败清理不会清除已被替换的新连接")
    void failureCleanupDoesNotClearReplacementConnection() throws RconException {
        FakeConnection first = new FakeConnection();
        first.failCommand = true;
        FakeConnection second = new FakeConnection();
        AtomicInteger opened = new AtomicInteger();
        RconClient client = new RconClient(LOGGER, RconClient.DEFAULT_HOST, 25575, "pw",
                (host, port, password) -> opened.incrementAndGet() == 1 ? first : second);
        client.connect();

        // 在"清理旧连接"的窗口内发布新连接：模拟旧连接的迟到异常与连接替换交错。
        // 若清理逻辑不带 identity 检查（例如无条件把 connection 置空），新连接会在这里被抹掉。
        first.onAbort = client::connect;

        RconException failure = assertThrows(RconException.class, () -> client.sendCommand("list"));
        assertEquals(RconException.Kind.COMMAND_FAILED, failure.getKind());

        assertEquals(2, opened.get(), "清理旧连接期间应已建立新连接");
        assertTrue(client.isConnected(), "旧连接的清理不得清除新连接");
        assertEquals("ok", client.sendCommand("ok"), "后续命令应使用新连接");
        assertEquals(1, second.commandCount.get(), "新连接应真正收到命令");
        client.stop();
    }

    // ------------------------------------------------------------------
    // 连接建立与停止的竞争
    // ------------------------------------------------------------------

    @Test
    @DisplayName("连接建立期间 stop 不等待，迟到的连接不会发布且会被中止")
    void stopDuringConnectDoesNotWaitAndDiscardsLateConnection() throws Exception {
        CountDownLatch connectEntered = new CountDownLatch(1);
        CountDownLatch releaseConnect = new CountDownLatch(1);
        FakeConnection late = new FakeConnection();
        RconClient client = new RconClient(LOGGER, RconClient.DEFAULT_HOST, 25575, "pw",
                (host, port, password) -> {
                    connectEntered.countDown();
                    awaitOrFail(releaseConnect, "test connect");
                    return late;
                });

        ExecutorService executor = Executors.newFixedThreadPool(1);
        try {
            Future<?> connecting = executor.submit(client::connect);
            assertTrue(connectEntered.await(2, TimeUnit.SECONDS), "连接应已开始");

            long startedAt = System.nanoTime();
            client.stop();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            assertTrue(
                    elapsedMillis < NON_BLOCKING_UPPER_BOUND_MILLIS,
                    "stop 不应等待连接建立完成，实际耗时 " + elapsedMillis + "ms");

            releaseConnect.countDown();
            connecting.get(2, TimeUnit.SECONDS);

            assertFalse(client.isConnected(), "已停止的实例不得发布迟到的连接");
            assertTrue(late.aborted.get(), "迟到的连接必须被中止，否则 socket 泄漏");
        } finally {
            releaseConnect.countDown();
            executor.shutdownNow();
            client.stop();
        }
    }

    @Test
    @DisplayName("连接建立期间重复 connect 不会启动第二个连接")
    void concurrentConnectIsNotStartedTwice() throws Exception {
        CountDownLatch connectEntered = new CountDownLatch(1);
        CountDownLatch releaseConnect = new CountDownLatch(1);
        AtomicInteger opened = new AtomicInteger();
        RconClient client = new RconClient(LOGGER, RconClient.DEFAULT_HOST, 25575, "pw",
                (host, port, password) -> {
                    opened.incrementAndGet();
                    connectEntered.countDown();
                    awaitOrFail(releaseConnect, "test connect");
                    return new FakeConnection();
                });

        ExecutorService executor = Executors.newFixedThreadPool(1);
        try {
            Future<?> connecting = executor.submit(client::connect);
            assertTrue(connectEntered.await(2, TimeUnit.SECONDS), "首次连接应已开始");

            client.connect();
            assertEquals(1, opened.get(), "正在连接时不得再启动第二个连接");

            releaseConnect.countDown();
            connecting.get(2, TimeUnit.SECONDS);
            assertTrue(client.isConnected());
        } finally {
            releaseConnect.countDown();
            executor.shutdownNow();
            client.stop();
        }
    }

    @Test
    @DisplayName("stop 是终态：之后的 connect 被拒绝，不会重新发布连接")
    void stopIsTerminal() {
        AtomicInteger opened = new AtomicInteger();
        RconClient client = client((host, port, password) -> {
            opened.incrementAndGet();
            return new FakeConnection();
        });
        client.connect();
        client.stop();

        client.connect();

        assertEquals(1, opened.get(), "停止后的实例不应再建立连接");
        assertFalse(client.isConnected());
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static RconClient client(RconClient.ConnectionFactory factory) {
        return new RconClient(LOGGER, RconClient.DEFAULT_HOST, 25575, "test-password", factory);
    }

    private static void awaitOrFail(CountDownLatch latch, String what) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IOException(what + " timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(what + " interrupted", e);
        }
    }

    private static final class FakeConnection implements RconClient.Connection {

        private final AtomicInteger activeCommands = new AtomicInteger();
        private final AtomicInteger maxConcurrentCommands = new AtomicInteger();
        private final AtomicInteger commandCount = new AtomicInteger();
        private final AtomicInteger abortCount = new AtomicInteger();
        private final AtomicBoolean aborted = new AtomicBoolean();
        private final CountDownLatch commandEntered;
        private final CountDownLatch releaseCommand;

        /** 命令是否立即以 IOException 失败（模拟对端断开） */
        private volatile boolean failCommand;

        /** abort() 时的回调（用于构造"清理旧连接时新连接已就位"的交错） */
        private volatile Runnable onAbort;

        private FakeConnection() {
            this(null, null);
        }

        private FakeConnection(CountDownLatch commandEntered, CountDownLatch releaseCommand) {
            this.commandEntered = commandEntered;
            this.releaseCommand = releaseCommand;
        }

        @Override
        public String command(String command) throws IOException {
            if (aborted.get()) {
                throw new IOException("connection is aborted");
            }
            if (failCommand) {
                throw new IOException("simulated command failure");
            }
            int active = activeCommands.incrementAndGet();
            maxConcurrentCommands.accumulateAndGet(active, Math::max);
            try {
                if (commandEntered != null) {
                    commandEntered.countDown();
                    awaitOrFail(releaseCommand, "test command");
                    if (aborted.get()) {
                        // 真实 Socket 被关闭时读操作会抛 IOException，这里等价模拟
                        throw new IOException("connection is aborted");
                    }
                } else {
                    try {
                        Thread.sleep(25L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("test command interrupted", e);
                    }
                }
                commandCount.incrementAndGet();
                return command;
            } finally {
                activeCommands.decrementAndGet();
            }
        }

        @Override
        public void abort() {
            abortCount.incrementAndGet();
            aborted.set(true);
            if (releaseCommand != null) {
                releaseCommand.countDown();
            }
            Runnable callback = onAbort;
            if (callback != null) {
                callback.run();
            }
        }
    }
}
