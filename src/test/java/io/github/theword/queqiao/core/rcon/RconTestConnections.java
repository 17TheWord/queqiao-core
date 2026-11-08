package io.github.theword.queqiao.core.rcon;

import org.slf4j.Logger;

import java.io.IOException;

/**
 * RCON 连接替身工厂
 *
 * <p>{@link RconClient.Connection} 是包级可见接口，只有同包的测试才能实现它。
 * 这里提供一个"中止必定失败"的连接，用于验证"关闭失败必须被上层发现"这条路径。
 */
public final class RconTestConnections {

    private RconTestConnections() {
    }

    /**
     * 构造一个已持有连接、但 {@code abort()} 必定失败的客户端
     *
     * @param logger   日志实现
     * @param host     主机
     * @param port     端口
     * @param password 密码
     * @return 已"连接"的客户端
     */
    public static RconClient clientWithFailingAbort(Logger logger, String host, int port, String password) {
        RconClient client = new RconClient(logger, host, port, password, (h, p, pw) -> new FailingConnection());
        client.connect();
        return client;
    }

    /**
     * 中止时抛异常的连接：模拟 Socket 关闭失败
     */
    private static final class FailingConnection implements RconClient.Connection {

        @Override
        public String command(String command) {
            return "ok";
        }

        @Override
        public void abort() throws IOException {
            throw new IOException("injected abort failure");
        }
    }
}
