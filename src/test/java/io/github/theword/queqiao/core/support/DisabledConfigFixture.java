package io.github.theword.queqiao.core.support;

import io.github.theword.queqiao.core.config.io.ConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * "全部禁用"配置夹具
 *
 * <p>把 WebSocket Server / Client / Rcon 全部关闭，使 {@code QueQiaoRuntime.start()}
 * 不绑定端口、不建立任何连接 —— 于是可以在测试里安全地走完真实的启动路径。
 *
 * <p>构造时备份原配置文件，{@link #close()} 时还原（原本不存在则删除）。
 *
 * <p>从 {@code QueQiaoRuntimeLifecycleTest} 的内嵌夹具提取而来，
 * 供多个测试类共用。
 */
public final class DisabledConfigFixture implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(DisabledConfigFixture.class);

    private static final Path CONFIG_PATH = ConfigStore.resolveConfigPath(
            PlatformStubs.noopPlatformContext().getDataDirectory());
    private static final Path BACKUP_PATH = CONFIG_PATH.resolveSibling(CONFIG_PATH.getFileName() + ".bak");

    private static final String DISABLED_CONFIG = String.join("\n",
            "enable: true",
            "debug: false",
            "server_name: \"TestServer\"",
            "access_token: \"\"",
            "message_prefix: \"[鹊桥]\"",
            "enable_translation: false",
            "websocket_server:",
            "  enable: false",
            "  host: \"127.0.0.1\"",
            "  port: 8080",
            "websocket_client:",
            "  enable: false",
            "  reconnect_interval: 1",
            "  reconnect_max_times: 1",
            "  url_list: []",
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

    private final byte[] previousContent;

    public DisabledConfigFixture() throws IOException {
        this.previousContent = Files.exists(CONFIG_PATH) ? Files.readAllBytes(CONFIG_PATH) : null;
        Files.createDirectories(CONFIG_PATH.getParent());
        Files.write(CONFIG_PATH, DISABLED_CONFIG.getBytes(StandardCharsets.UTF_8));
        LOGGER.info("已写入禁用全部传输层的测试配置：{}", CONFIG_PATH.toAbsolutePath());
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
