package io.github.theword.queqiao.core.constant;

/**
 * 命令相关的常量消息
 *
 * <p>包含命令执行过程中用于提示或日志输出的静态字符串。
 */
public class CommandConstant {
    public static final String RELOAD_CONFIG = "加载配置文件完成";

    /**
     * 运行时尚未就绪（未创建 / 未启动 / 已关闭）时，依赖运行状态的命令的统一回执
     *
     * <p>命令树允许在 Runtime 启动之前构建，因此命令执行时依赖可能尚不存在；
     * 此时各命令回执本消息并直接返回，而不是抛异常。
     */
    public static final String RUNTIME_NOT_READY = "鹊桥尚未启动。";

    public static final String RECONNECT_MESSAGE = "正在尝试重连 {} 的 WebSocket 客户端...";
    public static final String RECONNECT_NOT_OPEN_CLIENT = "正在重连未打开的 Websocket Client...";
    public static final String RECONNECT_ALL_CLIENT = "正在重连所有 Websocket Client...";
    public static final String RECONNECT_NO_CLIENT_NEED_RECONNECT = "没有客户端需要重连";

    /**
     * 重连命令的结束提示
     *
     * <p>措辞为"已安排"而非"已重新连接"：重连命令只是把任务<b>投递到调度器</b>，
     * 并不保证连接成功，真正的结果由 {@code WsClient} 记录到日志。
     */
    public static final String RECONNECTED = "已安排重连，实际结果请查看日志";

    /**
     * Mod端权限等级
     *
     * <p>2: OP
     */
    public static final int MOD_PERMISSION_LEVEL = 2;

    /**
     * 命令执行成功信号
     */
    public static final int SUCCESS_SIGNAL = 1;

    /**
     * 命令执行失败信号
     */
    public static final int FAIL_SIGNAL = 0;
}
