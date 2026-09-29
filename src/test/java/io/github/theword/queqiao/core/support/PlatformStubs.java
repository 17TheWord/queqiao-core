package io.github.theword.queqiao.core.support;

import io.github.theword.queqiao.core.api.Api;
import io.github.theword.queqiao.core.api.DefaultApis;
import io.github.theword.queqiao.core.config.Config;
import io.github.theword.queqiao.core.config.ConfigKeys;
import io.github.theword.queqiao.core.config.schema.ConfigRegistry;
import io.github.theword.queqiao.core.constant.ServerTypeConstant;
import io.github.theword.queqiao.core.exception.rcon.RconException;
import io.github.theword.queqiao.core.handle.HandleProtocolMessage;
import io.github.theword.queqiao.core.platform.AbstractPlatformContext;
import io.github.theword.queqiao.core.platform.PlatformResult;
import io.github.theword.queqiao.core.platform.PlatformResultCode;
import io.github.theword.queqiao.core.platform.TestCommandSource;
import io.github.theword.queqiao.core.platform.TestComponent;
import io.github.theword.queqiao.core.platform.TestPlayer;
import io.github.theword.queqiao.core.platform.TestServer;
import io.github.theword.queqiao.core.protocol.RconCommandExecutor;
import io.github.theword.queqiao.core.protocol.handler.status.ServerStatusCollector;
import io.github.theword.queqiao.core.utils.RuntimeUtils;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 协议层与平台层的测试替身
 *
 * <p>协议层通过构造器接收"Api 集合"与"平台上下文"，
 * 因此测试可以注入替身来验证<b>成功路径</b>——
 * 这在依赖静态全局状态的时期是做不到的（平台实现为 null，只能落到 500）。
 */
public final class PlatformStubs {

    private PlatformStubs() {
    }

    /**
     * 不做任何事、不记录任何内容的平台上下文
     */
    public static AbstractPlatformContext<?, ?, ?, ?> noopPlatformContext() {
        return new RecordingPlatformContext();
    }

    /**
     * 记录所有调用的平台上下文
     */
    public static RecordingPlatformContext recordingPlatformContext() {
        return new RecordingPlatformContext();
    }

    /**
     * 不做任何事的 RCON 执行器，返回固定结果
     *
     * @param result 返回值
     */
    public static RconCommandExecutor rconExecutorReturning(String result) {
        return command -> result;
    }

    /**
     * 总是抛出指定类型 RconException 的执行器
     *
     * @param kind 失败类型
     */
    public static RconCommandExecutor rconExecutorFailing(RconException.Kind kind) {
        return command -> {
            switch (kind) {
                case DISABLED:
                    throw RconException.disabled();
                case DISCONNECTED:
                    throw RconException.disconnected();
                case INVALID_COMMAND:
                    throw RconException.invalidCommand();
                case COMMAND_FAILED:
                default:
                    throw RconException.commandFailed(new IllegalStateException("stub failure"));
            }
        };
    }

    /**
     * 构造一份不触碰文件系统的默认配置（默认值只来自 {@link ConfigKeys} 的 Schema）
     */
    public static Config defaultConfig() {
        ConfigRegistry registry = new ConfigRegistry();
        ConfigKeys.registerAll(registry);
        return new Config(registry);
    }

    /**
     * 构造 Runtime 作用域辅助能力（默认配置 + 给定日志实现）
     */
    public static RuntimeUtils newRuntimeUtils(Logger logger) {
        return new RuntimeUtils(defaultConfig(), logger);
    }

    /**
     * 构造未启动的状态采集器（未调用 startRefreshScheduler，走同步采集回退分支）
     */
    public static ServerStatusCollector newStatusCollector(Logger logger) {
        return new ServerStatusCollector(null, null, logger);
    }

    /**
     * 构造协议分发入口（使用空平台上下文与空 RCON 执行器，铺默认 Api 批次）
     */
    public static HandleProtocolMessage newDispatcher(Logger logger, Gson gson) {
        return newDispatcher(logger, gson, noopPlatformContext(), rconExecutorReturning(""));
    }

    /**
     * 构造协议分发入口（指定平台上下文与 RCON 执行器，铺默认 Api 批次）
     */
    public static HandleProtocolMessage newDispatcher(
            Logger logger, Gson gson,
            AbstractPlatformContext<?, ?, ?, ?> platformContext,
            RconCommandExecutor rconCommandExecutor) {
        ServerStatusCollector statusCollector = newStatusCollector(logger);
        List<Api<?, ?>> apis = DefaultApis.all(platformContext, statusCollector, rconCommandExecutor, logger);
        return new HandleProtocolMessage(logger, gson, apis, newRuntimeUtils(logger));
    }

    /**
     * 记录调用的平台上下文
     *
     * <p>记录发生在<b>平台原语层</b>（{@code broadcast(C)} / {@code sendMessage(P, C)} 等），
     * 因此它验证的是"请求确实穿透到了平台边界"，而不是某一层中间状态。
     *
     * <p>默认在线玩家包含 {@code Player1} / {@code Player2} / {@code Steve}，
     * 以便私聊成功路径能被验证（{@code findPlayer} 依赖 {@code getPlayers()}）。
     */
    public static final class RecordingPlatformContext
            extends AbstractPlatformContext<TestServer, TestComponent, TestPlayer, TestCommandSource> {

        private static final String DEFAULT_SERVER_TYPE = ServerTypeConstant.SPIGOT;
        private static final String DEFAULT_SERVER_VERSION = "1.20.1";

        private final List<String> broadcasts = Collections.synchronizedList(new ArrayList<>());
        private final List<String> actionBars = Collections.synchronizedList(new ArrayList<>());
        private final List<String> titleCalls = Collections.synchronizedList(new ArrayList<>());
        private final List<String> privateMessages = Collections.synchronizedList(new ArrayList<>());
        private final List<String> returnMessages = Collections.synchronizedList(new ArrayList<>());
        private final List<TestPlayer> players = Collections.synchronizedList(new ArrayList<>());

        private volatile boolean permissionGranted = true;

        /**
         * 平台是否支持标题；false 时 {@code sendTitleComponent} 交回基类默认实现（UNSUPPORTED）
         */
        private volatile boolean titleSupported = true;

        /**
         * 平台是否支持 ActionBar；false 时 {@code sendActionBarComponent} 交回基类默认实现（UNSUPPORTED）
         */
        private volatile boolean actionBarSupported = true;

        /**
         * 强制 broadcast 失败的结果码；null 表示不强制失败
         */
        private volatile PlatformResultCode broadcastFailure;

        /**
         * 强制 sendMessage 失败的结果码；null 表示不强制失败
         */
        private volatile PlatformResultCode sendFailure;

        public RecordingPlatformContext() {
            super(new TestServer());
            players.add(new TestPlayer("Player1", UUID.randomUUID()));
            players.add(new TestPlayer("Player2", UUID.randomUUID()));
            players.add(new TestPlayer("Steve", UUID.randomUUID()));
        }

        // ---- 平台元数据 ----

        @Override
        public String getServerType() {
            return DEFAULT_SERVER_TYPE;
        }

        @Override
        public String getServerVersion() {
            return DEFAULT_SERVER_VERSION;
        }

        // ---- 必需原语 ----

        @Override
        public TestComponent jsonToComponent(JsonElement jsonElement) {
            return new TestComponent(jsonElement);
        }

        @Override
        public Collection<TestPlayer> getPlayers() {
            synchronized (players) {
                return new ArrayList<>(players);
            }
        }

        @Override
        public String getPlayerName(TestPlayer player) {
            return player.getName();
        }

        @Override
        public UUID getPlayerUUID(TestPlayer player) {
            return player.getUuid();
        }

        @Override
        public PlatformResult<String> broadcast(TestComponent component) {
            if (broadcastFailure != null) {
                return PlatformResult.failure(broadcastFailure, "forced broadcast failure");
            }
            broadcasts.add(component.getJson());
            return PlatformResult.success(component.getJson());
        }

        @Override
        public PlatformResult<Void> sendPrivateMessage(TestPlayer player, TestComponent component) {
            if (sendFailure != null) {
                return PlatformResult.failure(sendFailure, "forced send failure");
            }
            privateMessages.add("nickname=" + player.getName()
                    + ", uuid=" + player.getUuid()
                    + ", message=" + component.getJson());
            return PlatformResult.success(null);
        }

        @Override
        public boolean doCheckPermission(TestCommandSource source, String permission) {
            return permissionGranted;
        }

        @Override
        public void returnCallBackMessage(TestCommandSource source, TestComponent component) {
            returnMessages.add(component.getJson());
        }

        // ---- 可选原语：测试桩全部支持，以便验证成功路径 ----

        @Override
        public PlatformResult<Void> sendTitleComponent(TestComponent title, TestComponent subtitle,
                                                       int fadeIn, int stay, int fadeOut) {
            if (!titleSupported) {
                // 交回基类默认实现 → PlatformResultCode.UNSUPPORTED，用于验证 503 映射
                return super.sendTitleComponent(title, subtitle, fadeIn, stay, fadeOut);
            }
            titleCalls.add("title=" + jsonOf(title)
                    + ", subtitle=" + jsonOf(subtitle)
                    + ", fadeIn=" + fadeIn + ", stay=" + stay + ", fadeOut=" + fadeOut);
            return PlatformResult.success(null);
        }

        @Override
        public PlatformResult<Void> sendActionBarComponent(TestComponent component) {
            if (!actionBarSupported) {
                // 交回基类默认实现 → PlatformResultCode.UNSUPPORTED，用于验证 503 映射
                return super.sendActionBarComponent(component);
            }
            actionBars.add(component.getJson());
            return PlatformResult.success(null);
        }

        private static String jsonOf(TestComponent component) {
            return component == null ? null : component.getJson();
        }

        // ---- 测试辅助 ----

        /**
         * 追加一个在线玩家（例如为私聊用例准备目标玩家）
         */
        public void addPlayer(String name, UUID uuid) {
            players.add(new TestPlayer(name, uuid));
        }

        /**
         * 设置权限检查结果
         */
        public void setPermissionGranted(boolean granted) {
            this.permissionGranted = granted;
        }

        /**
         * 设置平台是否支持标题（false → UNSUPPORTED → 503）
         */
        public void setTitleSupported(boolean supported) {
            this.titleSupported = supported;
        }

        /**
         * 设置平台是否支持 ActionBar（false → UNSUPPORTED → 503）
         */
        public void setActionBarSupported(boolean supported) {
            this.actionBarSupported = supported;
        }

        /**
         * 强制 broadcast 返回指定失败码；传 null 恢复成功
         */
        public void failBroadcastWith(PlatformResultCode code) {
            this.broadcastFailure = code;
        }

        /**
         * 强制 sendMessage 返回指定失败码；传 null 恢复成功
         *
         * <p>由于 {@code sendPrivateMessage} 在基类中是 final 且会透传发送结果，
         * 本开关可以间接驱动私聊的失败路径。
         */
        public void failSendWith(PlatformResultCode code) {
            this.sendFailure = code;
        }

        public List<String> getBroadcasts() {
            synchronized (broadcasts) {
                return new ArrayList<>(broadcasts);
            }
        }

        public List<String> getActionBars() {
            synchronized (actionBars) {
                return new ArrayList<>(actionBars);
            }
        }

        public List<String> getTitleCalls() {
            synchronized (titleCalls) {
                return new ArrayList<>(titleCalls);
            }
        }

        public List<String> getPrivateMessages() {
            synchronized (privateMessages) {
                return new ArrayList<>(privateMessages);
            }
        }

        public List<String> getReturnMessages() {
            synchronized (returnMessages) {
                return new ArrayList<>(returnMessages);
            }
        }

        /**
         * 清空全部录制内容（便于用例之间复用同一个上下文实例）
         */
        public void clearRecordings() {
            broadcasts.clear();
            actionBars.clear();
            titleCalls.clear();
            privateMessages.clear();
            returnMessages.clear();
        }
    }
}
