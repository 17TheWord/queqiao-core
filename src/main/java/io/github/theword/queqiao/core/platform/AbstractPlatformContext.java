package io.github.theword.queqiao.core.platform;

import java.util.Collection;
import java.util.UUID;

import com.google.gson.JsonElement;

import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.constant.ServerTypeConstant;
import io.github.theword.queqiao.core.event.model.PlayerModel;

/**
 * 平台上下文：core 与具体服务端之间的<b>唯一</b>接入点
 *
 * <p>平台侧继承本类并实现其中的抽象原语；core 侧只通过本类的公开方法访问平台能力，
 * 因此 core 不依赖任何具体服务端的类型。
 *
 * <p><b>职责边界（重要）</b>：本类只描述"平台本身能做什么"——
 * 平台元数据、玩家查询、组件转换、广播、私聊、Title、ActionBar。
 * <b>不负责</b>命令来源相关能力：回执（{@code reply}）与权限判定已归属
 * {@link CommandSource}，命令来源的类型转换也不在本类。
 * 两者职责完全分离：
 * <pre>
 * AbstractPlatformContext   平台整体能力
 * CommandSource             命令来源能力（谁发起的 / 如何回复 / 其权限）
 * </pre>
 *
 * <p><b>结果表达</b>：平台操作统一返回 {@link PlatformResult}，
 * 用 {@link PlatformResultCode} 表达"平台侧发生了什么"。
 * 本类<b>不</b>依赖 {@code ProtocolException}、{@code Response}、
 * {@code PrivateMessageResponse} 或任何协议类型——
 * 把 {@link PlatformResult} 翻译成协议状态是 {@code Api} 层的职责。
 * 因此其它模块（命令层、未来的非协议调用方）也可以直接调用本类并消费结果，
 * 不需要经过 Api / WebSocket。
 *
 * <p><b>公开方法签名约定（重要）</b>：core 侧调用的公开方法签名中<b>不出现</b>
 * {@code S / C / P} 这三个类型参数。这样 core 侧持有
 * {@code AbstractPlatformContext<?, ?, ?>} 时仍可直接调用，
 * 无需到处传播通配符；凡是要用到平台类型 P / C 的操作，
 * 都在本类内部一次性完成。
 *
 * <p><b>延迟绑定</b>：{@link #getServerType()} 与 {@link #getServerVersion()}
 * 只要求在本上下文被使用前（即 Runtime {@code start()} 之后）可用，
 * 不要求构造时即可用。因此平台可以在服务端实例就绪之前先构造本上下文。
 *
 * @param <S> 服务端实例类型
 * @param <C> 消息组件类型
 * @param <P> 玩家类型
 */
public abstract class AbstractPlatformContext<S, C, P> {

    /**
     * 目标玩家不存在的说明
     *
     * <p>与历史上 {@code PrivateMessageResponse.playerNotFound()} 的文案保持一致，
     * 便于非协议调用方直接展示。
     */
    private static final String MESSAGE_PLAYER_NOT_FOUND = "Target player not found.";

    /**
     * 服务端实例
     *
     * <p>平台需自行保证该实例在 {@link #getPlayers()} 等原语被调用前可用。
     */
    public final S server;

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
     * 是否为模组服务端
     *
     * <p>默认按 {@link #getServerType()} 推导。若某平台不符合"模组 = config 目录"这一规律，
     * 覆盖本方法即可。
     *
     * @return 是否为模组服务端
     */
    public boolean isModServer() {
        return ServerTypeConstant.isModType(getServerType());
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
     * 广播组件给所有玩家
     *
     * <p><b>Core 语义只有"广播成功 / 广播失败"</b>，因此不返回任何数据：
     * 底层平台 API 的返回值（例如渲染后的文本）对 Core 调用者没有业务意义，
     * 不应作为返回值暴露出来。
     *
     * <p>{@code C} 本身<b>不会</b>离开平台层。
     *
     * <p>若将来 Core 确实需要广播人数、消息 ID 等领域数据，
     * 应引入专门的领域结果类型（如 {@code BroadcastResult}），
     * 而不是保留一个无法回答"这个值代表什么"的 {@code String}。
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
                PlatformResultCode.UNSUPPORTED, ProtocolConstants.Message.TITLE_UNSUPPORTED);
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
                PlatformResultCode.UNSUPPORTED, ProtocolConstants.Message.ACTIONBAR_UNSUPPORTED);
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
     * 发送私聊消息，并返回结果
     *
     * <p>查找玩家、发送、构造结果三步都在本方法内完成——只有平台侧认识玩家类型 P，
     * 因此这类"碰 P"的操作统一在本类内部收口，不向外暴露泛型。
     *
     * <p>成功时 data 为目标玩家的 Core DTO 快照（{@link PlayerModel}），
     * 因此调用方可以据此构造自己的响应，而不需要知道平台玩家类型。
     *
     * @param nickname 目标玩家昵称，可为 null
     * @param uuid     目标玩家 UUID，可为 null
     * @param json     消息内容，JSON 格式
     * @return 未找到玩家时为 {@link PlatformResultCode#PLAYER_NOT_FOUND}；
     * 发送失败时透传发送结果码；成功时 data 为目标玩家快照
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
