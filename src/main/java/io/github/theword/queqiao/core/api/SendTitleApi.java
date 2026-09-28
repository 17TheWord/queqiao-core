package io.github.theword.queqiao.core.api;

import java.util.Objects;

import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.exception.protocol.ProtocolException;
import io.github.theword.queqiao.core.payload.TitlePayload;
import io.github.theword.queqiao.core.platform.AbstractPlatformContext;
import org.slf4j.Logger;

/**
 * 发送标题
 *
 * <p>协议名：{@code send_title}。
 *
 * <p>平台未实现标题时，{@code AbstractPlatformContext#sendTitleComponent} 会抛出 503。
 */
public final class SendTitleApi extends Api<TitlePayload, Void> {

    /**
     * Title 时间参数上限（ticks）
     *
     * <p>20 ticks = 1 秒，这里取 72000 ticks（1 小时）作为防御上限，
     * 防止异常大的值进入平台实现导致 title 长时间不消失。
     */
    private static final int MAX_TITLE_DURATION_TICKS = 20 * 60 * 60;

    private final AbstractPlatformContext<?, ?, ?, ?> platformContext;
    private final Logger logger;

    public SendTitleApi(AbstractPlatformContext<?, ?, ?, ?> platformContext, Logger logger) {
        super(TitlePayload.class);
        this.platformContext = Objects.requireNonNull(platformContext, "platformContext");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String name() {
        return ProtocolConstants.Api.SEND_TITLE;
    }

    @Override
    protected Void doExecute(TitlePayload payload) throws ProtocolException {
        if ((payload.getTitle() == null || payload.getTitle().isJsonNull())
                && (payload.getSubtitle() == null || payload.getSubtitle().isJsonNull())) {
            throw ProtocolException.badRequest(ProtocolConstants.Message.TITLE_AND_SUBTITLE_EMPTY);
        }
        validateDuration(payload.getFadeIn(), "fade_in");
        validateDuration(payload.getStay(), "stay");
        validateDuration(payload.getFadeOut(), "fade_out");

        platformContext.sendTitle(payload.getTitle(), payload.getSubtitle(),
                payload.getFadeIn(), payload.getStay(), payload.getFadeOut());
        return null;
    }

    /**
     * 校验单个时间参数
     *
     * @param ticks     ticks 值
     * @param fieldName 字段名，用于日志
     * @throws ProtocolException 值非法时抛出 400
     */
    private void validateDuration(int ticks, String fieldName) throws ProtocolException {
        if (ticks < 0) {
            this.logger.warn("Title 的 {} 为负数（{}），已拒绝", fieldName, ticks);
            throw ProtocolException.badRequest(ProtocolConstants.Message.TITLE_DURATION_NEGATIVE);
        }
        if (ticks > MAX_TITLE_DURATION_TICKS) {
            this.logger.warn("Title 的 {} 超出上限（{} > {}），已拒绝",
                    fieldName, ticks, MAX_TITLE_DURATION_TICKS);
            throw ProtocolException.badRequest(ProtocolConstants.Message.TITLE_DURATION_TOO_LARGE);
        }
    }
}
