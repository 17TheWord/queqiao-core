package io.github.theword.queqiao.core.service;

import io.github.theword.queqiao.core.api.platform.PlayerMessageSender;
import io.github.theword.queqiao.core.api.platform.PlayerProvider;
import io.github.theword.queqiao.core.event.model.PlayerModel;
import io.github.theword.queqiao.core.response.PrivateMessageResponse;
import com.google.gson.JsonElement;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 私聊业务流程
 *
 * <p>把"私聊"这件事里<b>属于 Core 的部分</b>集中到一处：
 * <pre>
 * send(nickname, uuid, message)
 *         │
 *         ▼
 * PlayerProvider.getOnlinePlayers()      ← 平台能力：怎么拿玩家
 *         │
 *         ▼
 * PlayerLookupService.find(...)          ← Core 逻辑：怎么找玩家
 *         │
 *    ┌────┴────┐
 *    │         │
 *  找不到     找到
 *    │         │
 *    ▼         ▼
 * playerNotFound()   PlayerMessageSender.sendPrivateMessage(...)   ← 平台能力：怎么发消息
 *                       │
 *                       ▼
 *                   sendSuccess(...)     ← Core 逻辑：返回什么
 * </pre>
 *
 * <p>因此 Paper / Fabric / Forge 只需要各自实现 {@link PlayerProvider} 与
 * {@link PlayerMessageSender}，而<b>不</b>需要重复实现查找、找不到的语义与成功响应。
 *
 * <p><b>查找规则</b>见 {@link PlayerLookupService}（先 UUID、后昵称）。
 *
 * <p><b>线程模型</b>：同步调用。本类自身无状态，可被多线程共享；
 * 平台实现内部的线程安全由平台适配器保证。
 *
 * @since 0.7.0
 */
public final class PrivateMessageService {

    private final PlayerProvider playerProvider;
    private final PlayerMessageSender messageSender;

    /**
     * 查找逻辑无状态，因此直接内部持有，不作为构造依赖暴露
     */
    private final PlayerLookupService playerLookupService = new PlayerLookupService();

    /**
     * 构造私聊服务
     *
     * @param playerProvider 平台玩家能力，不得为 null
     * @param messageSender  平台私聊发送能力，不得为 null
     */
    public PrivateMessageService(PlayerProvider playerProvider, PlayerMessageSender messageSender) {
        this.playerProvider = Objects.requireNonNull(playerProvider, "playerProvider");
        this.messageSender = Objects.requireNonNull(messageSender, "messageSender");
    }

    /**
     * 执行一次私聊
     *
     * <p>目标玩家由 Core 查找一次后<b>直接</b>传给 {@link PlayerMessageSender}，
     * 不会要求平台再查一次。
     *
     * @param nickname 目标昵称，允许为 null
     * @param uuid     目标 UUID，允许为 null
     * @param message  消息内容
     * @return 私聊响应：找到并发送成功为 {@link PrivateMessageResponse#sendSuccess(PlayerModel)}，
     *         未找到为 {@link PrivateMessageResponse#playerNotFound()}
     */
    public PrivateMessageResponse send(String nickname, UUID uuid, JsonElement message) {
        Collection<PlayerModel> players = playerProvider.getOnlinePlayers();

        Optional<PlayerModel> target = playerLookupService.find(players, uuid, nickname);
        if (target.isEmpty()) {
            return PrivateMessageResponse.playerNotFound();
        }

        PlayerModel player = target.get();
        messageSender.sendPrivateMessage(player, message);
        return PrivateMessageResponse.sendSuccess(player);
    }
}
