package io.github.theword.queqiao.core.event.model.achievement;

import io.github.theword.queqiao.core.event.model.TranslateModel;
import io.github.theword.queqiao.core.translation.TranslationService;

/**
 * 成就模型
 */
public class AchievementModel {

    /**
     * 成就唯一标识
     *
     * <p>服务端支持：Fabric、Forge、Folia、NeoForge、Paper、Spigot
     */
    private String key;

    /**
     * 成就展示信息
     *
     * <p>服务端支持：Fabric、Forge、Folia、NeoForge、Paper
     */
    private DisplayModel display;

    /**
     * 成就消息的翻译模型。
     * <p>
     * 该属性包含了用于国际化显示的所有原始数据，包括翻译键（Translation Key）和参数列表。
     * 建议配合 {@link TranslationService TranslationService} 进行解析。
     * </p>
     * <p>服务端支持：Fabric, Forge, Folia, NeoForge, Paper</p>
     *
     * @see TranslateModel
     * @since 0.6.0
     */
    private TranslateModel translation;

    /**
     * 格式化成就消息
     * <p> frame 不能为空，需先对其进行 set
     *
     * @param frame    成就框架（goal、challenge、task、default）
     * @param nickname 玩家昵称
     * @param title    成就标题
     * @return 格式化后的成就消息
     * @deprecated 自 0.6.0 起弃用。硬编码拼接不利于国际化，
     *             请改用 {@link #getTranslationKey(String)} 获取翻译键，
     *             并配合 TranslationService 进行翻译。
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
     * 获取成就类型的国际化翻译键。
     * <p>
     * 该方法将成就框架映射为 Minecraft 标准的翻译键（例如：chat.type.advancement.goal）。
     *
     * @param frame 成就框架类型，如果为 null 或空字符串，则默认按照 "task" 处理。
     * @return 对应的翻译键字符串。
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