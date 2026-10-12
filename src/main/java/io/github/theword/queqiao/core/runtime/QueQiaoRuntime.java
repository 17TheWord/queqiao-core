package io.github.theword.queqiao.core.runtime;

import io.github.theword.queqiao.core.config.io.ConfigFileReader;
import io.github.theword.queqiao.core.config.io.ConfigFileState;
import io.github.theword.queqiao.core.config.ConfigKeys;
import io.github.theword.queqiao.core.config.schema.ConfigRegistry;
import io.github.theword.queqiao.core.config.Config;
import io.github.theword.queqiao.core.config.ConfigSnapshot;
import io.github.theword.queqiao.core.config.io.ConfigDocument;
import io.github.theword.queqiao.core.config.io.ConfigLoadResult;
import io.github.theword.queqiao.core.config.io.ConfigLoader;
import io.github.theword.queqiao.core.config.io.ConfigStore;
import io.github.theword.queqiao.core.config.io.ConfigWriteSnapshot;
import io.github.theword.queqiao.core.config.io.ConfigWriter;
import io.github.theword.queqiao.core.api.Api;
import io.github.theword.queqiao.core.api.DefaultApis;
import io.github.theword.queqiao.core.command.CommandRouter;
import io.github.theword.queqiao.core.command.RootCommand;
import io.github.theword.queqiao.core.constant.BaseConstant;
import io.github.theword.queqiao.core.constant.CommandConstant;
import io.github.theword.queqiao.core.constant.WebsocketConstantMessage;
import io.github.theword.queqiao.core.event.base.BaseEvent;
import io.github.theword.queqiao.core.exception.rcon.RconException;
import io.github.theword.queqiao.core.extension.QueQiaoExtension;
import io.github.theword.queqiao.core.extension.QueQiaoExtensionDiscovery;
import io.github.theword.queqiao.core.handle.HandleProtocolMessage;
import io.github.theword.queqiao.core.platform.AbstractPlatformContext;
import io.github.theword.queqiao.core.protocol.handler.status.ServerStatusCollector;
import io.github.theword.queqiao.core.rcon.RconClient;
import io.github.theword.queqiao.core.utils.GsonUtils;
import io.github.theword.queqiao.core.utils.RuntimeUtils;
import io.github.theword.queqiao.core.utils.WebsocketManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 鹊桥运行时：装配并托管一次运行所需的全部 Core 组件。
 *
 * <p>平台只需提供 {@link AbstractPlatformContext}（以及可选的扩展来源），
 * Config、WebSocket、RCON、命令路由、协议分发与翻译调度都由本类装配，
 * 平台不必了解 Core 的内部对象图。
 *
 * <p>生命周期单向：{@code NEW → STARTING → RUNNING → STOPPING → STOPPED}，
 * 启动失败进入 {@code FAILED}，不支持重启。
 */
public final class QueQiaoRuntime {

    // ------------------------------------------------------------------
    // 生命周期状态
    // ------------------------------------------------------------------

    /**
     * 生命周期状态，迁移单向，不支持重启。
     */
    private final AtomicReference<RuntimeState> state = new AtomicReference<>(RuntimeState.NEW);

    /**
     * 串行化 {@code start()} / {@code reload()} / {@code shutdown()} 的生命周期锁。
     * 它不替代 Config / WebSocket / RCON 各自的同步机制。
     */
    private final Object lifecycleLock = new Object();

    // ------------------------------------------------------------------
    // 构造后不再变化的字段：用 final 保证安全发布
    // ------------------------------------------------------------------

    /**
     * Runtime 作用域辅助能力，随 Runtime 创建一次后不再替换。
     */
    public final RuntimeUtils utils;

    private final Gson gson;

    /**
     * 协议分发入口，与传输方式无关；构造后不可变，可跨连接与线程共享。
     */
    private final HandleProtocolMessage handleProtocolMessage;

    /**
     * 服务器状态采集器。实例级持有，不与其他 Runtime 共享状态；生命周期由 Runtime
     * 管理（start 启动、reload 刷新、shutdown 停止）。
     */
    private final ServerStatusCollector serverStatusCollector;

    // ------------------------------------------------------------------
    // 会随 start / reload / shutdown 变化的字段
    //
    // 标记为 volatile 只保证单字段可见性；并发 reload 期间可能瞬时读到新旧混合的值。
    // ------------------------------------------------------------------

    /**
     * 配置 Schema（整个 Runtime 只有一份）
     */
    private final ConfigRegistry configRegistry;

    /**
     * 配置运行时状态（<b>唯一</b>配置状态来源）
     */
    private volatile Config config;

    /**
     * 当前配置文档，保留未知字段与原始结构，供 Writer / Checker / Synchronizer 使用。
     */
    private volatile ConfigDocument configDocument;

    /**
     * 日志实现。非 final 以支持 {@link #setLogger(Logger)}；已在构造时捕获 logger 的
     * 协作者不受运行期替换影响。
     */
    private volatile Logger logger;

    /**
     * 平台上下文，构造时注入，之后不再变化。
     */
    public final AbstractPlatformContext<?, ?, ?> platformContext;

    private volatile WebsocketManager websocketManager;

    /** 串行化 Runtime 对 RCON 客户端的创建、替换和关闭。 */
    private final Object rconLifecycleLock = new Object();

    private volatile RconClient rconClient;

    private volatile JsonElement messagePrefixJsonElement;

    private QueQiaoRuntime(
            Logger logger,
            AbstractPlatformContext<?, ?, ?> platformContext,
            Consumer<List<Api<?, ?>>> apiConfigurer,
            Collection<? extends QueQiaoExtension> extensions
    ) {
        this.logger = logger;
        this.gson = GsonUtils.getGson();
        this.platformContext = platformContext;
        // 配置系统接线：Schema 只注册一次，Config 只建一份，全局唯一配置状态
        this.configRegistry = new ConfigRegistry();
        ConfigKeys.registerAll(this.configRegistry);
        this.config = new Config(this.configRegistry);
        this.configDocument = ConfigDocument.empty();
        // Runtime 作用域辅助能力：只依赖 Config 与 Logger，不反向持有 Runtime
        this.utils = new RuntimeUtils(this.config, this.logger);
        // 状态采集器为实例级：先于协议层创建，再注入给协议分发链。
        // 平台类型与版本以 Supplier 形式传入，采集快照时才现取——这样平台可以先 create()
        // 再等服务器启动完成，不必在构造瞬间就拿到版本。
        this.serverStatusCollector = new ServerStatusCollector(
                platformContext::getServerType, platformContext::getServerVersion, logger);
        // 协议层接线（固定顺序，全部发生在 ProtocolRouter 构造之前）：
        //   1. 铺默认批次   2. 交给使用方自由增删（自愿注册）   3. 安装 Extension 贡献的 API
        // 三者写入的是<b>同一份</b>列表，因此 Extension 与内置 API 共享同一张路由表，
        // 不存在第二套路由，也不会因为"来源不同"而产生行为差异。
        // 这里传入 this::sendRconCommand 是安全的：该 lambda 只在收到请求时才会被调用，
        // 此时对象早已构造完成（构造期间不会被发布）。
        List<Api<?, ?>> apis = new ArrayList<>(
                DefaultApis.all(platformContext, this.serverStatusCollector, this::sendRconCommand, logger));
        if (apiConfigurer != null) {
            apiConfigurer.accept(apis);
        }
        installExtensions(extensions, apis);
        this.handleProtocolMessage = new HandleProtocolMessage(logger, this.gson, apis, this.utils);
    }

    /**
     * 安装 Extension：把每个扩展贡献的 API 加入同一份最终 API 集合
     *
     * <p>注册阶段结束时<b>无条件 seal</b>（含异常路径）：扩展即使保存了 context 引用，
     * 也无法在装配结束后再向路由表追加 API，从而保住"路由表构造后不可变"这一不变量。
     *
     * <p><b>fail-fast</b>：集合含 null 元素、或任一扩展的 register 抛异常时，
     * 直接让 Runtime 构造失败。不采用"记日志后继续启动"——因为此时已可能有部分 API
     * 被注册，继续启动会得到一个状态不明确的 Runtime。
     *
     * @param extensions 平台宿主提供的扩展集合，不得为 null；元素不得为 null
     * @param apis       Runtime 正在装配的 API 列表
     */
    private static void installExtensions(
            Collection<? extends QueQiaoExtension> extensions, List<Api<?, ?>> apis) {
        RuntimeExtensionContext context = new RuntimeExtensionContext(apis);
        try {
            for (QueQiaoExtension extension : extensions) {
                if (extension == null) {
                    throw new NullPointerException("extensions 中不得包含 null 元素");
                }
                try {
                    extension.register(context);
                } catch (RuntimeException e) {
                    throw new IllegalStateException(
                            "Failed to register extension: " + extension.getClass().getName(), e);
                }
            }
        } finally {
            context.seal();
        }
    }

    /**
     * 创建运行时，不安装任何扩展。
     *
     * <p>等价于
     * {@code create(configurer, platformContext, apiConfigurer, Collections.emptyList())}。
     *
     * @param configurer      可选的启动期 Schema 注册回调
     * @param platformContext 平台上下文，不得为 null
     * @param apiConfigurer   可选的 Api 装配回调；收到的是已铺好默认批次的可变列表，可自由增删
     * @return 尚未启动的运行时
     */
    public static QueQiaoRuntime create(
            Consumer<ConfigRegistry> configurer,
            AbstractPlatformContext<?, ?, ?> platformContext,
            Consumer<List<Api<?, ?>>> apiConfigurer
    ) {
        return create(configurer, platformContext, apiConfigurer, Collections.emptyList());
    }

    /**
     * 创建运行时，并安装平台宿主提供的 {@link QueQiaoExtension}。
     *
     * <p>API 装配顺序：默认批次 → {@code apiConfigurer} → Extensions → seal；
     * 扩展与内置 API 进入同一份最终集合，共用同一套路由与 echo 机制。
     *
     * <p>fail-fast：{@code extensions} 为 null、含 null 元素，或任一扩展的
     * {@link QueQiaoExtension#register} 抛异常时直接失败，不会返回半装配的 Runtime。
     *
     * @param configurer      可选的启动期 Schema 注册回调
     * @param platformContext 平台上下文，不得为 null
     * @param apiConfigurer   可选的 Api 装配回调，可为 null
     * @param extensions      扩展集合，不得为 null；无扩展时传 {@code Collections.emptyList()}
     * @return 尚未启动的运行时
     */
    public static QueQiaoRuntime create(
            Consumer<ConfigRegistry> configurer,
            AbstractPlatformContext<?, ?, ?> platformContext,
            Consumer<List<Api<?, ?>>> apiConfigurer,
            Collection<? extends QueQiaoExtension> extensions
    ) {
        Objects.requireNonNull(
                platformContext, "platformContext 不能为 null：平台必须提供 AbstractPlatformContext 实现");
        Objects.requireNonNull(
                extensions, "extensions 不能为 null：无扩展时请传 Collections.emptyList()");
        Logger runtimeLogger = LoggerFactory.getLogger(BaseConstant.MODULE_NAME);
        QueQiaoRuntime runtime = new QueQiaoRuntime(
                runtimeLogger,
                platformContext,
                apiConfigurer,
                extensions
        );
        if (configurer != null) {
            configurer.accept(runtime.configRegistry);
        }
        return runtime;
    }

    // ------------------------------------------------------------------
    // 便捷启动 API
    //
    // 普通平台只需要"平台上下文"（以及可选的扩展来源）这两样东西。
    // 上面的完整 create(...) 仍然保留，供 Core 测试、需要自定义 configurer /
    // apiConfigurer 的高级 Host 使用。
    // ------------------------------------------------------------------

    /**
     * 创建运行时（不安装任何扩展）
     *
     * <p>等价于 {@code create(null, platformContext, null, Collections.emptyList())}。
     *
     * @param platformContext 平台上下文，不得为 null
     * @return 尚未启动的运行时
     */
    public static QueQiaoRuntime create(AbstractPlatformContext<?, ?, ?> platformContext) {
        return create(null, platformContext, null, Collections.emptyList());
    }

    /**
     * 创建运行时并安装给定的扩展集合
     *
     * @param platformContext 平台上下文，不得为 null
     * @param extensions      扩展集合，不得为 null；元素不得为 null
     * @return 尚未启动的运行时
     */
    public static QueQiaoRuntime create(
            AbstractPlatformContext<?, ?, ?> platformContext,
            Collection<? extends QueQiaoExtension> extensions
    ) {
        return create(null, platformContext, null, extensions);
    }

    /**
     * 创建运行时，扩展由平台的 {@link QueQiaoExtensionDiscovery} 发现。
     *
     * <p>Core 不规定发现方式，只接收结果。
     *
     * @param platformContext 平台上下文，不得为 null
     * @param discovery       扩展发现入口，不得为 null；其 {@code discover()} 不得返回 null
     * @return 尚未启动的运行时
     */
    public static QueQiaoRuntime create(
            AbstractPlatformContext<?, ?, ?> platformContext,
            QueQiaoExtensionDiscovery discovery
    ) {
        return create(platformContext, discoverExtensions(discovery));
    }

    /**
     * 创建并启动运行时，不安装任何扩展。
     *
     * <p>等价于 {@code create(platformContext)} + {@code runtime.start()}；启动失败沿用
     * {@link #start()} 的语义，抛异常且 Runtime 已完成自清理。
     *
     * @param platformContext 平台上下文，不得为 null
     * @return 已启动的运行时
     */
    public static QueQiaoRuntime start(AbstractPlatformContext<?, ?, ?> platformContext) {
        QueQiaoRuntime runtime = create(platformContext);
        runtime.start();
        return runtime;
    }

    /**
     * 创建并启动运行时，并安装给定的扩展集合
     *
     * @param platformContext 平台上下文，不得为 null
     * @param extensions      扩展集合，不得为 null；元素不得为 null
     * @return 已启动的运行时
     */
    public static QueQiaoRuntime start(
            AbstractPlatformContext<?, ?, ?> platformContext,
            Collection<? extends QueQiaoExtension> extensions
    ) {
        QueQiaoRuntime runtime = create(platformContext, extensions);
        runtime.start();
        return runtime;
    }

    /**
     * 创建并启动运行时，扩展由平台的 {@link QueQiaoExtensionDiscovery} 发现。
     *
     * <p>支持扩展的平台最常用这一条：
     * {@code runtime = QueQiaoRuntime.start(platform, FabricExtensionDiscovery::discover);}
     *
     * @param platformContext 平台上下文，不得为 null
     * @param discovery       扩展发现入口，不得为 null；其 {@code discover()} 不得返回 null
     * @return 已启动的运行时
     */
    public static QueQiaoRuntime start(
            AbstractPlatformContext<?, ?, ?> platformContext,
            QueQiaoExtensionDiscovery discovery
    ) {
        QueQiaoRuntime runtime = create(platformContext, discovery);
        runtime.start();
        return runtime;
    }

    /**
     * 调用发现入口并校验返回值。
     *
     * <p>{@code discover()} 返回 null 属于装配错误，不静默当成"没有扩展"。
     */
    private static Collection<? extends QueQiaoExtension> discoverExtensions(
            QueQiaoExtensionDiscovery discovery) {
        Objects.requireNonNull(discovery, "discovery");
        Collection<? extends QueQiaoExtension> discovered = discovery.discover();
        Objects.requireNonNull(
                discovered,
                "discovery.discover() 不得返回 null：没有扩展时请返回空集合");
        return discovered;
    }

    /**
     * 创建本 Runtime 的命令路由。
     *
     * <p>前置条件：Runtime 必须已 {@link #start()} 成功 —— {@code WebsocketManager}
     * 只在 start 之后存在。{@code NCS} 是方法级泛型，Runtime 自身不被参数化。
     *
     * @param <NCS> 平台原生命令来源类型
     * @return 命令路由
     */
    public <NCS> CommandRouter<NCS> createCommandRouter() {
        return new CommandRouter<>(logger, config, websocketManager, this::reload);
    }

    /**
     * 创建一棵<b>在 Runtime 启动之前</b>即可构建的完整命令树。
     *
     * <p>调用时 Runtime 可以尚不存在：{@code Config}、{@code WebsocketManager} 与 reload
     * 都通过 {@code runtimeSupplier} 延迟获取，只有命令真正执行时才取值。
     * 因此节点名称、子节点与权限元数据在 Runtime 未启动时也可读取，平台可以在注册命令时
     * 就把整棵树映射到自己的命令 API，无需自行解析路径或实现补全。
     *
     * <p>返回的树已冻结（结构不可再修改），Runtime 被替换后
     * 也无需重建命令树 —— 下一次执行会通过供应商取到新实例。
     *
     * <p>Runtime 未就绪（供应商返回 null，或状态不是 {@link RuntimeState#RUNNING}）时，
     * 依赖运行状态的命令回执 {@link CommandConstant#RUNTIME_NOT_READY} 并安全返回，不抛异常。
     *
     * @param logger          日志实现，不得为 null
     * @param runtimeSupplier 当前 Runtime 的供应商，不得为 null；尚未就绪时可返回 null
     * @param <NCS>           平台原生命令来源类型
     * @return 已冻结的完整根命令
     */
    public static <NCS> RootCommand<NCS> createDeferredCommandTree(
            Logger logger,
            Supplier<QueQiaoRuntime> runtimeSupplier) {
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(runtimeSupplier, "runtimeSupplier");

        RootCommand<NCS> root = new RootCommand<>(
                logger,
                () -> {
                    QueQiaoRuntime runtime = runtimeSupplier.get();
                    return runtime == null ? null : runtime.getConfig();
                },
                () -> {
                    QueQiaoRuntime runtime = runtimeSupplier.get();
                    return runtime == null ? null : runtime.getWebsocketManager();
                },
                () -> {
                    QueQiaoRuntime runtime = runtimeSupplier.get();
                    if (runtime == null || runtime.getState() != RuntimeState.RUNNING) {
                        return null;
                    }
                    return runtime.reload();
                });
        // 工厂返回的就是完整树：立即冻结，保证后续（含数据包重载导致的重复注册）
        // 只能读取结构，不可能修改它。
        root.freeze();
        return root;
    }

    public AbstractPlatformContext<?, ?, ?> getPlatformContext() {
        return platformContext;
    }

    /**
     * 加载配置文件并提交到 {@link Config}。
     *
     * <p>{@code MISSING} / {@code EMPTY} 时使用 Schema 默认值并生成带注释的
     * {@code config.yml}；{@code VALID} 时只读不写；{@code INVALID} 时抛异常且不修改运行时状态。
     */
    private void loadConfig() {
        Path configPath = ConfigStore.resolveConfigPath(platformContext.getDataDirectory());
        ConfigFileReader.Result result = ConfigFileReader.read(configPath);
        ConfigLoader loader = new ConfigLoader(configRegistry, config);

        if (result.getState() == ConfigFileState.INVALID) {
            // 交给 Loader 抛出明确异常；运行时状态保持不变
            loader.load(ConfigFileState.INVALID, null);
        }

        if (result.getState() == ConfigFileState.MISSING || result.getState() == ConfigFileState.EMPTY) {
            logger.warn("配置文件 {} 不存在或为空，将使用默认配置并生成完整配置文件。", configPath);
            ConfigLoadResult loaded = loader.load(result.getState(), result.getMap());
            configDocument = loaded.getDocument();
            writeConfigFile(configPath);
            return;
        }

        ConfigLoadResult loaded = loader.load(result.getState(), result.getMap());
        configDocument = loaded.getDocument();
        logUnknownFields(loaded);
    }

    /**
     * 用当前 Schema + 运行时值 + 文档生成 config.yml（唯一 YAML 输出入口）
     */
    private void writeConfigFile(Path configPath) {
        try {
            new ConfigWriter().write(
                    ConfigWriteSnapshot.of(configRegistry, config, configDocument), configPath, logger);
        } catch (IOException e) {
            logger.warn("生成配置文件 {} 失败：{}", configPath, e.getMessage());
        }
    }

    private void logUnknownFields(ConfigLoadResult loaded) {
        if (!loaded.getUnknownCorePaths().isEmpty()) {
            logger.warn("配置文件存在当前版本不支持的字段（已保留、不会生效）：{}", loaded.getUnknownCorePaths());
        }
        if (!loaded.getUnknownAddonPaths().isEmpty()) {
            logger.info("检测到 {} 个扩展配置项（已保留）：{}",
                    loaded.getUnknownAddonPaths().size(), loaded.getUnknownAddonPaths());
        }
    }

    /**
     * @return 当前配置文档（含未知字段）
     */
    public ConfigDocument getConfigDocument() {
        return configDocument;
    }

    /**
     * @return 配置 Schema
     */
    public ConfigRegistry getConfigRegistry() {
        return configRegistry;
    }

    /**
     * 启动运行时，只能启动一次。
     *
     * <p>仅 {@link RuntimeState#NEW} 状态可调用，其余状态抛 {@link IllegalStateException}；
     * 需要重启时应创建新的 Runtime 实例。
     *
     * <p>启动失败时先清理已创建的资源、把状态置为 {@link RuntimeState#FAILED}，
     * 再抛出原始异常（不包装）。因此平台不需要自己写 try/catch + rollback，
     * 也不会出现"抛异常却留下半启动 Runtime"的状态。
     */
    public void start() {
        synchronized (lifecycleLock) {
            if (state.get() != RuntimeState.NEW) {
                throw new IllegalStateException("QueQiaoRuntime 当前状态不允许启动: " + state.get());
            }
            state.set(RuntimeState.STARTING);

            try {
                doStart();
                state.set(RuntimeState.RUNNING);
            } catch (Throwable startupError) {
                // 启动失败：走独立的资源清理路径（不调用 public shutdown()，
                // 因为那会把状态推进到 STOPPED，而调用方需要知道"曾经启动失败"）。
                try {
                    shutdownResources();
                } catch (Throwable cleanupError) {
                    logger.error("Runtime 启动失败后的清理也失败", cleanupError);
                }
                state.set(RuntimeState.FAILED);
                throw startupError;
            }
        }
    }

    /**
     * @return 当前生命周期状态
     */
    public RuntimeState getState() {
        return state.get();
    }

    private void doStart() {
        logger.info(BaseConstant.LAUNCHING);

        // 核心与 Addon 在启动期完成注册；加载配置前冻结 Schema。
        configRegistry.freeze();

        // §6 固定顺序：先把配置加载并提交，再启动任何依赖配置的服务。
        // 配置非法时这里会抛异常，从而不会出现"半套配置 + 服务已启动"的状态（§12/§38）。
        loadConfig();

        logger.info(BaseConstant.INITIALIZED);

        messagePrefixJsonElement = initMessagePrefixJsonObject(config.get(ConfigKeys.MESSAGE_PREFIX));
        // 翻译服务归属平台：Runtime 只按配置调度它的生命周期，不持有、也不自行创建。
        platformContext.getTranslationService()
                .reload(config.get(ConfigKeys.ENABLE_TRANSLATION));
        serverStatusCollector.initPingTarget();
        serverStatusCollector.startRefreshScheduler(
                config.get(ConfigKeys.Status.REFRESH_INTERVAL_SECONDS));
        initWebsocketManager();
        initRconClient();
    }

    /**
     * 重载配置，并同步刷新依赖配置的组件（WebSocket、RCON、状态采集、翻译）。
     *
     * <p>只允许对 {@link RuntimeState#RUNNING} 的 Runtime 调用，其余状态抛
     * {@link IllegalStateException}。
     *
     * <p>不回执：进度消息作为 {@link ReloadResult} 返回，由调用方决定如何展示。
     *
     * @return 重载结果，携带本应展示给用户的进度消息
     */
    public ReloadResult reload() {
        synchronized (lifecycleLock) {
            if (state.get() != RuntimeState.RUNNING) {
                throw new IllegalStateException("QueQiaoRuntime 当前状态不允许 reload: " + state.get());
            }

            loadConfig();
            messagePrefixJsonElement = initMessagePrefixJsonObject(config.get(ConfigKeys.MESSAGE_PREFIX));
            platformContext.getTranslationService()
                    .reload(config.get(ConfigKeys.ENABLE_TRANSLATION));
            serverStatusCollector.initPingTarget();
            serverStatusCollector.updateRefreshInterval(config.get(ConfigKeys.Status.REFRESH_INTERVAL_SECONDS));
            List<String> messages = new ArrayList<>();
            WebsocketManager manager = websocketManager;
            if (manager != null) {
                messages.addAll(manager.restart(config));
            }
            restartRconClient();
            return new ReloadResult(messages);
        }
    }

    /**
     * 关闭运行时，幂等。
     *
     * <p>只有 {@link RuntimeState#RUNNING} 会真正清理资源并迁移到 {@code STOPPING → STOPPED}；
     * {@code NEW} 与 {@code FAILED} 保持原状态不变（因此"先 shutdown 再 start"仍然可行），
     * {@code STOPPED} / {@code STOPPING} 为 no-op。
     *
     * <p>与 {@link #start()} 共享生命周期锁，不会中断进行中的启动。
     */
    public void shutdown() {
        synchronized (lifecycleLock) {
            RuntimeState current = state.get();
            if (current != RuntimeState.RUNNING) {
                // NEW / FAILED / STOPPED / STOPPING 均为 no-op（STARTING 因共享同一把锁而不可达）
                return;
            }

            state.set(RuntimeState.STOPPING);
            try {
                shutdownResources();
            } finally {
                // 无论清理是否抛异常都必须落到终止状态，否则后续 shutdown() 会被
                // STOPPING 卡成永久 no-op，破坏幂等语义
                state.set(RuntimeState.STOPPED);
            }
            logger.info("鹊桥已关闭");
        }
    }

    /**
     * 清理 Runtime 管理生命周期的资源，并停用平台翻译服务；
     * 顺序为状态采集器 → WebSocket → RCON → 翻译服务。
     *
     * <p>每一项自身幂等，因此本方法可被"启动失败回滚"与"正常关闭"共用。
     */
    private void shutdownResources() {
        serverStatusCollector.stopRefreshScheduler();

        WebsocketManager manager = websocketManager;
        if (manager != null) {
            manager.stop(1000, WebsocketConstantMessage.SHUTDOWN);
            websocketManager = null;
        }

        synchronized (rconLifecycleLock) {
            RconClient client = rconClient;
            rconClient = null;
            if (client != null) {
                client.stop();
            }
        }

        // 翻译服务由平台持有，这里只负责按生命周期停用。disable() 幂等，
        // 且 TranslationService 构造不读磁盘，因此"从未加载过"时调用也无副作用。
        platformContext.getTranslationService().disable();
    }

    /**
     * 分发事件。未启动或已关闭时静默丢弃，不抛异常。
     *
     * @param baseEvent 事件
     */
    public void sendEvent(BaseEvent baseEvent) {
        WebsocketManager manager = websocketManager;
        if (manager == null) {
            utils.debugLog("运行时尚未启动或已关闭，事件未分发");
            return;
        }
        // 事件对象的构造不再读全局状态；服务器上下文在发布前统一填充
        baseEvent.fillServerContext(
                config.get(ConfigKeys.SERVER_NAME),
                platformContext.getServerVersion(),
                platformContext.getServerType());
        manager.sendEvent(baseEvent);
    }

    private void initWebsocketManager() {
        websocketManager = new WebsocketManager(logger, gson, handleProtocolMessage, config, utils);
        websocketManager.start();
    }

    private void initRconClient() {
        synchronized (rconLifecycleLock) {
            ConfigSnapshot configSnapshot = config.snapshot();
            if (configSnapshot.valueOf(ConfigKeys.Rcon.ENABLE)) {
                RconClient client = createRconClient(configSnapshot);
                client.connect();
                rconClient = client;
            } else {
                rconClient = null;
                logger.info("Rcon 未启用，跳过 Rcon 客户端初始化");
            }
        }
    }

    private void restartRconClient() {
        synchronized (rconLifecycleLock) {
            RconClient previous = rconClient;
            ConfigSnapshot configSnapshot = config.snapshot();
            if (!configSnapshot.valueOf(ConfigKeys.Rcon.ENABLE)) {
                rconClient = null;
                if (previous != null) {
                    previous.stop();
                    logger.info("Rcon 已根据新配置禁用并关闭连接");
                }
                return;
            }

            RconClient replacement = createRconClient(configSnapshot);
            replacement.connect();
            // 先发布新客户端，再关闭旧客户端；已经拿到旧引用的调用由旧客户端自身串行化。
            rconClient = replacement;
            if (previous != null) {
                previous.stop();
            }
        }
    }

    private RconClient createRconClient(ConfigSnapshot source) {
        return new RconClient(
                logger,
                source.valueOf(ConfigKeys.Rcon.PORT),
                source.valueOf(ConfigKeys.Rcon.PASSWORD));
    }

    public String sendRconCommand(String command) throws RconException {
        if (!config.get(ConfigKeys.Rcon.ENABLE)) {
            throw RconException.disabled();
        }
        RconClient client = rconClient;
        if (client == null) {
            throw RconException.disconnected();
        }
        return client.sendCommand(command);
    }

    public JsonElement initMessagePrefixJsonObject(String messagePrefixText) {
        if (messagePrefixText == null) {
            messagePrefixText = "[鹊桥]";
        }

        String criteria = messagePrefixText.trim();

        if (criteria.isEmpty()) {
            JsonObject emptyObj = new JsonObject();
            emptyObj.addProperty("text", "");
            logger.info("消息前缀配置为空，已禁用前缀显示。");
            return emptyObj;
        }

        if ((criteria.startsWith("{") && criteria.endsWith("}")) || (criteria.startsWith("[") && criteria.endsWith("]"))) {
            try {
                JsonElement element = gson.fromJson(messagePrefixText, JsonElement.class);

                if (element.isJsonObject()) {
                    logger.info("消息前缀已成功解析为 MC 组件格式 (JSON Object)。");
                    return element;
                } else if (element.isJsonArray()) {
                    JsonArray array = element.getAsJsonArray();
                    if (!(array.size() == 0) && array.get(0).isJsonObject()) {
                        logger.info("消息前缀已成功解析为 MC 组件格式 (JSON Array)。");
                        return element;
                    }
                }
            } catch (JsonSyntaxException e) {
                if (criteria.startsWith("{")) {
                    logger.warn("检测到前缀尝试使用 JSON 格式但语法错误: {}", e.getMessage());
                }
            }
        }

        logger.info("消息前缀将采用默认风格的文本前缀: {}", messagePrefixText);
        JsonObject obj = new JsonObject();
        obj.addProperty("text", messagePrefixText);
        obj.addProperty("color", "yellow");
        return obj;
    }

    /**
     * @return 配置运行时状态（<b>唯一</b>配置状态来源）
     */
    public Config getConfig() {
        return config;
    }

    /**
     * 替换运行时配置状态（供测试与工具注入；正常流程由 {@code loadConfig()} 完成）
     *
     * @param config 新的配置运行时状态
     */
    public void setConfig(Config config) {
        if (config == null) {
            throw new IllegalArgumentException("Config 不能为 null");
        }
        this.config = config;
    }

    public Logger getLogger() {
        return logger;
    }

    /**
     * 替换日志实现。只影响之后通过 {@link #getLogger()} 读取的路径；已构造的协作者
     * 在构造时即捕获 logger，不受影响。
     *
     * @param logger 新的日志实现
     */
    public void setLogger(Logger logger) {
        this.logger = logger;
    }
    public WebsocketManager getWebsocketManager() {
        return websocketManager;
    }

    /**
     * @return 本 Runtime 独占的状态采集器（实例级，不与其他 Runtime 共享）
     */
    public ServerStatusCollector getServerStatusCollector() {
        return serverStatusCollector;
    }

    /**
     * @return 服务端版本，由平台上下文提供
     */
    public String getServerVersion() {
        return platformContext.getServerVersion();
    }

    /**
     * @return 平台类型，由平台上下文提供
     */
    public String getServerType() {
        return platformContext.getServerType();
    }

    public Gson getGson() {
        return gson;
    }

    public JsonElement getMessagePrefixJsonElement() {
        return messagePrefixJsonElement;
    }
}
