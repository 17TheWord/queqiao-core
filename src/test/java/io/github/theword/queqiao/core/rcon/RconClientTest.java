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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RconClientTest {

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(RconClientTest.class);

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

    @Test
    @DisplayName("关闭等待在途命令完成，之后的命令明确失败")
    void stopWaitsForCommandAndThenRejectsFurtherCommands() throws Exception {
        CountDownLatch commandEntered = new CountDownLatch(1);
        CountDownLatch releaseCommand = new CountDownLatch(1);
        FakeConnection connection = new FakeConnection(commandEntered, releaseCommand);
        RconClient client = client((host, port, password) -> connection);
        client.connect();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> commandFailure = new AtomicReference<>();
        CountDownLatch stopCompleted = new CountDownLatch(1);
        try {
            Future<?> command = executor.submit(() -> {
                try {
                    assertEquals("running", client.sendCommand("running"));
                } catch (Throwable error) {
                    commandFailure.set(error);
                }
            });
            assertTrue(commandEntered.await(2, TimeUnit.SECONDS), "命令应开始执行");

            CountDownLatch stopStarted = new CountDownLatch(1);
            AtomicReference<Thread> stopThread = new AtomicReference<>();
            Future<?> stop = executor.submit(() -> {
                stopThread.set(Thread.currentThread());
                stopStarted.countDown();
                client.stop();
                stopCompleted.countDown();
            });
            assertTrue(stopStarted.await(2, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (stopThread.get().getState() != Thread.State.BLOCKED
                    && stopCompleted.getCount() != 0
                    && System.nanoTime() < deadline) {
                Thread.sleep(1L);
            }
            assertEquals(Thread.State.BLOCKED, stopThread.get().getState(),
                    "stop 应等待正在使用连接的命令，而不是并发关闭 socket");

            releaseCommand.countDown();
            command.get(2, TimeUnit.SECONDS);
            stop.get(2, TimeUnit.SECONDS);
            assertNull(commandFailure.get());
            assertFalse(client.isConnected());
            assertTrue(connection.closed.get());
            assertEquals(RconException.Kind.DISCONNECTED,
                    assertThrows(RconException.class, () -> client.sendCommand("after-stop")).getKind());
        } finally {
            releaseCommand.countDown();
            executor.shutdownNow();
            client.stop();
        }
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

    private static RconClient client(RconClient.ConnectionFactory factory) {
        return new RconClient(LOGGER, RconClient.DEFAULT_HOST, 25575, "test-password", factory);
    }

    private static final class FakeConnection implements RconClient.Connection {

        private final AtomicInteger activeCommands = new AtomicInteger();
        private final AtomicInteger maxConcurrentCommands = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final CountDownLatch commandEntered;
        private final CountDownLatch releaseCommand;

        private FakeConnection() {
            this(null, null);
        }

        private FakeConnection(CountDownLatch commandEntered, CountDownLatch releaseCommand) {
            this.commandEntered = commandEntered;
            this.releaseCommand = releaseCommand;
        }

        @Override
        public String command(String command) throws IOException {
            if (closed.get()) {
                throw new IOException("connection is closed");
            }
            int active = activeCommands.incrementAndGet();
            maxConcurrentCommands.accumulateAndGet(active, Math::max);
            try {
                if (commandEntered != null) {
                    commandEntered.countDown();
                    try {
                        if (!releaseCommand.await(2, TimeUnit.SECONDS)) {
                            throw new IOException("test command timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("test command interrupted", e);
                    }
                } else {
                    try {
                        Thread.sleep(25L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("test command interrupted", e);
                    }
                }
                return command;
            } finally {
                activeCommands.decrementAndGet();
            }
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
