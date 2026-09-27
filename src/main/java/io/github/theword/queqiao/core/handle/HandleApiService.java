package io.github.theword.queqiao.core.handle;

import io.github.theword.queqiao.core.response.PrivateMessageResponse;
import com.google.gson.JsonElement;

import java.util.UUID;

/**
 * 公共消息处理接口
 *
 * <p>服务端均需实现该接口
 */
public interface HandleApiService {

    /**
     * API: broadcast / send_msg
     *
     * @param jsonData Json消息
     */
    void handleBroadcastMessage(JsonElement jsonData);

    /**
     * API: send_title
     *
     * @param titlePayload    Title
     * @param subTitlePayload Subtitle
     * @param fadeIn          淡入时间(ticks)
     * @param stay            停留时间(ticks)
     * @param fadeOut         淡出时间(ticks)
     */
    void handleSendTitleMessage(JsonElement titlePayload, JsonElement subTitlePayload, int fadeIn, int stay, int fadeOut);

    /**
     * API: send_actionbar
     *
     * @param jsonData Json消息
     */
    void handleSendActionBarMessage(JsonElement jsonData);

    /**
     * API: send_private_msg
     *
     * @param nickname 目标玩家名
     * @param uuid     目标 UUID
     * @param jsonData Json消息
     * @return 私聊消息响应 {@link PrivateMessageResponse}
     * @deprecated 私聊已迁移到新的 API SPI：Core 负责"怎么找玩家 / 找不到返回什么 /
     *         找到后返回什么"，平台只需要实现
     *         {@code io.github.theword.queqiao.core.api.platform.PlayerProvider} 与
     *         {@code io.github.theword.queqiao.core.api.platform.PlayerMessageSender}。
     *         本方法仅为迁移期兼容保留，新的
     *         {@code io.github.theword.queqiao.core.api.standard.PrivateMessageApi}
     *         <b>不再</b>调用它，{@code SendPrivateMessageHandler} 也不再被注册。
     */
    @Deprecated
    PrivateMessageResponse handleSendPrivateMessage(String nickname, UUID uuid, JsonElement jsonData);
}
