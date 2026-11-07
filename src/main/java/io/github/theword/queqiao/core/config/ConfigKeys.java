package io.github.theword.queqiao.core.config;

import io.github.theword.queqiao.core.config.codec.BooleanCodec;
import io.github.theword.queqiao.core.config.codec.IntegerCodec;
import io.github.theword.queqiao.core.config.codec.ListCodec;
import io.github.theword.queqiao.core.config.codec.StringCodec;
import io.github.theword.queqiao.core.config.schema.ConfigKey;
import io.github.theword.queqiao.core.config.schema.ConfigRegistry;
import io.github.theword.queqiao.core.config.schema.ConfigSectionNode;
import io.github.theword.queqiao.core.config.validation.ConfigValidators;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 核心配置项声明
 *
 * <p>这里是全部配置的<b>唯一来源</b>：路径、类型、默认值、校验、注释都在此声明。
 *
 * <p><b>路径与默认值必须与既有 {@code config.yml} 完全兼容</b>：
 * 不重命名字段、不改变嵌套结构、不改变默认值。
 *
 * @since 0.6.12
 */
public final class ConfigKeys {

    private ConfigKeys() {
    }

    // ------------------------------------------------------------------
    // 顶层
    // ------------------------------------------------------------------

    public static final ConfigKey<Boolean> ENABLE = ConfigKey.builder("enable", BooleanCodec.INSTANCE)
            .defaultValue(true)
            .comment("是否启用插件/模组")
            .build();

    public static final ConfigKey<Boolean> DEBUG = ConfigKey.builder("debug", BooleanCodec.INSTANCE)
            .defaultValue(false)
            .comment("DEBUG，开启后会打印所有日志")
            .build();

    public static final ConfigKey<String> SERVER_NAME = ConfigKey.builder("server_name", StringCodec.INSTANCE)
            .defaultValue("Server")
            .comment("服务器名称，当有多个服务器时，请使用不同的命名")
            .build();

    public static final ConfigKey<String> ACCESS_TOKEN =
            ConfigKey.builder("access_token", StringCodec.INSTANCE)
                    .defaultValue("")
                    .comment(
                            "访问令牌，用于连接时进行验证",
                            "安全提示：若把 websocket_server.host 改为非回环地址而此处留空，",
                            "任何能访问该端口的人都可发送消息并执行 Rcon 命令。",
                            "浏览器无法在 WebSocket 握手中设置自定义请求头，只能通过 URL query 传递；",
                            "而 URL 会进入反向代理日志，因此能用请求头的客户端请优先使用请求头。")
                    .build();

    public static final ConfigKey<String> MESSAGE_PREFIX =
            ConfigKey.builder("message_prefix", StringCodec.INSTANCE)
                    .defaultValue("[鹊桥]")
                    .comment("消息前缀（不包含 Title、ActionBar）", "设置为空时不会在消息前面添加前缀")
                    .build();

    public static final ConfigKey<Boolean> ENABLE_TRANSLATION =
            ConfigKey.builder("enable_translation", BooleanCodec.INSTANCE)
                    .defaultValue(false)
                    .comment("是否启用消息翻译功能（需要在 config.yml 同级目录下创建 translate 文件夹）")
                    .build();

    public static final ConfigKey<List<String>> IGNORED_COMMANDS =
            ConfigKey.builder("ignored_commands", ListCodec.INSTANCE)
                    .defaultValueSupplier(ArrayList::new)
                    .comment("忽略的命令列表", "例如 [\"tp\"]，则所有以 /tp 起始的命令对应的事件均不会被广播")
                    .build();

    /**
     * 强制忽略的命令
     *
     * <p>注册与登录命令<b>不允许</b>被用户配置放开——避免玩家凭据进入事件流。
     * 它们与用户配置<b>取并集</b>，因此不是 {@link #IGNORED_COMMANDS} 的默认值
     * （默认值仍为空列表，与既有模板一致）。
     */
    public static final Set<String> MANDATORY_IGNORED_COMMANDS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("l", "login", "register", "reg")));

    /**
     * 生效的忽略命令集合 = 强制项 ∪ 用户配置
     *
     * <p>这是既有行为的等价实现：此前 {@code Config.loadIgnoredCommands} 会把用户列表与
     * 内置强制项合并。规则集中在这里，消费方（如 {@code RuntimeUtils}）直接使用本方法。
     *
     * @param runtime 运行时值存储
     * @return 生效集合
     */
    public static Set<String> effectiveIgnoredCommands(Config runtime) {
        Set<String> result = new LinkedHashSet<>(MANDATORY_IGNORED_COMMANDS);
        result.addAll(runtime.get(IGNORED_COMMANDS));
        return result;
    }

    // ------------------------------------------------------------------
    // websocket_server
    // ------------------------------------------------------------------

    /**
     * WebSocket Server（{@code websocket_server}）
     */
    public static final class WebSocket {

        private WebSocket() {
        }

        public static final ConfigKey<Boolean> ENABLE =
                ConfigKey.builder("websocket_server.enable", BooleanCodec.INSTANCE)
                        .defaultValue(true)
                        .comment("是否启用")
                        .build();

        public static final ConfigKey<String> HOST =
                ConfigKey.builder("websocket_server.host", StringCodec.INSTANCE)
                        .defaultValue("127.0.0.1")
                        .comment(
                                "WebSocket Server 地址",
                                "默认仅本机可访问；改为 0.0.0.0 等非回环地址前，请先设置 access_token")
                        .build();

        public static final ConfigKey<Integer> PORT =
                ConfigKey.builder("websocket_server.port", IntegerCodec.INSTANCE)
                        .defaultValue(8080)
                        .validator(ConfigValidators.range(1, 65535))
                        .comment("WebSocket Server 端口（取值范围 1~65535，越界将回退默认值）")
                        .build();
    }

    // ------------------------------------------------------------------
    // websocket_client
    // ------------------------------------------------------------------

    /**
     * WebSocket Client（{@code websocket_client}）
     */
    public static final class WebSocketClient {

        private WebSocketClient() {
        }

        public static final ConfigKey<Boolean> ENABLE =
                ConfigKey.builder("websocket_client.enable", BooleanCodec.INSTANCE)
                        .defaultValue(false)
                        .comment("是否启用")
                        .build();

        public static final ConfigKey<Integer> RECONNECT_INTERVAL =
                ConfigKey.builder("websocket_client.reconnect_interval", IntegerCodec.INSTANCE)
                        .defaultValue(5)
                        .validator(ConfigValidators.range(1, 3600))
                        .comment("重连间隔（秒）（取值范围 1~3600；为 0 会导致立即重连风暴，将回退默认值）")
                        .build();

        public static final ConfigKey<Integer> RECONNECT_MAX_TIMES =
                ConfigKey.builder("websocket_client.reconnect_max_times", IntegerCodec.INSTANCE)
                        .defaultValue(5)
                        .validator(ConfigValidators.range(0, 1000))
                        .comment("最大重连次数（取值范围 0~1000；0 表示禁用自动重连）")
                        .build();

        public static final ConfigKey<List<String>> URL_LIST =
                ConfigKey.builder("websocket_client.url_list", ListCodec.INSTANCE)
                        .defaultValueSupplier(() -> new ArrayList<>(
                                Collections.singletonList("ws://127.0.0.1:8080/minecraft/ws")))
                        .comment("WebSocket 连接地址列表")
                        .build();
    }

    // ------------------------------------------------------------------
    // rcon
    // ------------------------------------------------------------------

    /**
     * Rcon 客户端（{@code rcon}）
     */
    public static final class Rcon {

        private Rcon() {
        }

        public static final ConfigKey<Boolean> ENABLE = ConfigKey.builder("rcon.enable", BooleanCodec.INSTANCE)
                .defaultValue(false)
                .comment("是否启用")
                .build();

        /**
         * Rcon 服务器地址
         *
         * <p>默认值刻意使用 {@code 127.0.0.1} 而不是 {@code localhost}：
         * 部分操作系统会把 {@code localhost} 优先解析为 IPv6 回环地址 {@code ::1}，
         * 而 Minecraft 的 RCON 通常只监听 IPv4 回环地址，导致连接被拒绝。
         *
         * <p>取值不做格式限制——主机名、IPv4、IPv6 字面地址与域名都应可用，
         * 是否可解析由建立连接时的系统解析器决定。
         */
        public static final ConfigKey<String> HOST =
                ConfigKey.builder("rcon.host", StringCodec.INSTANCE)
                        .defaultValue("127.0.0.1")
                        .validator(ConfigValidators.nonBlank())
                        .comment(
                                "Rcon 服务器地址（主机名 / IPv4 / IPv6 / 域名）",
                                "默认使用 IPv4 回环地址，避免 localhost 被解析为 ::1 而 RCON 只监听 IPv4")
                        .build();

        public static final ConfigKey<Integer> PORT = ConfigKey.builder("rcon.port", IntegerCodec.INSTANCE)
                .defaultValue(25575)
                .validator(ConfigValidators.range(1, 65535))
                .comment("Rcon 端口（取值范围 1~65535，越界将回退默认值）")
                .build();

        public static final ConfigKey<String> PASSWORD =
                ConfigKey.builder("rcon.password", StringCodec.INSTANCE)
                        .defaultValue("")
                        .comment("Rcon 密码")
                        .build();
    }

    /** 状态快照采集配置 */
    public static final class Status {

        private Status() {
        }

        public static final ConfigKey<Integer> REFRESH_INTERVAL_SECONDS =
                ConfigKey.builder("status.refresh_interval_seconds", IntegerCodec.INSTANCE)
                        .defaultValue(10)
                        .validator(ConfigValidators.atLeast(5))
                        .comment("状态快照后台采集间隔（秒，最小 5 秒；数值越大，状态更新越慢）")
                        .build();
    }

    // ------------------------------------------------------------------
    // subscribe_event
    // ------------------------------------------------------------------

    /**
     * 订阅事件（{@code subscribe_event}）
     */
    public static final class SubscribeEvent {

        private SubscribeEvent() {
        }

        public static final ConfigKey<Boolean> PLAYER_CHAT =
                ConfigKey.builder("subscribe_event.player_chat", BooleanCodec.INSTANCE)
                        .defaultValue(true)
                        .comment("玩家聊天事件监听")
                        .build();

        public static final ConfigKey<Boolean> PLAYER_DEATH =
                ConfigKey.builder("subscribe_event.player_death", BooleanCodec.INSTANCE)
                        .defaultValue(true)
                        .comment("玩家死亡事件监听")
                        .build();

        public static final ConfigKey<Boolean> PLAYER_JOIN =
                ConfigKey.builder("subscribe_event.player_join", BooleanCodec.INSTANCE)
                        .defaultValue(true)
                        .comment("玩家登录事件监听")
                        .build();

        public static final ConfigKey<Boolean> PLAYER_QUIT =
                ConfigKey.builder("subscribe_event.player_quit", BooleanCodec.INSTANCE)
                        .defaultValue(true)
                        .comment("玩家退出事件监听")
                        .build();

        public static final ConfigKey<Boolean> PLAYER_COMMAND =
                ConfigKey.builder("subscribe_event.player_command", BooleanCodec.INSTANCE)
                        .defaultValue(true)
                        .comment("玩家命令事件监听")
                        .build();

        public static final ConfigKey<Boolean> PLAYER_ADVANCEMENT =
                ConfigKey.builder("subscribe_event.player_advancement", BooleanCodec.INSTANCE)
                        .defaultValue(true)
                        .comment("玩家成就事件监听")
                        .build();
    }

    /**
     * 全部核心配置项（按 YAML 中的自然顺序）
     *
     * @return 配置项列表
     */
    public static List<ConfigKey<?>> all() {
        return Collections.unmodifiableList(Arrays.<ConfigKey<?>>asList(
                ENABLE,
                DEBUG,
                SERVER_NAME,
                ACCESS_TOKEN,
                MESSAGE_PREFIX,
                ENABLE_TRANSLATION,
                WebSocket.ENABLE,
                WebSocket.HOST,
                WebSocket.PORT,
                WebSocketClient.ENABLE,
                WebSocketClient.RECONNECT_INTERVAL,
                WebSocketClient.RECONNECT_MAX_TIMES,
                WebSocketClient.URL_LIST,
                Rcon.ENABLE,
                Rcon.HOST,
                Rcon.PORT,
                Rcon.PASSWORD,
                Status.REFRESH_INTERVAL_SECONDS,
                SubscribeEvent.PLAYER_CHAT,
                SubscribeEvent.PLAYER_DEATH,
                SubscribeEvent.PLAYER_JOIN,
                SubscribeEvent.PLAYER_QUIT,
                SubscribeEvent.PLAYER_COMMAND,
                SubscribeEvent.PLAYER_ADVANCEMENT,
                IGNORED_COMMANDS));
    }

    /**
     * 区块的注释与排版（供 Writer 生成带注释的 YAML）
     *
     * <p>注意：{@code ignored_commands} 是配置项而非区块，它的注释声明在 {@link #IGNORED_COMMANDS} 上。
     *
     * @return 区块列表
     */
    public static List<ConfigSectionNode> sections() {
        return Collections.unmodifiableList(Arrays.asList(
                ConfigSectionNode.of("websocket_server").comment("WebSocket Server配置项").blankLinesBefore(1),
                ConfigSectionNode.of("websocket_client").comment("WebSocket Client配置项").blankLinesBefore(1),
                ConfigSectionNode.of("rcon").comment("Rcon 客户端配置项").blankLinesBefore(1),
                ConfigSectionNode.of("status").comment("状态采集配置项").blankLinesBefore(1),
                ConfigSectionNode.of("subscribe_event").comment("订阅事件配置项").blankLinesBefore(1)));
    }

    /**
     * 把全部核心配置项与区块注册到 Registry
     *
     * @param registry 注册中心
     */
    public static void registerAll(ConfigRegistry registry) {
        for (ConfigKey<?> key : all()) {
            registry.register(key);
        }
        for (ConfigSectionNode section : sections()) {
            registry.register(section);
        }
    }
}
