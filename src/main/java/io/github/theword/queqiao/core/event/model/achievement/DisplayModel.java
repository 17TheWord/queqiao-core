package io.github.theword.queqiao.core.event.model.achievement;

import io.github.theword.queqiao.core.event.model.TranslateModel;

/**
 * 成就展示信息的数据载体。
 */
public class DisplayModel {

    /**
     * 标题。译文由服务端生成后写入，不是客户端按语言环境动态渲染的结果。
     *
     * @since 0.6.0
     */
    private TranslateModel title;

    /**
     * 描述文本。译文由服务端生成后写入，不是客户端按语言环境动态渲染的结果。
     *
     * @since 0.6.0
     */
    private TranslateModel description;

    /**
     * 展示信息类型
     */
    private String frame;

    public TranslateModel getDescription() {
        return description;
    }

    public void setDescription(TranslateModel value) {
        this.description = value;
    }

    public String getFrame() {
        return frame;
    }

    public void setFrame(String value) {
        this.frame = value.toLowerCase();
    }

    public TranslateModel getTitle() {
        return title;
    }

    public void setTitle(TranslateModel value) {
        this.title = value;
    }

}