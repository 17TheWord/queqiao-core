package io.github.theword.queqiao.core.constant;

public final class ProtocolConstants {
    private ProtocolConstants() {
    }

    public static final class Api {
        public static final String BROADCAST = "broadcast";
        public static final String SEND_MSG = "send_msg";
        public static final String SEND_TITLE = "send_title";
        public static final String SEND_ACTIONBAR = "send_actionbar";
        public static final String SEND_PRIVATE_MSG = "send_private_msg";
        public static final String SEND_COMMAND = "send_command";
        public static final String SEND_RCON_COMMAND = "send_rcon_command";
        public static final String GET_STATUS = "get_status";

        private Api() {
        }
    }

    public static final class Message {
        public static final String FAILED = "failed";
        public static final String TITLE_AND_SUBTITLE_EMPTY = "Title and Subtitle cannot both be null";
        public static final String TITLE_DURATION_NEGATIVE = "Title 的 fade_in / stay / fade_out 不能为负数";
        public static final String TITLE_DURATION_TOO_LARGE = "Title 的 fade_in / stay / fade_out 超出允许上限（72000 ticks）";
        public static final String SEND_COMMAND_UNSUPPORTED = "send_command is not supported now";
        public static final String TITLE_UNSUPPORTED = "当前平台未实现标题功能";
        public static final String ACTIONBAR_UNSUPPORTED = "当前平台未实现 ActionBar 功能";
        public static final String PARSE_MESSAGE_FAILED = "解析消息失败";
        public static final String PARSE_DATA_FAILED = "解析请求数据失败";
        public static final String MISSING_API = "请求缺少 api 字段";
        public static final String RCON_COMMAND_EMPTY = "Rcon 命令不能为空";

        private Message() {
        }
    }

    public static final class Status {
        /**
         * 请求处理成功
         */
        public static final int SUCCESS = 200;
        public static final int BAD_REQUEST = 400;
        public static final int NOT_FOUND = 404;
        public static final int INTERNAL_ERROR = 500;
        /**
         * 服务暂不可用（如 Rcon 未启用 / 未连接）
         */
        public static final int SERVICE_UNAVAILABLE = 503;

        private Status() {
        }
    }
}
