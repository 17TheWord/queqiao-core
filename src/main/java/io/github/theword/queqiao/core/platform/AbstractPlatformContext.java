package io.github.theword.queqiao.core.platform;

import java.util.Collection;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.constant.ServerTypeConstant;
import io.github.theword.queqiao.core.event.model.PlayerModel;
import io.github.theword.queqiao.core.exception.protocol.ProtocolException;
import io.github.theword.queqiao.core.response.PrivateMessageResponse;

/**
 * 平台上下文：core 与具体服务端之间的<b>唯一</b>接入点
 *
 * <p>平台侧继承本类并实现其中的抽象原语；core 侧只通过本类的公开方法访问平台能力，
 * 因此 core 不依赖任何具体服务端的类型。
 *
 * <p><b>公开方法签名约定（重要）</b>：本类的公开方法签名中<b>不出现</b>
 * {@code S / C / P / CS} 这四个类型参数。这样 core 侧持有
 * {@code AbstractPlatformContext<?, ?, ?, ?>} 时仍可直接调用，
 * 无需到处传播通配符；凡是要用到平台类型 P / C / CS 的操作，
 * 都在本类内部一次性完成。
 *
 * <p><b>延迟绑定</b>：{@link #getServerType()} 与 {@link #getServerVersion()}
 * 只要求在本上下文被使用前（即 Runtime {@code start()} 之后）可用，
 * 不要求构造时即可用。因此平台可以在服务端实例就绪之前先构造本上下文。
 *
 * @param <S>  服务端实例类型
 * @param <C>  消息组件类型
 * @param <P>  玩家类型
 * @param <CS> 命令源类型
 */
public abstract class AbstractPlatformContext<S, C, P, CS> {

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
     * @param component 平台组件
     */
    public abstract void broadcast(C component);

    /**
     * 发送组件给指定玩家
     *
     * @param player    玩家
     * @param component 平台组件
     */
    public abstract void sendMessage(P player, C component);

    /**
     * 判断命令源是否具有指定权限
     *
     * <p><b>命名说明</b>：本方法名带 {@code do} 前缀，是为了与公开入口
     * {@link #checkPermission(Object, String)} 区分——两者擦除后签名相同，
     * 同名会导致编译期的 name clash。
     *
     * @param source     命令源
     * @param permission 权限节点
     * @return 是否具有权限
     */
    public abstract boolean doCheckPermission(CS source, String permission);

    /**
     * 向命令源回执组件
     *
     * @param source    命令源
     * @param component 平台组件
     */
    public abstract void returnCallBackMessage(CS source, C component);

    // ------------------------------------------------------------------
    // 可选原语：默认表示"平台不支持"，平台按需覆盖
    // ------------------------------------------------------------------

    /**
     * 向所有玩家发送标题（可选能力）
     *
     * <p>默认实现抛出 503，表示当前平台不支持标题。支持标题的平台覆盖本方法即可。
     *
     * @param title    主标题组件，可为 null
     * @param subtitle 副标题组件，可为 null
     * @param fadeIn   淡入时间（ticks）
     * @param stay     停留时间（ticks）
     * @param fadeOut  淡出时间（ticks）
     * @throws ProtocolException 平台不支持时抛出 503
     */
    public void sendTitleComponent(C title, C subtitle, int fadeIn, int stay, int fadeOut)
            throws ProtocolException {
        throw ProtocolException.serviceUnavailable(ProtocolConstants.Message.TITLE_UNSUPPORTED, null);
    }

    /**
     * 向所有玩家发送 ActionBar（可选能力）
     *
     * <p>默认实现抛出 503，表示当前平台不支持 ActionBar。支持的平台覆盖本方法即可。
     *
     * @param component 平台组件
     * @throws ProtocolException 平台不支持时抛出 503
     */
    public void sendActionBarComponent(C component) throws ProtocolException {
        throw ProtocolException.serviceUnavailable(ProtocolConstants.Message.ACTIONBAR_UNSUPPORTED, null);
    }

    // ------------------------------------------------------------------
    // core 提供调用的方法
    // ------------------------------------------------------------------

    /**
     * 广播 JSON 组件给所有玩家
     *
     * @param json JSON 组件
     */
    public final void broadcast(JsonElement json) {
        C component = jsonToComponent(json);
        broadcast(component);
    }

    /**
     * 根据玩家昵称和 UUID 查找玩家对象
     *
     * <p>注意：
     * <ol>
     *     <li>优先使用 UUID 查找玩家对象，因为 UUID 是唯一标识符，而昵称可能会重复；</li>
     *     <li>如果昵称和 UUID 都为空，则返回 null；</li>
     *     <li>平台返回的 UUID 或昵称为 null 时跳过该玩家，不会抛 NPE。</li>
     * </ol>
     *
     * @param name 玩家昵称
     * @param uuid 玩家UUID
     * @return 玩家对象，未找到时返回 null
     */
    public final P findPlayer(String name, String uuid) {
        if ((name == null || name.isEmpty()) && (uuid == null || uuid.isEmpty())) {
            return null;
        }
        for (P player : getPlayers()) {
            UUID playerUuid = getPlayerUUID(player);
            if (playerUuid != null && playerUuid.toString().equals(uuid)) {
                return player;
            }
            String playerName = getPlayerName(player);
            if (playerName != null && playerName.equals(name)) {
                return player;
            }
        }
        return null;
    }

    /**
     * 发送消息给指定玩家
     *
     * <p>找不到玩家时静默忽略，不抛异常。需要区分"未找到"与"已发送"时，
     * 请使用 {@link #sendPrivateMessage(String, UUID, JsonElement)}。
     *
     * @param name 玩家昵称
     * @param uuid 玩家UUID
     * @param json 消息内容，JSON 格式
     */
    public final void sendMessage(String name, String uuid, JsonElement json) {
        P player = findPlayer(name, uuid);
        if (player != null) {
            C component = jsonToComponent(json);
            sendMessage(player, component);
        }
    }

    /**
     * 发送标题给所有玩家
     *
     * @param title    主标题，可为 null
     * @param subtitle 副标题，可为 null
     * @param fadeIn   淡入时间（ticks）
     * @param stay     停留时间（ticks）
     * @param fadeOut  淡出时间（ticks）
     * @throws ProtocolException 平台不支持标题时抛出 503
     */
    public final void sendTitle(JsonElement title, JsonElement subtitle,
                                int fadeIn, int stay, int fadeOut) throws ProtocolException {
        C titleComponent = title == null ? null : jsonToComponent(title);
        C subtitleComponent = subtitle == null ? null : jsonToComponent(subtitle);
        sendTitleComponent(titleComponent, subtitleComponent, fadeIn, stay, fadeOut);
    }

    /**
     * 发送 ActionBar 给所有玩家
     *
     * @param json ActionBar 内容，JSON 格式
     * @throws ProtocolException 平台不支持 ActionBar 时抛出 503
     */
    public final void sendActionBar(JsonElement json) throws ProtocolException {
        sendActionBarComponent(jsonToComponent(json));
    }

    /**
     * 发送私聊消息，并返回结果
     *
     * <p>查找玩家、构造响应、发送三步都在本方法内完成——只有平台侧认识玩家类型 P，
     * 因此这类"碰 P"的操作统一在本类内部收口，不向外暴露泛型。
     *
     * @param nickname 目标玩家昵称，可为 null
     * @param uuid     目标玩家 UUID，可为 null
     * @param json     消息内容，JSON 格式
     * @return 私聊结果：未找到玩家时为 {@code playerNotFound()}，成功时为 {@code sendSuccess(...)}
     */
    public final PrivateMessageResponse sendPrivateMessage(String nickname, UUID uuid, JsonElement json) {
        P player = findPlayer(nickname, uuid == null ? null : uuid.toString());
        if (player == null) {
            return PrivateMessageResponse.playerNotFound();
        }
        sendMessage(player, jsonToComponent(json));
        return PrivateMessageResponse.sendSuccess(new PlayerModel(getPlayerName(player), getPlayerUUID(player)));
    }

    /**
     * 判断命令源是否具有指定权限
     *
     * <p>入参为 {@link Object}：core 的命令层全程以 {@code Object} 传递命令源，
     * 真实类型由平台实现侧负责转换。
     *
     * @param source     命令源
     * @param permission 权限节点
     * @return 是否具有权限
     */
    @SuppressWarnings("unchecked")
    public final boolean checkPermission(Object source, String permission) {
        return doCheckPermission((CS) source, permission);
    }

    /**
     * 向命令源回执纯文本
     *
     * <p>文本会被包装为 {@code {"text": "..."}} 的 JSON 组件后交给平台。
     *
     * <p><b>空值语义</b>：{@code source} 为 null 时直接返回，不抛异常——
     * 与平台启动/关闭路径上"无命令源"的回执调用保持一致。
     *
     * @param source 命令源，可为 null
     * @param text   纯文本内容
     */
    @SuppressWarnings("unchecked")
    public final void returnCallBackMessage(Object source, String text) {
        if (source == null) {
            return;
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("text", text);
        returnCallBackMessage((CS) source, obj);
    }

    /**
     * 向命令源回执 JSON 组件
     *
     * @param source      命令源
     * @param jsonElement JSON 组件
     */
    public final void returnCallBackMessage(CS source, JsonElement jsonElement) {
        C component = jsonToComponent(jsonElement);
        returnCallBackMessage(source, component);
    }
}
