package io.github.theword.queqiao.core.event.model;

/**
 * 翻译组件的数据载体：翻译键、递归参数与最终文本。
 *
 * <p>对应 Minecraft 的 Translation Component 结构。三个字段都是可选的 ——
 * 只提供原始显示文本的平台可以只填 {@link #text}。
 *
 * @since 0.6.0
 */
public class TranslateModel {

    /**
     * 翻译键，例如 {@code death.attack.player}。
     */
    private String key;

    /**
     * 翻译键的参数列表，元素本身也可以带翻译键（递归嵌套）。
     */
    private TranslateModel[] args;

    /**
     * 最终显示文本。
     *
     * <p>翻译成功时为格式化后的译文；翻译未命中或翻译关闭时，为平台提供的原始显示文本。
     * 平台能解析出结构时同时保留 {@link #key} 与 {@link #args}；纯文本模型允许二者为空。
     */
    private String text;

    /**
     * 供序列化框架（Gson）使用。
     */
    public TranslateModel() {
    }

    /**
     * @param key  翻译键
     * @param args 翻译参数数组
     * @param text 最终显示文本
     */
    public TranslateModel(String key, TranslateModel[] args, String text) {
        this.key = key;
        this.args = args;
        this.text = text;
    }

    /**
     * 将任意值转换为纯文本翻译模型。
     *
     * @param value 原始值
     * @return 不包含翻译键和参数的模型
     */
    public static TranslateModel of(Object value) {
        return new TranslateModel(null, null, String.valueOf(value));
    }

    /**
     * @return 翻译键
     */
    public String getKey() {
        return key;
    }

    /**
     * @param key 翻译键
     */
    public void setKey(String key) {
        this.key = key;
    }

    /**
     * @return 翻译参数数组，可能为 null
     */
    public TranslateModel[] getArgs() {
        return args;
    }

    /**
     * @param args 翻译参数数组
     */
    public void setArgs(TranslateModel[] args) {
        this.args = args;
    }

    /**
     * @return 最终显示文本
     */
    public String getText() {
        return text;
    }

    /**
     * @param text 最终显示文本
     */
    public void setText(String text) {
        this.text = text;
    }

    /**
     * @return 是否带有翻译参数
     */
    public boolean hasArgs() {
        return args != null && args.length > 0;
    }
}