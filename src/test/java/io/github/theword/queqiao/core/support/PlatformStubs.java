package io.github.theword.queqiao.core.support;

import io.github.theword.queqiao.core.api.ApiRegistry;
import io.github.theword.queqiao.core.api.platform.BroadcastService;
import io.github.theword.queqiao.core.api.standard.BroadcastApi;
import io.github.theword.queqiao.core.config.Config;
import io.github.theword.queqiao.core.config.ConfigKeys;
import io.github.theword.queqiao.core.config.schema.ConfigRegistry;
import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.exception.rcon.RconException;
import io.github.theword.queqiao.core.handle.HandleApiService;
import io.github.theword.queqiao.core.handle.HandleProtocolMessage;
import io.github.theword.queqiao.core.protocol.RconCommandExecutor;
import io.github.theword.queqiao.core.protocol.handler.status.ServerStatusCollector;
import io.github.theword.queqiao.core.response.PrivateMessageResponse;
import io.github.theword.queqiao.core.utils.RuntimeUtils;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 协议层测试替身
 *
 * <p>协议层通过构造器接收"平台 API 实现"与"RCON 执行器"，
 * 因此测试可以注入替身来验证<b>成功路径</b>——
 * 这在依赖静态全局状态的时期是做不到的（平台实现为 null，只能落到 500）。
 */
public final class PlatformStubs {

    private PlatformStubs() {
    }

    /**
     * 不做任何事、返回 null 的平台 API 实现
     */
    public static HandleApiService noopApiService() {
        return new RecordingApiService();
    }

    /**
     * 记录所有调用的平台 API 实现
     */
    public static RecordingApiService recordingApiService() {
        return new RecordingApiService();
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
     * 构造一个已注册 {@code broadcast} / {@code send_msg} 的注册中心
     *
     * <p>Core 默认<b>不再</b>注册广播类 API（由平台按自身能力决定），
     * 因此以广播当载体验证"传输 / 分发链路"的用例需要自己注册。
     * 两个名称指向<b>同一个</b> {@link BroadcastService} 实例。
     *
     * @param service 平台广播能力
     * @return 已注册两个广播名称的注册中心
     */
    public static ApiRegistry registryWithBroadcast(BroadcastService service) {
        ApiRegistry registry = new ApiRegistry();
        registry.register(new BroadcastApi(ProtocolConstants.Api.BROADCAST, service));
        registry.register(new BroadcastApi(ProtocolConstants.Api.SEND_MSG, service));
        return registry;
    }

    /**
     * 记录被广播出去的消息的 {@link BroadcastService}
     */
    public static final class RecordingBroadcastService implements BroadcastService {

        private final List<String> broadcasts = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void broadcast(JsonElement message) {
            broadcasts.add(String.valueOf(message));
        }

        public int getCount() {
            synchronized (broadcasts) {
                return broadcasts.size();
            }
        }

        public List<String> getBroadcasts() {
            synchronized (broadcasts) {
                return new ArrayList<>(broadcasts);
            }
        }
    }

    /**
     * 构造协议分发入口（使用空平台实现与空 RCON 执行器）
     */
    public static HandleProtocolMessage newDispatcher(Logger logger, Gson gson) {
        return newDispatcher(logger, gson, noopApiService(), rconExecutorReturning(""));
    }

    /**
     * 构造协议分发入口（指定平台实现与 RCON 执行器）
     */
    public static HandleProtocolMessage newDispatcher(
            Logger logger, Gson gson, HandleApiService apiService, RconCommandExecutor rconCommandExecutor) {
        return newDispatcher(logger, gson, apiService, rconCommandExecutor, new ApiRegistry());
    }

    /**
     * 构造协议分发入口（使用调用方提供的 ApiRegistry）
     *
     * <p>协议层会把 Core 默认 API 注册进传入的注册中心。需要额外注册自定义 API
     * （例如 {@code PrivateMessageApi} 或测试用的 EchoApi）的用例，
     * 先用本重载拿到同一个注册中心引用，再继续注册即可。
     */
    public static HandleProtocolMessage newDispatcher(
            Logger logger,
            Gson gson,
            HandleApiService apiService,
            RconCommandExecutor rconCommandExecutor,
            ApiRegistry apiRegistry) {
        return new HandleProtocolMessage(
                logger, gson, apiService, rconCommandExecutor, newRuntimeUtils(logger),
                newStatusCollector(logger), apiRegistry);
    }

    /**
     * 记录调用的平台 API 实现
     */
    public static final class RecordingApiService implements HandleApiService {

        private final List<String> broadcasts = Collections.synchronizedList(new ArrayList<>());
        private final List<String> actionBars = Collections.synchronizedList(new ArrayList<>());
        private final List<String> titleCalls = Collections.synchronizedList(new ArrayList<>());
        private final List<String> privateMessages = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void handleBroadcastMessage(JsonElement jsonData) {
            broadcasts.add(String.valueOf(jsonData));
        }

        @Override
        public void handleSendTitleMessage(JsonElement titlePayload, JsonElement subTitlePayload, int fadeIn, int stay, int fadeOut) {
            titleCalls.add("title=" + titlePayload + ", subtitle=" + subTitlePayload
                    + ", fadeIn=" + fadeIn + ", stay=" + stay + ", fadeOut=" + fadeOut);
        }

        @Override
        public void handleSendActionBarMessage(JsonElement jsonData) {
            actionBars.add(String.valueOf(jsonData));
        }

        @Override
        public PrivateMessageResponse handleSendPrivateMessage(String nickname, UUID uuid, JsonElement jsonData) {
            privateMessages.add("nickname=" + nickname + ", uuid=" + uuid + ", message=" + jsonData);
            return null;
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
    }
}
