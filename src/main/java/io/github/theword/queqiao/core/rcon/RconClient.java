package io.github.theword.queqiao.core.rcon;

import io.github.theword.queqiao.core.exception.rcon.RconException;
import org.glavo.rcon.AuthenticationException;
import org.glavo.rcon.Rcon;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 单条 RCON 连接的生命周期与命令串行化边界。
 *
 * <p>RCON 使用请求—响应协议，不能让多个线程并发读写同一个 socket。命令、连接和关闭
 * 由同一个对象监视器串行化；关闭等待当前命令完成，关闭完成后的命令得到明确的断开错误。
 */
public class RconClient {

    /** TCP 连接超时及 Socket 单次读等待超时；这是空闲超时，不是整个命令的总耗时上限。 */
    static final int SOCKET_TIMEOUT_MILLIS = 10_000;

    /**
     * 默认 Rcon 服务器地址
     *
     * <p>使用 IPv4 回环地址而不是 {@code localhost}：部分系统会把 {@code localhost}
     * 优先解析为 {@code ::1}，而 Minecraft 的 RCON 通常只监听 IPv4 回环地址。
     */
    static final String DEFAULT_HOST = "127.0.0.1";

    /** Minecraft RCON EXEC_COMMAND 数据包可承载的最大命令字节数。 */
    private static final int MAX_COMMAND_BYTES = 1446;

    private final Logger logger;
    private final String host;
    private final int port;
    private final String password;
    private final ConnectionFactory connectionFactory;

    /** 仅由本实例同步方法访问。 */
    private Connection connection;

    /**
     * 使用默认地址（{@link #DEFAULT_HOST}）构造客户端
     *
     * @param logger   日志实现
     * @param port     Rcon 端口
     * @param password Rcon 密码
     */
    public RconClient(Logger logger, int port, String password) {
        this(logger, DEFAULT_HOST, port, password);
    }

    /**
     * 构造客户端
     *
     * <p>Host 在构造时确定，实例内部不提供 Setter —— 配置变化时由 Runtime 创建新的
     * {@code RconClient} 并替换引用，避免"连接已建立却悄悄换了目标地址"。
     *
     * @param logger   日志实现
     * @param host     Rcon 服务器地址（主机名 / IPv4 / IPv6 / 域名）
     * @param port     Rcon 端口
     * @param password Rcon 密码
     */
    public RconClient(Logger logger, String host, int port, String password) {
        this(logger, host, port, password, RconClient::openConnection);
    }

    RconClient(Logger logger, String host, int port, String password, ConnectionFactory connectionFactory) {
        if (logger == null) {
            throw new IllegalArgumentException("logger 不能为 null");
        }
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("host 不能为空白");
        }
        if (connectionFactory == null) {
            throw new IllegalArgumentException("connectionFactory 不能为 null");
        }
        this.logger = logger;
        this.host = host;
        this.port = port;
        this.password = password == null ? "" : password;
        this.connectionFactory = connectionFactory;
    }

    private static Connection openConnection(String host, int port, String password)
            throws AuthenticationException, IOException {
        Rcon rcon = new Rcon();
        rcon.setTimeout(SOCKET_TIMEOUT_MILLIS);
        try {
            rcon.connect(host, port, password.getBytes(StandardCharsets.UTF_8));
        } catch (AuthenticationException | IOException e) {
            try {
                if (rcon.getSocket() != null) {
                    rcon.close();
                }
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
        return new Connection() {
            @Override
            public String command(String command) throws IOException {
                return rcon.command(command);
            }

            @Override
            public void close() throws Exception {
                rcon.close();
            }
        };
    }

    /** 尝试连接 RCON；连接已存在时保持幂等。 */
    public synchronized void connect() {
        if (connection != null) {
            logger.warn("Rcon 已连接，无需重复连接");
            return;
        }
        try {
            connection = connectionFactory.connect(host, port, password);
            if (connection == null) {
                throw new IllegalStateException("RCON connection factory 返回了 null");
            }
            logger.info("Rcon 连接成功！[host: {}, port: {}]", host, port);
        } catch (AuthenticationException e) {
            logger.error("Rcon 认证失败，请检查配置项是否正确");
        } catch (IOException e) {
            logger.warn("Rcon 连接失败：", e);
        }
    }

    /**
     * 在检查连接状态与发送命令的整个期间持有监视器，避免 stop/connect 与命令交错。
     */
    public synchronized String sendCommand(String command) throws RconException {
        if (command == null || command.trim().isEmpty()) {
            throw RconException.invalidCommand();
        }
        if (command.getBytes(StandardCharsets.UTF_8).length > MAX_COMMAND_BYTES) {
            throw RconException.invalidCommand("Rcon 命令超过最大长度（" + MAX_COMMAND_BYTES + " 字节）");
        }
        Connection current = connection;
        if (current == null) {
            throw RconException.clientDisconnected();
        }
        try {
            return current.command(command);
        } catch (IOException | RuntimeException e) {
            connection = null;
            try {
                current.close();
            } catch (Exception closeError) {
                if (closeError instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                e.addSuppressed(closeError);
            }
            throw RconException.commandFailed(e);
        }
    }

    /** 关闭连接；与命令和连接建立串行执行，重复调用安全。 */
    public synchronized void stop() {
        Connection current = connection;
        if (current == null) {
            return;
        }

        // 在关闭过程中本实例已不再接受新命令；同步方法保证其它操作等关闭结束后再进入。
        connection = null;
        logger.info("正在关闭 Rcon 客户端...");
        try {
            current.close();
            logger.info("Rcon 客户端资源已释放");
        } catch (Exception e) {
            logger.warn("Rcon 连接关闭失败：{}", e.getMessage(), e);
        }
    }

    public synchronized boolean isConnected() {
        return connection != null;
    }

    interface ConnectionFactory {
        Connection connect(String host, int port, String password) throws AuthenticationException, IOException;
    }

    interface Connection {
        String command(String command) throws IOException;

        void close() throws Exception;
    }
}
