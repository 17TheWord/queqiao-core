package io.github.theword.queqiao.core.config.io;

import io.github.theword.queqiao.core.config.ConfigKeys;
import io.github.theword.queqiao.core.config.exception.ConfigValidationException;
import io.github.theword.queqiao.core.config.schema.ConfigRegistry;
import io.github.theword.queqiao.core.config.Config;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 完整启动链集成测试（Phase 7 §34）
 *
 * <pre>
 * config.yml
 *    ↓ ConfigFileReader（文件 + YAML 解析）
 *    ↓ ConfigLoader（已解析 Map → 运行时）
 *    ↓ Config（唯一配置状态）
 *    ↓ 业务读取（ConfigKeys.*）
 * </pre>
 */
class ConfigIntegrationTest {

    private static final String CONFIG_YAML = String.join(
                    "\n",
                    "enable: true",
                    "server_name: \"MyServer\"",
                    "websocket_server:",
                    "  enable: true",
                    "  host: \"0.0.0.0\"",
                    "  port: 19132",
                    "rcon:",
                    "  enable: true",
                    "  port: 25575",
                    "  password: \"pw\"",
                    "ignored_commands:",
                    "  - say",
                    "")
            + "\n";

    @Test
    @DisplayName("文件 → Reader → Loader → Runtime → 业务读取：全链路可用")
    void fullChainFromFileToBusinessReads(@TempDir Path tempDir) throws IOException {
        Path configPath = tempDir.resolve("config.yml");
        Files.write(configPath, CONFIG_YAML.getBytes(StandardCharsets.UTF_8));

        ConfigRegistry registry = new ConfigRegistry();
        ConfigKeys.registerAll(registry);
        Config runtime = new Config(registry);

        ConfigFileReader.Result read = ConfigFileReader.read(configPath);
        assertEquals(ConfigFileState.VALID, read.getState(), read.getErrorMessage());
        new ConfigLoader(registry, runtime).load(read.getState(), read.getMap());

        // §34 要求验证的业务读取项
        assertTrue(runtime.get(ConfigKeys.ENABLE));
        assertEquals("MyServer", runtime.get(ConfigKeys.SERVER_NAME));
        assertTrue(runtime.get(ConfigKeys.WebSocket.ENABLE));
        assertEquals("0.0.0.0", runtime.get(ConfigKeys.WebSocket.HOST));
        assertEquals(Integer.valueOf(19132), runtime.get(ConfigKeys.WebSocket.PORT));
        assertTrue(runtime.get(ConfigKeys.Rcon.ENABLE));

        Set<String> ignored = ConfigKeys.effectiveIgnoredCommands(runtime);
        assertTrue(ignored.contains("say"), "用户配置的忽略命令应生效：" + ignored);
        assertTrue(ignored.contains("login"), "强制忽略项必须始终生效：" + ignored);

        // 未在文件中出现的字段 → 使用 Schema 默认值
        assertEquals(Integer.valueOf(5), runtime.get(ConfigKeys.WebSocketClient.RECONNECT_INTERVAL));
        assertTrue(runtime.isDefault(ConfigKeys.WebSocketClient.RECONNECT_INTERVAL));
    }

    @Test
    @DisplayName("配置非法：不修改运行时状态，且不会启动出半套配置")
    void invalidConfigLeavesRuntimeUntouched(@TempDir Path tempDir) throws IOException {
        Path configPath = tempDir.resolve("config.yml");
        Files.write(configPath, CONFIG_YAML.getBytes(StandardCharsets.UTF_8));

        ConfigRegistry registry = new ConfigRegistry();
        ConfigKeys.registerAll(registry);
        Config runtime = new Config(registry);
        ConfigLoader loader = new ConfigLoader(registry, runtime);

        ConfigFileReader.Result valid = ConfigFileReader.read(configPath);
        loader.load(valid.getState(), valid.getMap());
        String before = runtime.describeEntries().toString();

        // 写入非法端口后再加载
        Files.write(
                configPath,
                CONFIG_YAML.replace("port: 19132", "port: 99999").getBytes(StandardCharsets.UTF_8));
        ConfigFileReader.Result broken = ConfigFileReader.read(configPath);

        try {
            loader.load(broken.getState(), broken.getMap());
            org.junit.jupiter.api.Assertions.fail("非法配置应当抛出异常");
        } catch (RuntimeException expected) {
            // 预期
        }

        assertEquals(before, runtime.describeEntries().toString(), "加载失败必须保持原状态");
        assertEquals(Integer.valueOf(19132), runtime.get(ConfigKeys.WebSocket.PORT));
    }

    // ------------------------------------------------------------------
    // rcon.host
    // ------------------------------------------------------------------

    @Test
    @DisplayName("rcon.host 缺失时使用默认 IPv4 回环地址，显式配置时按用户值读取")
    void rconHostFallsBackToDefaultAndReadsUserValue(@TempDir Path tempDir) throws IOException {
        // 上面 CONFIG_YAML 的 rcon 区块没有 host —— 即"旧配置文件"的形态
        Path configPath = tempDir.resolve("config.yml");
        Files.write(configPath, CONFIG_YAML.getBytes(StandardCharsets.UTF_8));

        ConfigRegistry registry = new ConfigRegistry();
        ConfigKeys.registerAll(registry);
        Config runtime = new Config(registry);
        ConfigLoader loader = new ConfigLoader(registry, runtime);

        ConfigFileReader.Result read = ConfigFileReader.read(configPath);
        loader.load(read.getState(), read.getMap());

        assertEquals("127.0.0.1", runtime.get(ConfigKeys.Rcon.HOST), "缺失时应取 Schema 默认值");
        assertTrue(runtime.isDefault(ConfigKeys.Rcon.HOST), "缺失字段来源应为 DEFAULT");

        // 显式配置自定义地址（此处用域名，顺带验证不做 IPv4 限制）
        Files.write(
                configPath,
                CONFIG_YAML.replace("rcon:\n", "rcon:\n  host: \"mc.example.com\"\n")
                        .getBytes(StandardCharsets.UTF_8));
        ConfigFileReader.Result custom = ConfigFileReader.read(configPath);
        loader.load(custom.getState(), custom.getMap());

        assertEquals("mc.example.com", runtime.get(ConfigKeys.Rcon.HOST));
        assertTrue(runtime.contains(ConfigKeys.Rcon.HOST), "显式配置来源应为 USER");
    }

    @Test
    @DisplayName("rcon.host 支持 IPv4 / IPv6 字面地址，不被格式限制")
    void rconHostAcceptsIpv4AndIpv6Literals(@TempDir Path tempDir) throws IOException {
        Path configPath = tempDir.resolve("config.yml");

        ConfigRegistry registry = new ConfigRegistry();
        ConfigKeys.registerAll(registry);
        Config runtime = new Config(registry);
        ConfigLoader loader = new ConfigLoader(registry, runtime);

        for (String host : new String[] {"127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost", "mc.example.com"}) {
            Files.write(
                    configPath,
                    CONFIG_YAML.replace("rcon:\n", "rcon:\n  host: \"" + host + "\"\n")
                            .getBytes(StandardCharsets.UTF_8));
            ConfigFileReader.Result read = ConfigFileReader.read(configPath);
            loader.load(read.getState(), read.getMap());
            assertEquals(host, runtime.get(ConfigKeys.Rcon.HOST), "不应限制 host 的格式：" + host);
        }
    }

    @Test
    @DisplayName("rcon.host 为空白时按配置校验规则明确拒绝，且不污染原状态")
    void blankRconHostIsRejected(@TempDir Path tempDir) throws IOException {
        Path configPath = tempDir.resolve("config.yml");

        ConfigRegistry registry = new ConfigRegistry();
        ConfigKeys.registerAll(registry);
        Config runtime = new Config(registry);
        ConfigLoader loader = new ConfigLoader(registry, runtime);

        Files.write(configPath, CONFIG_YAML.getBytes(StandardCharsets.UTF_8));
        ConfigFileReader.Result valid = ConfigFileReader.read(configPath);
        loader.load(valid.getState(), valid.getMap());
        String before = runtime.describeEntries().toString();

        for (String blank : new String[] {"\"\"", "\"   \""}) {
            Files.write(
                    configPath,
                    CONFIG_YAML.replace("rcon:\n", "rcon:\n  host: " + blank + "\n")
                            .getBytes(StandardCharsets.UTF_8));
            ConfigFileReader.Result broken = ConfigFileReader.read(configPath);

            ConfigValidationException error = assertThrows(
                    ConfigValidationException.class, () -> loader.load(broken.getState(), broken.getMap()));
            assertTrue(
                    error.getMessage().contains("rcon.host"),
                    "错误信息应指明字段路径，实际=" + error.getMessage());
            assertEquals(before, runtime.describeEntries().toString(), "校验失败必须保持原状态");
        }
    }
}
