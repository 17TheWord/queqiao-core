package io.github.theword.queqiao.core.protocol.handler;

import io.github.theword.queqiao.core.handle.HandleApiService;
import io.github.theword.queqiao.core.payload.MessagePayload;
import io.github.theword.queqiao.core.protocol.AbstractProtocolHandler;
import org.slf4j.Logger;

/**
 * 广播处理器（迁移期遗留）
 *
 * <p><b>已不再被 {@code ProtocolRouter} 注册</b>：{@code broadcast} 与 {@code send_msg}
 * 已迁移到 {@link io.github.theword.queqiao.core.api.standard.BroadcastApi}，
 * 由平台提供 {@link io.github.theword.queqiao.core.api.platform.BroadcastService} 并自行注册
 * （未注册时返回 404）。
 *
 * <p>保留本类是为了保留迁移痕迹、避免把"兼容性清理"和"API SPI 实验"绑在一起。
 *
 * @deprecated 使用 {@code BroadcastApi} + {@code BroadcastService} 代替。
 */
@Deprecated
public class BroadcastHandler extends AbstractProtocolHandler<MessagePayload, Void> {
    public BroadcastHandler(String apiName, Logger logger, HandleApiService handleApiService) {
        super(apiName, logger, handleApiService, MessagePayload.class);
    }

    @Override
    protected Void handlePayload(MessagePayload payload) {
        this.handleApiService.handleBroadcastMessage(payload.getMessage());
        return null;
    }
}
