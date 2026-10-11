package io.github.theword.queqiao.core.event.model.achievement;

import io.github.theword.queqiao.core.event.model.TranslateModel;
import io.github.theword.queqiao.core.translation.TranslationService;

/**
 * 成就事件的数据载体。
 *
 * <p>平台负责把原生组件解析为 {@link TranslateModel}，译文由
 * {@link TranslationService} 查询并格式化。本类不执行翻译。
 */
public class AchievementModel {

    /**
     * 成就唯一标识
     */
    private String key;

    /**
     * 成就展示信息
     */
    private DisplayModel display;

    /**
     * 成就消息的翻译模型，携带翻译键与参数。
     *
     * @see TranslateModel
     * @since 0.6.0
     */
    private TranslateModel translation;

    /**
     * 按框架类型拼接成就消息文本。
     *
     * @param frame    成就框架（goal、challenge、task、default）
     * @param nickname 玩家昵称
     * @param title    成就标题
     * @return 拼接后的文本
     * @deprecated 自 0.6.0 起弃用：硬编码拼接无法国际化。
     *             请改用 {@link #getTranslationKey(String)} 取翻译键，
     *             再由 {@link TranslationService} 查询译文。
     */
    @Deprecated
    public String pattern(String frame, String nickname, String title) {
        String result;
        switch (frame.toLowerCase()) {
            case "goal":
                result = " has reached the goal ";
                break;
            case "challenge":
                result = " has completed the challenge ";
                break;
            case "task":
                result = " has completed the task ";
                break;
            default:
                result = " has made the advancement ";
                break;
        }
        return nickname + result + title;
    }

    /**
     * 把成就框架映射为 Minecraft 翻译键，例如 {@code chat.type.advancement.goal}。
     *
     * @param frame 成就框架；为 null 或空字符串时按 {@code task} 处理
     * @return 翻译键
     * @since 0.6.0
     */
    public String getTranslationKey(String frame) {
        String type = (frame == null || frame.isEmpty()) ? "task" : frame.toLowerCase();
        return "chat.type.advancement." + type;
    }

    public AchievementModel() {
    }

    public AchievementModel(String key, DisplayModel display, TranslateModel translation) {
        this.key = key;
        this.display = display;
        this.translation = translation;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public DisplayModel getDisplay() {
        return display;
    }

    public void setDisplay(DisplayModel display) {
        this.display = display;
    }

    public TranslateModel getTranslation() {
        return translation;
    }

    public void setTranslation(TranslateModel translation) {
        this.translation = translation;
    }
}