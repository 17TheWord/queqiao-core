package io.github.theword.queqiao.core.constant;

public class WebsocketConstantMessage {
    public static final String PARSE_MESSAGE_ERROR_ON_MESSAGE = "解析来自 {} 的 WebSocket 消息时出现异常";
    public static final String CLOSE_BY_RELOAD = "Websocket 正在重载";

    /**
     * 鹊桥整体关闭时使用的关闭原因
     *
     * <p><b>纯文本，不作为格式模板使用</b>——它会被直接作为关闭帧的原因下发给对端。
     * 此前这里误用了带 {@code {}} 占位符的模板，导致关闭原因里出现字面 {@code {}}。
     */
    public static final String SHUTDOWN = "鹊桥正在关闭";

    public static class Client {
        public static final String SEND_MESSAGE = "发送至 {} 的消息：{}";
        public static final String LAUNCHING = "WebSocket Client 正在启动...";
        public static final String CONNECT_SUCCESSFUL = "已成功连接至 {} 的 WebSocket 服务器！";
        public static final String CLEAR_WEBSOCKET_CLIENT_LIST = "已清空 Websocket Client 列表。";
        public static final String RELOADING = "Websocket Client 正在重载";
        public static final String RELOADED = "Websocket Client 重载完毕";
        public static final String MANUAL_RECONNECTING = "正在手动重新连接至 {} 的 WebSocket 服务器...";
        private static final String CONNECT_TO = "连接至：{} 的 ";
        public static final String CLOSING_CONNECTION = CONNECT_TO + "WebSocket Client 正在关闭，Code {}，Reason：{}。";
        public static final String RECONNECTING = CONNECT_TO + "WebSocket 连接已断开，尝试第 {} 次重连...";
        public static final String CONNECTION_ERROR = CONNECT_TO + "WebSocket 连接出现异常：{}";
        public static final String CONNECTION_NOT_OPEN = CONNECT_TO + "WebSocket 连接未打开";
        public static final String SEND_MESSAGE_FAILED = CONNECTION_NOT_OPEN + "，发送消息 {} 失败";
        public static final String MESSAGE_HANDLE_FAILED = CONNECT_TO + "处理收到的消息时发生异常，已忽略该消息";
        public static final String URI_SYNTAX_ERROR = CONNECT_TO + "WebSocket URL 格式错误，无法连接！";
        public static final String MAX_RECONNECT_ATTEMPTS_REACHED = CONNECT_TO + "重连次数达到最大值，将不再自动重连，请使用命令手动重连！";

        /**
         * 已停止的客户端在重连竞态下建立了连接时的提示
         *
         * <p>参数：URI。此时连接会被立即关闭，不进入业务处理。
         */
        public static final String STOPPED_DISCARD_CONNECTION = CONNECT_TO + "WebSocket Client 已停止，丢弃竞态中建立的连接。";
    }

    public static class Server {
        public static final String ERROR_ON_STOPPING = "Websocket Server 正在启动时出现异常。";

        /**
         * 停止超时提示
         *
         * <p>参数：等待上限（毫秒）。语义刻意区分"已停止"与"未能确认停止"：
         * 超时后会中断 selector 线程，但线程若仍未退出，监听端口可能尚未释放。
         */
        public static final String STOP_TIMEOUT =
                "Websocket Server 未在 {} ms 内确认停止，已中断 selector 线程；若其仍未退出，端口可能仍在监听。";

        /**
         * 停止未确认时跳过启动新 Server 的提示
         *
         * <p>此时端口可能仍被旧实例占用，直接 bind 只会静默失败，
         * 因此保留旧实例并等待下一次 reload / 停止重试。
         */
        public static final String RESTART_SKIPPED =
                "旧 Websocket Server 尚未确认停止，本次不启动新的 Server；请稍后重试。";

        /**
         * 启动阶段清理"已确认终止"的残留实例时使用的原因
         *
         * <p>服务端级异常回调不再清空引用，因此再次启动时需要先确认旧实例已终止，
         * 才能清掉引用并重新绑定端口。
         */
        public static final String STALE_SERVER_CLEANED = "Websocket Server 实例已确认终止，清理残留引用";

        public static final String BROADCAST_MESSAGE = "向所有客户端广播消息：{}";
        public static final String RELOADING = "Websocket Server 正在重载";
        public static final String RELOADED = "Websocket Server 重载完毕";
        public static final String SERVER_STARTING = "WebSocket Server 在 {}:{} 启动...";
        private static final String CLIENT_PREFIX = "来自：{} 的 ";
        public static final String MISSING_SERVER_NAME_HEADER = CLIENT_PREFIX + "连接请求头中缺少服务器名，将断开连接";
        public static final String SERVER_NAME_DECODE_FAILED_HEADER = CLIENT_PREFIX + "连接请求头中服务器名：{} 解码失败，将断开连接，错误信息：{}";
        public static final String INVALID_CLIENT_ORIGIN_HEADER = CLIENT_PREFIX + "连接请求头中客户端来源错误，将断开连接";
        public static final String SERVER_NAME_PARSE_FAILED_HEADER = CLIENT_PREFIX + "连接请求头中服务器名解析失败，将断开连接";
        public static final String INVALID_SERVER_NAME_HEADER = CLIENT_PREFIX + "连接请求头中服务器名：{} 错误，将断开连接";
        /**
         * 认证失败日志。
         *
         * <p>安全要求：绝不记录客户端提交的 Authorization / accessToken / Bearer token 内容，
         * 只记录客户端地址。
         */
        public static final String INVALID_ACCESS_TOKEN_HEADER = CLIENT_PREFIX + "连接认证失败（Authorization 校验未通过），将断开连接";
        public static final String CLIENT_CONNECTED = CLIENT_PREFIX + "客户端已连接";
        public static final String CLIENT_DISCONNECTED = CLIENT_PREFIX + "客户端已断开";
        public static final String CLIENT_HAD_BEEN_DISCONNECTED = CLIENT_PREFIX + "客户端已被断开";
        public static final String CONNECTION_ERROR = CLIENT_PREFIX + "WebSocket 连接出现异常：{}";
    }
}
