package io.github.theword.queqiao.core.api.platform;

import io.github.theword.queqiao.core.event.model.PlayerModel;
import com.google.gson.JsonElement;

/**
 * 平台私聊发送能力 SPI
 *
 * <p>职责被刻意压到最小：<b>"已经确定目标玩家之后，把消息发出去"</b>。
 *
 * <p>实现<b>不需要知道</b>：
 * <ul>
 *     <li>nickname 怎么查、uuid 怎么查；</li>
 *     <li>没找到目标玩家时该返回什么；</li>
 *     <li>这个 API 最终返回给客户端什么响应。</li>
 * </ul>
 * 以上全部由 Core 负责（见 {@code io.github.theword.queqiao.core.service.PrivateMessageService}）。
 *
 * <p><b>线程模型</b>：当前契约是<b>同步调用</b>，实现必须保证在被调用时
 * 所处的线程上下文中可以安全工作（平台适配器负责切换到正确的线程）。
 * 调度器/线程池抽象不在本阶段范围内。
 *
 * @since 0.7.0
 */
public interface PlayerMessageSender {

    /**
     * 向指定玩家发送私聊消息
     *
     * @param player  目标玩家（由 Core 查找后传入的 PlayerModel，平台无需再查一次）
     * @param message 消息内容
     */
    void sendPrivateMessage(PlayerModel player, JsonElement message);
}
