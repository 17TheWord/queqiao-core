package io.github.theword.queqiao.core.platform;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.UUID;
import java.util.function.Function;

import com.google.gson.JsonElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.theword.queqiao.core.constant.BaseConstant;
import io.github.theword.queqiao.core.constant.ServerTypeConstant;
import io.github.theword.queqiao.core.event.model.PlayerModel;
import io.github.theword.queqiao.core.event.model.TranslateModel;
import io.github.theword.queqiao.core.translation.TranslationService;

/**
 * 平台上下文：core 与具体服务端之间的唯一接入点。
 *
 * <p>平台侧继承本类并实现抽象原语，core 侧只通过本类的公开方法访问平台能力，
 * 因此 core 不依赖任何具体服务端类型。本类只描述"平台本身能做什么"（元数据、玩家查询、
 * 组件转换、广播、私聊、Title、ActionBar、数据目录、翻译服务）；命令来源相关能力
 * （回执与权限判定）属于 {@code CommandExecutionContext}。
 *
 * <p>Core 面向协议调用的平台操作通过 {@link PlatformResult} 表达执行结果，不依赖任何协议类型 ——
 * 把它翻译成协议状态是 {@code Api} 层的职责。平台原生类型（{@code S / C / P}）只出现在
 * 平台适配原语上；Core 侧调用的方法签名中不出现它们，因此持有
 * {@code AbstractPlatformContext<?, ?, ?>} 时仍可直接调用。
 *
 * <p>构造时即持有服务端实例；{@link #getServerType()}、{@link #getServerVersion()} 与
 * {@link #getDataDirectory()} 必须不晚于 Runtime {@code start()} 可用 ——
 * Core 从 {@code start()} 起开始使用它们。
 *
 * @param <S> 服务端实例类型
 * @param <C> 消息组件类型
 * @param <P> 玩家类型
 */
public abstract class AbstractPlatformContext<S, C, P> {

    /**
     * 目标玩家不存在时返回给调用方的说明文本。
     */
    private static final String MESSAGE_PLAYER_NOT_FOUND = "Target player not found.";

    /**
     * 平台未实现标题功能时返回的说明文本。
     */
    private static final String MESSAGE_TITLE_UNSUPPORTED = "当前平台未实现标题功能";

    /**
     * 平台未实现 ActionBar 功能的说明
     */
    private static final String MESSAGE_ACTIONBAR_UNSUPPORTED = "当前平台未实现 ActionBar 功能";

    /**
     * 基类自建组件使用的日志实现，logger 名取 {@link BaseConstant#MODULE_NAME}。
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(BaseConstant.MODULE_NAME);

    /**
     * 服务端实例，构造时给出。
     */
    public final S server;

    /**
     * 平台翻译服务，首次 {@link #getTranslationService()} 时创建；volatile + 双重检查。
     */
    private volatile TranslationService translationService;

    public AbstractPlatformContext(S server) {
        this.server = server;
    }

    // ------------------------------------------------------------------
    // 平台元数据
    // ------------------------------------------------------------------

    /**
     * 平台类型
     *
     * <p>应返回 {@link ServerTypeConstant} 中的规范值（如 {@code "fabric"} / {@code "spigot"}），
     * 而不是服务端返回的原始字符串（后者可能形如 {@code "spigot-beta-satshop"}，不可控）。
     *
     * @return 平台类型
     */
    public abstract String getServerType();

    /**
     * 服务端版本
     *
     * @return 服务端版本，未知时可返回 null
     */
    public abstract String getServerVersion();

    /**
     * @return 是否为模组服务端
     * @deprecated 改用 {@link #getDataDirectory()} —— "模组 = config 目录"只是常见规律，
     * 由它推导路径会把平台差异编码进调用方。
     */
    @Deprecated
    public boolean isModServer() {
        return ServerTypeConstant.isModType(getServerType());
    }

    /**
     * 返回 QueQiao 数据目录（{@code config.yml} 与 {@code translate/} 的父目录）。
     *
     * <p>默认根据服务端类型选择 config 或 plugins 目录，且返回相对工作目录的相对路径；
     * 特殊平台可覆盖此方法，覆盖后也允许返回绝对路径。
     *
     * <p>同一个 PlatformContext 生命周期内应返回稳定值 —— Runtime 用它定位配置与翻译。
     *
     * @return 数据目录
     */
    public Path getDataDirectory() {
        return ServerTypeConstant.isModType(getServerType())
                ? Paths.get("config", BaseConstant.MODULE_NAME)
                : Paths.get("plugins", BaseConstant.MODULE_NAME);
    }

    // ------------------------------------------------------------------
    // 平台必须实现的原语
    // ------------------------------------------------------------------

    /**
     * 把 JSON 组件转换为平台自己的组件类型
     *
     * @param json JSON 组件
     * @return 平台组件
     */
    public abstract C jsonToComponent(JsonElement json);

    /**
     * @return 当前在线玩家集合
     */
    public abstract Collection<P> getPlayers();

    /**
     * @param player 玩家
     * @return 玩家昵称
     */
    public abstract String getPlayerName(P player);

    /**
     * @param player 玩家
     * @return 玩家 UUID
     */
    public abstract UUID getPlayerUUID(P player);

    /**
     * 把平台玩家对象转换为 {@link PlayerModel} 快照。
     *
     * @param player 玩家
     * @return {@link PlayerModel} 快照
     */
    public abstract PlayerModel getPlayerModel(P player);

    /**
     * 广播组件给所有玩家。
     *
     * <p>Core 只区分成功与失败，因此不返回任何领域数据；{@code C} 不会离开平台层。
     *
     * @param component 平台组件
     * @return 成功 / 失败结果
     */
    public abstract PlatformResult<Void> broadcast(C component);

    /**
     * 发送组件给指定玩家
     *
     * @param player    玩家
     * @param component 平台组件
     * @return 成功 / 失败结果
     */
    public abstract PlatformResult<Void> sendPrivateMessage(P player, C component);

    // ------------------------------------------------------------------
    // 可选原语：默认表示"平台不支持"，平台按需覆盖
    // ------------------------------------------------------------------

    /**
     * 向所有玩家发送标题（可选能力）
     *
     * <p>默认返回 {@link PlatformResultCode#UNSUPPORTED}，表示当前平台不支持标题。
     * 支持标题的平台覆盖本方法即可。
     *
     * @param title    主标题组件，可为 null
     * @param subtitle 副标题组件，可为 null
     * @param fadeIn   淡入时间（ticks）
     * @param stay     停留时间（ticks）
     * @param fadeOut  淡出时间（ticks）
     * @return 成功 / 失败结果
     */
    public PlatformResult<Void> sendTitleComponent(C title, C subtitle, int fadeIn, int stay, int fadeOut) {
        return PlatformResult.failure(
                PlatformResultCode.UNSUPPORTED, MESSAGE_TITLE_UNSUPPORTED);
    }

    /**
     * 向所有玩家发送 ActionBar（可选能力）
     *
     * <p>默认返回 {@link PlatformResultCode#UNSUPPORTED}，表示当前平台不支持 ActionBar。
     * 支持的平台覆盖本方法即可。
     *
     * @param component 平台组件
     * @return 成功 / 失败结果
     */
    public PlatformResult<Void> sendActionBarComponent(C component) {
        return PlatformResult.failure(
                PlatformResultCode.UNSUPPORTED, MESSAGE_ACTIONBAR_UNSUPPORTED);
    }

    // ------------------------------------------------------------------
    // 翻译能力（归属平台：目录与原生解析方式由平台决定）
    // ------------------------------------------------------------------

    /**
     * 返回本平台的翻译服务。
     *
     * <p>基类负责创建，平台无需自己 new：同一个上下文实例始终返回同一个
     * {@link TranslationService}，目录取自 {@link #getDataDirectory()} 下的 {@code translate}。
     * 首次调用时才创建（线程安全的懒初始化），且创建过程不读磁盘 ——
     * 是否加载由 {@link TranslationService#reload(boolean)} 决定。
     *
     * @return 平台翻译服务，永不为 null
     */
    public final TranslationService getTranslationService() {
        TranslationService service = translationService;
        if (service == null) {
            synchronized (this) {
                service = translationService;
                if (service == null) {
                    service = new TranslationService(
                            getDataDirectory().resolve("translate"), LOGGER);
                    translationService = service;
                }
            }
        }
        return service;
    }

    protected final TranslateModel buildTranslateModel(
            String key,
            Object[] args,
            Function<Object, TranslateModel> parseArgument,
            String fallbackText
    ) {
        TranslateModel[] children = new TranslateModel[args.length];
        String[] formattedArgs = new String[args.length];

        for (int i = 0; i < args.length; i++) {
            TranslateModel child = parseArgument.apply(args[i]);
            children[i] = child;
            formattedArgs[i] = child.getText();
        }

        String text = getTranslationService().translate(key, formattedArgs);

        if (text == null || text.equals(key)) {
            text = fallbackText;
        }

        return new TranslateModel(key, children, text);
    }

    /**
     * 把平台原生消息组件解析为 {@link TranslateModel}（可选能力）。
     *
     * <p>默认返回 {@code null}，表示平台不提供结构化解析 —— 这是合法状态，Core 与 Runtime
     * 都不会因此失败。具备"翻译键 + 参数"结构的平台可覆盖本方法，把组件递归拆成
     * {@link TranslateModel}。
     *
     * <p>无法解析时，平台可以使用自己的原始显示文本作为
     * {@link TranslateModel#getText()} 的取值；但不要把渲染文本当作
     * {@link TranslateModel#getKey()} 或 {@link TranslateModel#getArgs()} 的结构化解析结果。
     *
     * @param component 平台组件
     * @return 解析结果；平台未提供结构化解析时返回 null
     */
    public TranslateModel parseTranslateModel(C component) {
        return null;
    }

    // ------------------------------------------------------------------
    // core 提供调用的方法
    // ------------------------------------------------------------------

    /**
     * 广播 JSON 组件给所有玩家
     *
     * @param json JSON 组件
     * @return 成功 / 失败结果
     */
    public final PlatformResult<Void> broadcast(JsonElement json) {
        return broadcast(jsonToComponent(json));
    }

    /**
     * 根据玩家名称或 UUID 查找在线玩家。
     *
     * <p>当同时提供名称和 UUID 时，优先使用 UUID 进行查找，名称不参与匹配。
     * 当未提供 UUID 时，才使用名称进行查找。
     *
     * <p>如果名称和 UUID 均未提供，或未找到匹配的在线玩家，则返回 {@code null}。
     *
     * @param name 玩家名称，可以为 {@code null} 或空字符串
     * @param uuid 玩家 UUID 字符串，可以为 {@code null} 或空字符串
     * @return 匹配的在线玩家，未找到时返回 {@code null}
     */
    public final P findPlayer(String name, String uuid) {
        boolean hasName = name != null && !name.isEmpty();
        boolean hasUuid = uuid != null && !uuid.isEmpty();

        if (!hasName && !hasUuid) {
            return null;
        }

        for (P player : getPlayers()) {
            if (hasUuid) {
                UUID playerUuid = getPlayerUUID(player);
                if (playerUuid != null && playerUuid.toString().equals(uuid)) {
                    return player;
                }
            } else {
                String playerName = getPlayerName(player);
                if (playerName != null && playerName.equals(name)) {
                    return player;
                }
            }
        }

        return null;
    }

    /**
     * 发送标题给所有玩家
     *
     * @param title    主标题，可为 null
     * @param subtitle 副标题，可为 null
     * @param fadeIn   淡入时间（ticks）
     * @param stay     停留时间（ticks）
     * @param fadeOut  淡出时间（ticks）
     * @return 成功 / 失败结果；平台不支持时返回 {@link PlatformResultCode#UNSUPPORTED}
     */
    public final PlatformResult<Void> sendTitle(JsonElement title, JsonElement subtitle,
                                                int fadeIn, int stay, int fadeOut) {
        C titleComponent = title == null ? null : jsonToComponent(title);
        C subtitleComponent = subtitle == null ? null : jsonToComponent(subtitle);
        return sendTitleComponent(titleComponent, subtitleComponent, fadeIn, stay, fadeOut);
    }

    /**
     * 发送 ActionBar 给所有玩家
     *
     * @param json ActionBar 内容，JSON 格式
     * @return 成功 / 失败结果；平台不支持时返回 {@link PlatformResultCode#UNSUPPORTED}
     */
    public final PlatformResult<Void> sendActionBar(JsonElement json) {
        return sendActionBarComponent(jsonToComponent(json));
    }

    /**
     * 查找玩家、发送私聊，并返回结果。
     *
     * @param nickname 目标玩家昵称，可为 null
     * @param uuid     目标玩家 UUID，可为 null
     * @param json     消息内容，JSON 格式
     * @return 未找到玩家时为 {@link PlatformResultCode#PLAYER_NOT_FOUND}；
     * 发送失败时透传发送结果码；成功时 data 为目标玩家的 {@link PlayerModel} 快照
     */
    public final PlatformResult<PlayerModel> sendPrivateMessage(String nickname, UUID uuid, JsonElement json) {
        P player = findPlayer(nickname, uuid == null ? null : uuid.toString());
        if (player == null) {
            return PlatformResult.failure(PlatformResultCode.PLAYER_NOT_FOUND, MESSAGE_PLAYER_NOT_FOUND);
        }

        PlatformResult<Void> sent = sendPrivateMessage(player, jsonToComponent(json));
        if (!sent.isSuccess()) {
            return PlatformResult.failure(sent.getCode(), sent.getMessage());
        }

        return PlatformResult.success(new PlayerModel(getPlayerName(player), getPlayerUUID(player)));
    }
}
