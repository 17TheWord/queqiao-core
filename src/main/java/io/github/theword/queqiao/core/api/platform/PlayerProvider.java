package io.github.theword.queqiao.core.api.platform;

import io.github.theword.queqiao.core.event.model.PlayerModel;

import java.util.Collection;

/**
 * 平台玩家能力 SPI
 *
 * <p>平台只负责回答一个问题：<b>当前在线玩家有哪些</b>。
 * 它<b>不</b>负责"怎么找某个玩家"——查找规则属于 Core（见
 * {@code io.github.theword.queqiao.core.service.PlayerLookupService}），
 * 因此各平台不再重复实现 UUID / 昵称的遍历与比对。
 *
 * <p><b>返回的是 Core 的快照 DTO</b>：{@link PlayerModel}，而不是 Bukkit / Fabric / Forge
 * 的平台玩家对象。实现<b>必须</b>返回可以安全供调用者遍历的快照，
 * <b>不得</b>直接暴露平台内部的可变玩家集合（否则遍历期间集合被并发修改，
 * 会以平台相关的、难以复现的方式失败）。
 *
 * <p><b>线程模型</b>：当前契约是<b>同步调用</b>。实现必须保证在被调用时
 * 所处的线程上下文中可以安全工作（平台适配器负责切换到正确的线程）。
 * 调度器/线程池抽象不在本阶段范围内。
 *
 * @since 0.7.0
 */
public interface PlayerProvider {

    /**
     * 获取在线玩家快照
     *
     * @return 在线玩家快照；无玩家时返回空集合，不得返回 null
     */
    Collection<PlayerModel> getOnlinePlayers();
}
