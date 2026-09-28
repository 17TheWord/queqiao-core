package io.github.theword.queqiao.core.command;

import io.github.theword.queqiao.core.command.subCommand.client.ListCommand;
import io.github.theword.queqiao.core.command.subCommand.client.ReconnectCommand;
import io.github.theword.queqiao.core.command.subCommand.server.InfoCommand;
import io.github.theword.queqiao.core.constant.CommandConstant;
import io.github.theword.queqiao.core.config.io.ConfigStore;
import io.github.theword.queqiao.core.platform.TestCommandSource;
import io.github.theword.queqiao.core.runtime.QueQiaoRuntime;
import io.github.theword.queqiao.core.support.PlatformStubs;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令层测试（WS-E：E5 / E6 / E7）
 *
 * <p>
 * 覆盖三条直接读取 Manager 状态的命令：{@code server info}、{@code client list}、
 * {@code client reconnect}。
 *
 * <p>
 * <b>测试方式</b>：写一份配置夹具到 {@code plugins/queqiao/config.yml}（结束时还原原文件），
 * 其中服务端端口使用测试时选定的空闲端口、客户端 URL 指向一个无人监听的端口，
 * 然后创建并启动 {@code QueQiaoRuntime} —— 即可得到"活的" Manager 与真实监听的服务端。
 * 命令对象所需的依赖（命令返回服务、Logger、Config、Manager）全部从该 Runtime 显式取得。
 *
 * <p>
 * <b>已知代价</b>：本类会写工作目录下的配置文件并绑定端口（属评审记录的测试隔离欠债）；
 * 端口用"绑定 0 号端口取空闲端口再释放"获取，存在极小竞态窗口。
 */
@Isolated
class CommandLayerTest {

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandLayerTest.class);

    private static final String SERVER_NAME = "TestServer";

    /**
     * 记录型平台上下文：命令回执经 {@code returnCallBackMessage} 落到这里
     */
    private final PlatformStubs.RecordingPlatformContext PLATFORM =
            PlatformStubs.recordingPlatformContext();

    /**
     * 当前用例的 Runtime（由 {@link #startRuntime()} 创建并启动）
     */
    private QueQiaoRuntime runtime;

    /**
     * 创建并启动 Runtime；命令树所需的 Manager 只有在 start() 之后才存在
     */
    private void startRuntime() {
        runtime = QueQiaoRuntime.create(null, PLATFORM, null);
        runtime.start();
    }

    @AfterEach
    void tearDown() {
        QueQiaoRuntime current = runtime;
        runtime = null;
        if (current != null) {
            current.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // E5：server info 只读一次连接集合
    // ------------------------------------------------------------------

    @Test
    @DisplayName("server info 列出全部连接且编号连续（E5）")
    void infoCommandListsAllConnections() throws Exception {
        int serverPort = findFreePort();
        int deadPort = findFreePort();

        try (ConfigFixture ignored = new ConfigFixture(serverPort, deadPort)) {
            startRuntime();

            ProbeClient first = new ProbeClient(serverPort);
            ProbeClient second = new ProbeClient(serverPort);
            try {
                first.connect();
                second.connect();
                assertTrue(first.awaitOpen(10_000L), "第一个客户端应完成握手");
                assertTrue(second.awaitOpen(10_000L), "第二个客户端应完成握手");
                awaitCondition(
                        () -> runtime.getWebsocketManager().getWsServer().getConnections().size() == 2,
                        10_000L,
                        "服务端应登记 2 个连接");

                PLATFORM.clearRecordings();
                new InfoCommand(PLATFORM, LOGGER, runtime.getConfig(), runtime.getWebsocketManager())
                        .execute(new TestCommandSource(), Collections.emptyList());
                List<String> messages = PLATFORM.getReturnMessages();

                assertTrue(containsAny(messages, "已有 2 个连接"), "应报告 2 个连接，实际=" + messages);
                assertTrue(containsAny(messages, "1 来自"), "应有第 1 条连接明细，实际=" + messages);
                assertTrue(containsAny(messages, "2 来自"), "应有第 2 条连接明细，实际=" + messages);
                assertTrue(containsAny(messages, "127.0.0.1"), "连接明细应含远端地址，实际=" + messages);
            } finally {
                first.close();
                second.close();
            }
        }
    }

    // ------------------------------------------------------------------
    // E6：client list 取一次配置副本 + 编号从 1 开始
    // ------------------------------------------------------------------

    @Test
    @DisplayName("client list 编号从 1 开始且数量正确（E6）")
    void listCommandNumbersFromOne() throws Exception {
        int serverPort = findFreePort();
        int deadPort = findFreePort();

        try (ConfigFixture ignored = new ConfigFixture(serverPort, deadPort)) {
            startRuntime();

            PLATFORM.clearRecordings();
            new ListCommand(PLATFORM, LOGGER, runtime.getConfig(), runtime.getWebsocketManager())
                    .execute(new TestCommandSource(), Collections.emptyList());
            List<String> messages = PLATFORM.getReturnMessages();

            assertTrue(containsAny(messages, "共 2 个 Client"), "应报告 2 个 Client，实际=" + messages);
            assertTrue(containsAny(messages, "1 连接至"), "编号应从 1 开始，实际=" + messages);
            assertTrue(containsAny(messages, "2 连接至"), "应有第 2 项，实际=" + messages);
            assertTrue(
                    messages.stream().noneMatch(message -> message.contains("0 连接至")),
                    "不应出现从 0 开始的编号，实际=" + messages);
        }
    }

    // ------------------------------------------------------------------
    // E7：reconnect 措辞与语义
    // ------------------------------------------------------------------

    @Test
    @DisplayName("client reconnect 提示为“已安排重连”而非“已重新连接”（E7）")
    void reconnectCommandReportsScheduledInsteadOfConnected() throws Exception {
        int serverPort = findFreePort();
        int deadPort = findFreePort();

        try (ConfigFixture ignored = new ConfigFixture(serverPort, deadPort)) {
            startRuntime();

            PLATFORM.clearRecordings();
            new ReconnectCommand(PLATFORM, LOGGER, runtime.getWebsocketManager())
                    .reconnect(new TestCommandSource(), false);
            List<String> messages = PLATFORM.getReturnMessages();

            assertTrue(
                    containsAny(messages, CommandConstant.RECONNECTED),
                    "应使用“已安排重连”措辞，实际=" + messages);
            assertTrue(
                    containsAny(messages, "正在重连未打开的 Websocket Client"),
                    "起始提示应为动作描述，实际=" + messages);
            // 两个客户端都未打开（指向无人监听的端口），因此都应被安排重连
            assertTrue(
                    messages.stream().filter(message -> message.contains("正在尝试重连")).count() == 2,
                    "两个未连接的客户端都应被安排重连，实际=" + messages);
        }
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    private static boolean containsAny(List<String> messages, String fragment) {
        return messages.stream().anyMatch(message -> message.contains(fragment));
    }

    private static void awaitCondition(BooleanSupplier condition, long timeoutMillis, String description)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("等待超时（" + timeoutMillis + "ms）：" + description);
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    /**
     * 连接到测试服务端的探测客户端
     */
    private static final class ProbeClient extends WebSocketClient {

        private final CountDownLatch openLatch = new CountDownLatch(1);

        private ProbeClient(int port) throws Exception {
            super(new URI("ws://127.0.0.1:" + port + "/minecraft/ws"));
            addHeader("x-self-name", SERVER_NAME);
            addHeader("x-client-origin", "test-probe");
        }

        @Override
        public void onOpen(ServerHandshake serverHandshake) {
            openLatch.countDown();
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

        private boolean awaitOpen(long timeoutMillis) throws InterruptedException {
            return openLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 配置夹具：服务端监听空闲端口、客户端指向无人监听的端口；结束时还原原配置
     */
    private static final class ConfigFixture implements AutoCloseable {

        private static final Path CONFIG_PATH = ConfigStore.resolveConfigPath(false);
        private static final Path BACKUP_PATH = CONFIG_PATH.resolveSibling(CONFIG_PATH.getFileName() + ".bak");

        private final byte[] previousContent;

        private ConfigFixture(int serverPort, int deadPort) throws IOException {
            this.previousContent = Files.exists(CONFIG_PATH) ? Files.readAllBytes(CONFIG_PATH) : null;
            Files.createDirectories(CONFIG_PATH.getParent());

            String content = String.join("\n",
                    "enable: true",
                    "debug: false",
                    "server_name: \"" + SERVER_NAME + "\"",
                    "access_token: \"\"",
                    "message_prefix: \"[鹊桥]\"",
                    "enable_translation: false",
                    "websocket_server:",
                    "  enable: true",
                    "  host: \"127.0.0.1\"",
                    "  port: " + serverPort,
                    "websocket_client:",
                    "  enable: true",
                    "  reconnect_interval: 1",
                    "  reconnect_max_times: 1",
                    "  url_list:",
                    "    - \"ws://127.0.0.1:" + deadPort + "/client-a\"",
                    "    - \"ws://127.0.0.1:" + deadPort + "/client-b\"",
                    "rcon:",
                    "  enable: false",
                    "  port: 25575",
                    "  password: \"\"",
                    "subscribe_event:",
                    "  player_chat: false",
                    "  player_death: false",
                    "  player_join: false",
                    "  player_quit: false",
                    "  player_command: false",
                    "  player_advancement: false",
                    "ignored_commands: []",
                    "");

            Files.write(CONFIG_PATH, content.getBytes(StandardCharsets.UTF_8));
            LOGGER.info("已写入命令层测试配置：serverPort={}, deadPort={}", serverPort, deadPort);
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
