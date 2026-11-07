package io.github.theword.queqiao.core.rcon;

import io.github.theword.queqiao.core.exception.rcon.RconException;
import org.glavo.rcon.AuthenticationException;
import org.glavo.rcon.Rcon;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单条 RCON 连接的生命周期与命令串行化边界。
 *
 * <p>RCON 是请求—响应协议，同一条 socket 上不能让多个线程并发读写，因此命令必须串行执行。
 * 但"串行执行命令"与"及时释放连接"是<b>两件不同的事</b>，必须用两把不同的锁表达：
 *
 * <pre>
 * lifecycleLock（对象监视器）  仅保护状态：是否已停止 / 是否已有连接 / 是否正在连接 / 引用发布
 * commandLock  （ReentrantLock）仅保证同一条连接上同一时刻只有一个命令
 * </pre>
 *
 * <p><b>{@link #stop()} 绝不获取 {@code commandLock}</b>。否则，当命令线程正阻塞在
 * "等待服务器返回响应"上时，关闭操作会被迫排队等待该命令自然结束；而命令往往要等
 * Minecraft 主线程处理完才返回——主线程又在调用 {@code stop()}，于是形成死锁。
 * 现在 {@code stop()} 只做"摘引用 + 关闭底层 Socket"，在途命令随之中断（这是允许且预期的结果）。
 *
 * <p><b>停止是终态</b>：{@code stop()} 之后的实例不再接受连接与命令。需要重新连接时，
 * 由调用方（{@code QueQiaoRuntime}）创建新的 {@code RconClient} 实例。
 *
 * <p>连接建立过程中的 {@code stop()} 同样是安全的：连接在锁外建立，发布前会重新检查状态，
 * 迟到的连接不会发布到已停止的实例上，而是被立即中止。
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

    /**
     * 生命周期状态锁
     *
     * <p>只用于很短的状态操作：判断是否已停止 / 是否已有连接 / 是否正在连接、发布与移除连接。
     * <b>禁止</b>在持有该锁时进行网络连接、命令执行或任何 Socket 等待。
     */
    private final Object lifecycleLock = new Object();

    /**
     * 命令串行化锁
     *
     * <p>只保证同一条连接上同一时间只有一个命令。{@link #stop()} 不会获取它。
     */
    private final ReentrantLock commandLock = new ReentrantLock();

    /** 当前连接；由 {@link #lifecycleLock} 保护写，volatile 保证读可见性。 */
    private volatile Connection connection;

    /** 是否已停止（终态）。由 {@link #lifecycleLock} 保护。 */
    private boolean stopped;

    /** 是否正在建立连接。由 {@link #lifecycleLock} 保护。 */
    private boolean connecting;

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

    /**
     * 打开一条真实的 RCON 连接
     *
     * <p>连接失败时的清理使用 {@link Rcon#close()}：此时连接尚未发布给其它线程，
     * 不存在"已发布请求持有库内部锁"的竞争，走库的正常关闭路径是安全的。
     */
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
        return new RconConnection(rcon);
    }

    /**
     * 尝试连接 RCON。
     *
     * <p>幂等：已有连接或正在连接时不重复建立。连接失败只记录日志，客户端保持未连接状态，
     * 后续命令得到明确的 {@link RconException.Kind#DISCONNECTED}。
     *
     * <p>网络连接在锁外进行；如果期间实例已被 {@link #stop()}，连接结果不会发布，
     * 而是被立即中止。
     */
    public void connect() {
        synchronized (lifecycleLock) {
            if (stopped) {
                logger.warn("Rcon 客户端已停止，忽略连接请求");
                return;
            }
            if (connection != null) {
                logger.warn("Rcon 已连接，无需重复连接");
                return;
            }
            if (connecting) {
                logger.warn("Rcon 正在连接中，忽略重复连接请求");
                return;
            }
            connecting = true;
        }

        Connection opened;
        try {
            // 锁外建连：主机名解析、TCP 握手与认证都可能耗时，持锁等待会让 stop() 无法及时返回。
            opened = connectionFactory.connect(host, port, password);
        } catch (AuthenticationException e) {
            finishConnecting();
            logger.error("Rcon 认证失败，请检查配置项是否正确");
            return;
        } catch (IOException e) {
            finishConnecting();
            logger.warn("Rcon 连接失败：", e);
            return;
        } catch (RuntimeException e) {
            finishConnecting();
            throw e;
        }

        if (opened == null) {
            finishConnecting();
            throw new IllegalStateException("RCON connection factory 返回了 null");
        }

        if (publish(opened)) {
            logger.info("Rcon 连接成功！[host: {}, port: {}]", host, port);
        } else {
            // 连接期间实例已被 stop()，或已有其它线程发布了连接：
            // 迟到的连接必须立即释放，绝不允许它重新发布到已停止的实例上。
            abortQuietly(opened, null);
        }
    }

    /**
     * 执行命令
     *
     * <p>命令在 {@link #commandLock} 下串行执行；失败时只清理<b>仍然指向该连接</b>的引用，
     * 并中止该连接。锁的获取顺序固定为 commandLock → lifecycleLock。
     *
     * @param command RCON 命令
     * @return 服务器响应
     * @throws RconException 命令为空、超长、未连接或执行失败
     */
    public String sendCommand(String command) throws RconException {
        if (command == null || command.trim().isEmpty()) {
            throw RconException.invalidCommand();
        }
        if (command.getBytes(StandardCharsets.UTF_8).length > MAX_COMMAND_BYTES) {
            throw RconException.invalidCommand("Rcon 命令超过最大长度（" + MAX_COMMAND_BYTES + " 字节）");
        }

        commandLock.lock();
        try {
            Connection current = connection;
            if (current == null) {
                throw RconException.clientDisconnected();
            }
            try {
                return current.command(command);
            } catch (IOException | RuntimeException e) {
                // 只清除仍然指向失败连接的引用：迟到的异常不得清掉别处已发布的新连接。
                discard(current);
                abortQuietly(current, e);
                throw RconException.commandFailed(e);
            }
        } finally {
            commandLock.unlock();
        }
    }

    /**
     * 关闭连接（幂等）
     *
     * <p>目标是<b>快速中止</b>，不是优雅等待：
     * <ul>
     *     <li>不获取 {@link #commandLock}；</li>
     *     <li>不等待在途命令自然结束；</li>
     *     <li>不调用底层 {@code Rcon.close()} / {@code Rcon.disconnect()}；</li>
     *     <li>不在持有 {@link #lifecycleLock} 时关闭 Socket。</li>
     * </ul>
     *
     * <p>在途命令可能因 Socket 被关闭而失败，这是预期行为；命令不会自动重放
     * （RCON 命令可能有副作用，客户端无法判断服务器是否已执行）。
     */
    public void stop() {
        Connection previous;
        synchronized (lifecycleLock) {
            if (stopped) {
                return;
            }
            stopped = true;
            previous = connection;
            connection = null;
        }

        if (previous == null) {
            return;
        }

        logger.info("正在关闭 Rcon 客户端...");
        try {
            previous.abort();
            logger.info("Rcon 客户端资源已释放");
        } catch (Exception e) {
            logger.warn("Rcon 连接关闭失败：{}", e.getMessage(), e);
        }
    }

    /**
     * @return 当前是否持有连接
     */
    public boolean isConnected() {
        return connection != null;
    }

    /**
     * 结束"正在连接"标记
     */
    private void finishConnecting() {
        synchronized (lifecycleLock) {
            connecting = false;
        }
    }

    /**
     * 发布新连接
     *
     * @param opened 刚建立的连接
     * @return true 表示已发布；false 表示实例已停止或已有连接，调用方应中止该连接
     */
    private boolean publish(Connection opened) {
        synchronized (lifecycleLock) {
            connecting = false;
            if (stopped || connection != null) {
                return false;
            }
            connection = opened;
            return true;
        }
    }

    /**
     * 清除仍然指向失败连接的引用
     *
     * <p>identity 检查不可省略：旧连接的迟到异常不能清除已经被替换的新连接。
     *
     * @param failed 失败的连接
     */
    private void discard(Connection failed) {
        synchronized (lifecycleLock) {
            if (connection == failed) {
                connection = null;
            }
        }
    }

    /**
     * 中止连接并吞掉异常（关闭路径不应因重复关闭而失败）
     *
     * @param target 目标连接
     * @param cause  触发中止的原始异常；非 null 时把中止异常挂为 suppressed
     */
    private void abortQuietly(Connection target, Throwable cause) {
        try {
            target.abort();
        } catch (Exception abortError) {
            if (abortError instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (cause != null) {
                cause.addSuppressed(abortError);
            } else {
                logger.warn("Rcon 连接释放失败：{}", abortError.getMessage(), abortError);
            }
        }
    }

    interface ConnectionFactory {
        Connection connect(String host, int port, String password) throws AuthenticationException, IOException;
    }

    interface Connection {

        String command(String command) throws IOException;

        /**
         * 强制中止连接
         *
         * <p>实现必须直接关闭底层 Socket，<b>不得</b>走底层库的 {@code close()} /
         * {@code disconnect()}——那些方法会先获取库内部的锁，而正在等待响应的命令持有该锁。
         */
        void abort() throws IOException;
    }

    /**
     * {@link Rcon} 适配器
     */
    private static final class RconConnection implements Connection {

        private final Rcon rcon;

        private RconConnection(Rcon rcon) {
            this.rcon = rcon;
        }

        @Override
        public String command(String command) throws IOException {
            return rcon.command(command);
        }

        /**
         * 直接关闭底层 Socket
         *
         * <p>{@code org.glavo.rcon.Rcon} 的 {@code close()} / {@code disconnect()} 都会先
         * {@code lock.lock()}，而 {@code command()} 在等待响应期间正持有同一把锁——
         * 于是"关闭"会退化成"等待命令返回"，这正是死锁的来源。
         * 直接关闭 Socket 会让阻塞中的读立即抛异常，库在自己的异常路径上释放锁。
         *
         * <p>{@link Socket#close()} 本身幂等，重复中止是安全的。
         */
        @Override
        public void abort() throws IOException {
            Socket socket = rcon.getSocket();
            if (socket != null) {
                socket.close();
            }
        }
    }
}
