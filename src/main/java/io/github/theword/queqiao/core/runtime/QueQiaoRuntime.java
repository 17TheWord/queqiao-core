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
import io.github.theword.queqiao.core.constant.BaseConstant;
import io.github.theword.queqiao.core.constant.WebsocketConstantMessage;
import io.github.theword.queqiao.core.event.base.BaseEvent;
import io.github.theword.queqiao.core.exception.rcon.RconException;
import io.github.theword.queqiao.core.handle.HandleProtocolMessage;
import io.github.theword.queqiao.core.localize.LanguageService;
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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class QueQiaoRuntime {

    // ------------------------------------------------------------------
    // 生命周期状态
    // ------------------------------------------------------------------

    /**
     * 生命周期状态
     *
     * <p>迁移单向：{@code NEW → STARTING → RUNNING → STOPPING → STOPPED}；
     * 启动失败时 {@code STARTING → FAILED}。<b>不支持重新启动</b>。
     */
    private final AtomicReference<RuntimeState> state = new AtomicReference<>(RuntimeState.NEW);

    /**
     * 串行化 {@code start()} / {@code reload()} / {@code shutdown()} 的生命周期锁
     *
     * <p>目的不是保护业务数据，而是保证这三个操作不会并发改变 Runtime 生命周期。
     * 它<b>不</b>替代 Config / WebSocket / RCON 各自的同步机制。
     */
    private final Object lifecycleLock = new Object();

    // ------------------------------------------------------------------
    // 构造后不再变化的字段：用 final 保证安全发布
    // ------------------------------------------------------------------

    /**
     * Runtime 作用域辅助能力
     *
     * <p>随 Runtime 生命周期创建一次、之后不再替换，因此直接以 {@code public final}
     * 暴露为固定入口，调用形式为 {@code runtime.utils.debugLog(...)}。
     *
     * <p>注意：本字段是 Runtime 上<b>唯一</b>的 {@code public final} 字段。
     * Config / WebsocketManager / RconClient 等存在 reload 或替换语义的对象
     * 继续维持原有的 private + getter 形式。
     */
    public final RuntimeUtils utils;

    private final Gson gson;

    /**
     * 协议分发入口
     *
     * <p>属于平台级能力而非 WebSocket 传输层细节：分发逻辑与传输方式无关，
     * 因此在此处创建唯一实例，再注入给各传输层（当前为 WebSocket，未来可含 HTTP）。
     *
     * <p>该对象构造后即不可变，可安全地在多个连接/线程间共享。
     */
    private final HandleProtocolMessage handleProtocolMessage;

    /**
     * 服务器状态采集器
     *
     * <p>Runtime 实例级持有：快照缓存、探测目标、刷新线程池与任务全部属于本实例，
     * 因此同一 JVM 中的多个 Runtime 之间不存在状态共享。
     *
     * <p>生命周期由 Runtime 管理（start 启动、reload 刷新目标与间隔、shutdown 停止）。
     */
    private final ServerStatusCollector serverStatusCollector;

    // ------------------------------------------------------------------
    // 会随 start / reload / shutdown 变化的字段
    //
    // 标记为 volatile：这些字段由 init / reload 线程写入，由游戏线程与 WebSocket 线程读取。
    // 单个字段的可见性由此得到保证，但<b>跨字段的原子快照没有保证</b>——
    // 并发 reload 期间可能瞬时读到新旧混合的值。如需绝对一致，
    // 应改为"不可变状态对象整体替换"。当前 reload 本身会输出日志与消息，
    // 瞬时不一致可接受，故不为此引入额外复杂度。
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
     * 当前配置文档（保留未知字段与原始结构，供 Writer / Checker / Synchronizer 使用）
     *
     * <p>不能只凭 Runtime 重建文档——Runtime 不知道未知字段与文档结构。
     */
    private volatile ConfigDocument configDocument;

    /**
     * 日志实现
     *
     * <p>非 final：为兼容既有的 {@link #setLogger(Logger)} 公开接口。
     *
     * <p>注意：{@link HandleProtocolMessage}、{@link RuntimeUtils} 等协作者在构造时即捕获 logger，
     * 因此运行期替换 logger 只会影响之后才通过 {@link #getLogger()} 读取的路径，
     * 不会改变已构造对象的日志输出。
     */
    private volatile Logger logger;

    public final AbstractPlatformContext<?, ?, ?> platformContext;

    private volatile WebsocketManager websocketManager;

    /** 串行化 Runtime 对 RCON 客户端的创建、替换和关闭。 */
    private final Object rconLifecycleLock = new Object();

    private volatile RconClient rconClient;

    private volatile JsonElement messagePrefixJsonElement;

    private volatile LanguageService languageService;

    private QueQiaoRuntime(
            Logger logger,
            AbstractPlatformContext<?, ?, ?> platformContext,
            Consumer<List<Api<?, ?>>> apiConfigurer
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
        // 协议层接线：先铺默认批次，再交给使用方自由增删（自愿注册）。
        // 这里传入 this::sendRconCommand 是安全的：该 lambda 只在收到请求时才会被调用，
        // 此时对象早已构造完成（构造期间不会被发布）。
        List<Api<?, ?>> apis = new ArrayList<>(
                DefaultApis.all(platformContext, this.serverStatusCollector, this::sendRconCommand, logger));
        if (apiConfigurer != null) {
            apiConfigurer.accept(apis);
        }
        this.handleProtocolMessage = new HandleProtocolMessage(logger, this.gson, apis, this.utils);
    }

    /**
     * 创建运行时并在配置加载前注册扩展配置。
     *
     * <p><b>构造不变量</b>：平台实现是 API 边界的必填依赖，此处立即校验并指明参数名。
     * 若不在此拦截，会拖到"第一条协议请求"或"第一条命令"执行时才抛 NPE，定位成本很高。
     *
     * @param configurer 可选的启动期 Schema 注册回调
     * @param platformContext 平台上下文；服务端类型、版本、是否模组端均由它提供，
     *                        因此不再作为独立参数传入
     * @param apiConfigurer 可选的 Api 装配回调；收到的是<b>已铺好默认批次</b>的可变列表，
     *                      可自由增删以实现"自愿注册"
     * @return 尚未启动的运行时
     */
    public static QueQiaoRuntime create(
            Consumer<ConfigRegistry> configurer,
            AbstractPlatformContext<?, ?, ?> platformContext,
            Consumer<List<Api<?, ?>>> apiConfigurer
    ) {
        Objects.requireNonNull(
                platformContext, "platformContext 不能为 null：平台必须提供 AbstractPlatformContext 实现");
        Logger runtimeLogger = LoggerFactory.getLogger(BaseConstant.MODULE_NAME);
        QueQiaoRuntime runtime = new QueQiaoRuntime(
                runtimeLogger,
                platformContext,
                apiConfigurer
        );
        if (configurer != null) {
            configurer.accept(runtime.configRegistry);
        }
        return runtime;
    }

    /**
     * 加载配置文件并提交到 {@link Config}
     *
     * <p><b>四状态语义</b>（沿用 {@code ConfigFileState}）：
     * <ul>
     *     <li>{@code MISSING} / {@code EMPTY}：全部使用 Schema 默认值，并生成一份完整的
     *         带注释 {@code config.yml}（首次启动体验）；</li>
     *     <li>{@code VALID}：加载后<b>只读不写</b>——正常启动不得无条件重写用户文件；</li>
     *     <li>{@code INVALID}：抛出异常且<b>不修改运行时状态</b>，由上层终止初始化。</li>
     * </ul>
     */
    private void loadConfig() {
        Path configPath = ConfigStore.resolveConfigPath(platformContext.isModServer());
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
     * 启动运行时
     *
     * <p><b>只能启动一次</b>：仅 {@link RuntimeState#NEW} 状态可以调用本方法，
     * 其余状态一律抛出 {@link IllegalStateException}。需要"重启"时应创建新的 Runtime 实例。
     *
     * <p><b>失败回滚</b>：本方法对自己创建的资源负责。启动过程中任何一步抛异常时，
     * 都会先调用 {@link #shutdownResources()} 清理已创建的资源，把状态置为
     * {@link RuntimeState#FAILED}，再抛出<b>原始异常</b>（不包装、不替换）。
     * 因此各平台实现不必重复编写 try/catch + rollback，也不会出现
     * "start() 抛异常却留下半启动 Runtime"的状态。
     *
     * <p>典型接线：
     * <pre>
     * QueQiaoRuntime runtime = QueQiaoRuntime.create(...);
     * runtime.start();          // 失败则抛出，且 Runtime 已完成自清理（状态 FAILED）
     * this.runtime = runtime;   // 只有 start() 成功后才发布
     * </pre>
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
        languageService = new LanguageService(platformContext.isModServer(), logger, config);
        serverStatusCollector.initPingTarget();
        serverStatusCollector.startRefreshScheduler(
                config.get(ConfigKeys.Status.REFRESH_INTERVAL_SECONDS));
        initWebsocketManager();
        initRconClient();
    }

    /**
     * 重载配置
     *
     * <p>只允许对 {@link RuntimeState#RUNNING} 的 Runtime 调用：reload 的语义是
     * "运行中 Runtime 的配置重载"，不是初始化 API。{@code NEW} / {@code STARTING} /
     * {@code STOPPED} / {@code FAILED} 状态一律抛出 {@link IllegalStateException}。
     *
     * <p>注意：命令层在 Runtime 关闭后仍可能持有旧的命令树；此时触发 reload 会抛出
     * 上述异常，并由命令层的 {@code execute} 捕获后回显"命令执行出错"。
     *
     * <p><b>不回执</b>：Runtime 不负责向命令执行者发送消息。重载过程中的进度消息
     * 作为 {@link ReloadResult} 返回，由调用方（通常是 {@code ReloadCommand}）决定如何展示。
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
            LanguageService service = languageService;
            if (service != null) {
                service.reload();
            }
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
     * 关闭运行时
     *
     * <p><b>幂等</b>，且按状态区分语义：
     * <ul>
     *     <li>{@link RuntimeState#NEW}：no-op，状态保持 {@code NEW}——从未启动就没有
     *         Runtime-owned 资源，因此"先 shutdown 再 start"仍然可行；</li>
     *     <li>{@link RuntimeState#RUNNING}：{@code RUNNING → STOPPING → STOPPED}，
     *         期间清理全部 Runtime-owned 资源；</li>
     *     <li>{@link RuntimeState#FAILED}：no-op，状态保持 {@code FAILED}——启动失败时
     *         资源已完成清理，且调用方需要知道该 Runtime 曾经启动失败；</li>
     *     <li>{@link RuntimeState#STOPPED} / {@link RuntimeState#STOPPING}：no-op。</li>
     * </ul>
     *
     * <p>与 {@link #start()} 共享生命周期锁，因此不会观察到 {@code STARTING} 并强行中断启动。
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
     * 清理全部 Runtime-owned 资源
     *
     * <p>关闭顺序保持既有约定：状态采集器 → WebSocket → RCON → 语言服务；
     * 每一项自身都幂等，因此本方法可被"启动失败回滚"与"正常关闭"复用，
     * 而不会把生命周期状态变化与资源清理实现耦合在一起。
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

        LanguageService service = languageService;
        if (service != null) {
            service.disable();
        }
    }

    /**
     * 分发事件
     *
     * <p>运行时空对象状态下静默丢弃，不抛异常。
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

    public boolean isTranslationEnabled() {
        LanguageService service = languageService;
        return service != null && service.isInternalEnable();
    }

    public String translate(String key, String[] args) {
        LanguageService service = languageService;
        if (service == null) {
            return key;
        }
        return service.translate(key, args);
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
     * 替换日志实现（仅为兼容保留的公开接口）
     *
     * <p>只影响之后才通过 {@link #getLogger()} 读取的路径。
     * 已经构造的协作者（{@link HandleProtocolMessage}、{@link RuntimeUtils}、
     * {@link ServerStatusCollector}、{@link LanguageService} 等）在构造时即捕获 logger，
     * 不会被本次替换影响。
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
     * @return 服务端版本，由平台上下文提供（延迟获取，{@code start()} 之后可用）
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
