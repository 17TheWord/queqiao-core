package io.github.theword.queqiao.core.support;

import io.github.theword.queqiao.core.config.io.ConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 可写入任意内容的 {@code config.yml} 夹具
 *
 * <p>{@link DisabledConfigFixture} 只能表达"全部禁用"这一种形态；本夹具面向需要
 * <b>真实启动 WebSocket / RCON</b> 的生命周期测试：先写入一份配置，运行中再改写它，
 * 然后调用 {@code QueQiaoRuntime#reload()}。
 *
 * <p>构造时备份原配置文件，{@link #close()} 时还原（原本不存在则删除）。
 */
public final class RuntimeConfigFixture implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeConfigFixture.class);

    private static final Path CONFIG_PATH = ConfigStore.resolveConfigPath(
            PlatformStubs.noopPlatformContext().getDataDirectory());
    private static final Path BACKUP_PATH = CONFIG_PATH.resolveSibling(CONFIG_PATH.getFileName() + ".bak");

    private final byte[] previousContent;

    public RuntimeConfigFixture(String yaml) throws IOException {
        this.previousContent = Files.exists(CONFIG_PATH) ? Files.readAllBytes(CONFIG_PATH) : null;
        write(yaml);
    }

    /**
     * 覆盖写入配置（用于模拟"用户在运行期改了配置文件"）
     *
     * @param yaml 完整配置内容
     */
    public void write(String yaml) throws IOException {
        Files.createDirectories(CONFIG_PATH.getParent());
        Files.write(CONFIG_PATH, yaml.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成一份完整配置
     *
     * @param webSocketServerEnabled 是否启用 WebSocket Server
     * @param webSocketPort          WebSocket Server 端口
     * @param rconEnabled            是否启用 RCON
     * @param rconHost               RCON 地址
     * @param rconPort               RCON 端口
     * @param rconPassword           RCON 密码
     * @return YAML 文本
     */
    public static String yaml(
            boolean webSocketServerEnabled,
            int webSocketPort,
            boolean rconEnabled,
            String rconHost,
            int rconPort,
            String rconPassword) {
        return String.join("\n",
                "enable: true",
                "debug: false",
                "server_name: \"TestServer\"",
                "access_token: \"\"",
                "message_prefix: \"[鹊桥]\"",
                "enable_translation: false",
                "websocket_server:",
                "  enable: " + webSocketServerEnabled,
                "  host: \"127.0.0.1\"",
                "  port: " + webSocketPort,
                "websocket_client:",
                "  enable: false",
                "  reconnect_interval: 1",
                "  reconnect_max_times: 1",
                "  url_list: []",
                "rcon:",
                "  enable: " + rconEnabled,
                "  host: \"" + rconHost + "\"",
                "  port: " + rconPort,
                "  password: \"" + rconPassword + "\"",
                "subscribe_event:",
                "  player_chat: false",
                "  player_death: false",
                "  player_join: false",
                "  player_quit: false",
                "  player_command: false",
                "  player_advancement: false",
                "ignored_commands: []",
                "");
    }

    @Override
    public void close() throws IOException {
        if (previousContent != null) {
            Files.write(CONFIG_PATH, previousContent);
            return;
        }
        Files.deleteIfExists(CONFIG_PATH);
        Files.deleteIfExists(BACKUP_PATH);
        LOGGER.debug("已删除测试写入的配置文件");
    }
}
