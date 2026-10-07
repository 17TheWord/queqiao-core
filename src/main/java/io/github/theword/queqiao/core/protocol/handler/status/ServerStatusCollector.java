package io.github.theword.queqiao.core.protocol.handler.status;

import io.github.theword.queqiao.core.constant.BaseConstant;
import io.github.theword.queqiao.core.exception.status.MinecraftPingException;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 服务器状态采集器
 *
 * <p><b>Runtime 实例级</b>：本类由 {@code QueQiaoRuntime} 持有并管理生命周期，
 * 所有可变状态（快照缓存、探测目标、刷新线程池与任务、指标采集器、日志实现）
 * 都是<b>实例字段</b>，不再存在 JVM 级静态状态。
 * 因此同一个 JVM 中并存的两个 Runtime 各自拥有独立的采集器，互不干扰。
 *
 * <p>采集算法（Ping 与缓存策略）保持不变，本次只把静态生命周期改为实例生命周期。
 */
public final class ServerStatusCollector {
    private static final int DEFAULT_SERVER_PORT = 25565;
    private static final String DEFAULT_SERVER_HOST = "127.0.0.1";
    private static final String SERVER_PROPERTIES_FILE = "server.properties";
    private static final String REGEX_CONFIG_FILE = "regex.yml";
    private static final String CONFIG_DIRECTORY = "config";
    private static final String LOGS_DIRECTORY = "logs";
    private static final String LOG_PATH_KEY = "log_path";
    private static final String SERVER_IP_KEY = "server-ip";
    private static final String SERVER_PORT_KEY = "server-port";
    private static final String PING_REASON_NOT_CONFIGURED = "not_configured";
    private static final String PING_REASON_OK = "ok";
    private static final String PING_REASON_TIMEOUT = "timeout";
    private static final String PING_REASON_OFFLINE = "offline";
    private static final String PING_REASON_ERROR = "error";
    private static final int MIN_REFRESH_INTERVAL_SECONDS = 5;
    private static final int DEFAULT_REFRESH_INTERVAL_SECONDS = 10;
    private static final long INITIAL_SNAPSHOT_WAIT_MILLIS = 7000L;

    private static final Path[] REGEX_CONFIG_CANDIDATES = new Path[]{
            Paths.get(CONFIG_DIRECTORY, BaseConstant.MODULE_NAME, REGEX_CONFIG_FILE),
            Paths.get(BaseConstant.MODULE_NAME, REGEX_CONFIG_FILE)
    };

    /**
     * 无状态的 Ping 客户端，可安全地在所有实例间共享
     */
    private static final MinecraftPingClient PING_CLIENT = new MinecraftPingClient();

    /** 刷新调度器缺席时（尚未启动或已停止）的同步回退缓存有效期。 */
    private static final long SNAPSHOT_CACHE_TTL_MILLIS = 2000L;

    /**
     * 服务端类型提供者
     *
     * <p>用 Supplier 而非 String，是为了支持<b>延迟获取</b>：平台可能在本实例构造时
     * 还没拿到服务端实例，只有启动完成后 {@code getServerType()} 才可用。
     * 每次构建快照时现取，避免把"构造瞬间的值"固化下来。
     */
    private final Supplier<String> serverTypeSupplier;

    /**
     * 服务端版本提供者，语义同 {@link #serverTypeSupplier}
     */
    private final Supplier<String> serverVersionSupplier;

    private final Logger logger;

    /** 串行化本实例对采集目标、缓存与线程池的变更。 */
    private final Object lifecycleLock = new Object();

    /** 探测目标版本号计数器，每次目标变更递增，用于丢弃过期采集结果。 */
    private final AtomicLong targetVersion = new AtomicLong();

    private volatile SystemMetricsCollector metrics;
    private volatile SnapshotCache snapshotCache;
    private volatile PingTarget pingTarget;
    private volatile ScheduledThreadPoolExecutor refreshExecutor;
    private volatile ScheduledFuture<?> refreshTask;
    private volatile int refreshIntervalSeconds = DEFAULT_REFRESH_INTERVAL_SECONDS;
    private volatile SnapshotWaiter snapshotWaiter;

    /**
     * 构造状态采集器
     *
     * @param serverTypeSupplier    服务端类型提供者，允许为 null；返回值允许为 null（未指定）
     * @param serverVersionSupplier 服务端版本提供者，允许为 null；返回值允许为 null（未指定）
     * @param logger                日志实现，不得为 null
     */
    public ServerStatusCollector(
            Supplier<String> serverTypeSupplier, Supplier<String> serverVersionSupplier, Logger logger) {
        this.serverTypeSupplier = serverTypeSupplier;
        this.serverVersionSupplier = serverVersionSupplier;
        this.logger = Objects.requireNonNull(logger, "logger");
        this.metrics = new SystemMetricsCollector(null);
        this.pingTarget = unavailableTarget(DEFAULT_SERVER_HOST, DEFAULT_SERVER_PORT);
        this.snapshotWaiter = new SnapshotWaiter(this.pingTarget);
    }

    private static final class PingTarget {
        private final String host;
        private final int port;
        private final boolean available;
        private final long version;

        private PingTarget(String host, int port, boolean available, long version) {
            this.host = host;
            this.port = port;
            this.available = available;
            this.version = version;
        }

        private boolean sameEndpoint(String otherHost, int otherPort, boolean otherAvailable) {
            return port == otherPort && available == otherAvailable && host.equals(otherHost);
        }
    }

    /**
     * 启动状态快照定时采集。Runtime 启动完成后调用；重复调用只重设现有任务。
     */
    public void startRefreshScheduler(int intervalSeconds) {
        synchronized (lifecycleLock) {
            refreshIntervalSeconds = Math.max(MIN_REFRESH_INTERVAL_SECONDS, intervalSeconds);
            if (refreshExecutor == null || refreshExecutor.isShutdown()) {
                ThreadFactory threadFactory = runnable -> {
                    Thread thread = new Thread(runnable, "QueQiao-Status-Collector");
                    thread.setDaemon(true);
                    return thread;
                };
                refreshExecutor = new ScheduledThreadPoolExecutor(1, threadFactory);
                refreshExecutor.setRemoveOnCancelPolicy(true);
                metrics = new SystemMetricsCollector(logger);
                snapshotCache = null;
                snapshotWaiter = new SnapshotWaiter(pingTarget);
            }
            scheduleRefreshLocked(0L);
        }
    }

    /** 更新间隔；未变化时不打断当前采集周期。 */
    public void updateRefreshInterval(int intervalSeconds) {
        synchronized (lifecycleLock) {
            int normalizedInterval = Math.max(MIN_REFRESH_INTERVAL_SECONDS, intervalSeconds);
            if (refreshIntervalSeconds == normalizedInterval) {
                return;
            }
            refreshIntervalSeconds = normalizedInterval;
            if (refreshExecutor != null && !refreshExecutor.isShutdown()) {
                long initialDelay = snapshotCache == null ? 0L : normalizedInterval;
                scheduleRefreshLocked(initialDelay);
            }
        }
    }

    /** 关闭本实例持有的状态采集任务与线程。 */
    public void stopRefreshScheduler() {
        synchronized (lifecycleLock) {
            SnapshotWaiter waiter = snapshotWaiter;
            waiter.result.completeExceptionally(new IllegalStateException("状态采集器已停止"));
            ScheduledFuture<?> task = refreshTask;
            refreshTask = null;
            if (task != null) {
                task.cancel(true);
            }
            ScheduledThreadPoolExecutor executor = refreshExecutor;
            refreshExecutor = null;
            if (executor != null) {
                executor.shutdownNow();
            }
            snapshotCache = null;
            metrics = new SystemMetricsCollector(null);
            snapshotWaiter = new SnapshotWaiter(pingTarget);
        }
    }

    private void scheduleRefreshLocked(long initialDelaySeconds) {
        ScheduledFuture<?> current = refreshTask;
        if (current != null) {
            current.cancel(false);
        }
        ScheduledThreadPoolExecutor executor = refreshExecutor;
        if (executor != null && !executor.isShutdown()) {
            refreshTask = executor.scheduleWithFixedDelay(
                    this::refreshAndPublish,
                    initialDelaySeconds,
                    refreshIntervalSeconds,
                    TimeUnit.SECONDS);
        }
    }

    /**
     * 重新解析工作目录下的 {@code server.properties} 并刷新探测目标。
     */
    public void initPingTarget() {
        Path workingDirectory = Paths.get("").toAbsolutePath().normalize();
        Path serverPropertiesPath = resolveServerPropertiesPath(workingDirectory);
        initPingTarget(serverPropertiesPath);
    }

    private Path resolveServerPropertiesPath(Path workingDirectory) {
        Path absoluteWorkingDirectory = workingDirectory.toAbsolutePath().normalize();
        for (Path relativeRegexPath : REGEX_CONFIG_CANDIDATES) {
            Path regexConfigPath = absoluteWorkingDirectory.resolve(relativeRegexPath).normalize();
            if (!Files.isRegularFile(regexConfigPath)) {
                continue;
            }

            String logPath = readLogPath(regexConfigPath);
            if (logPath == null || logPath.trim().isEmpty()) {
                continue;
            }

            Path serverRoot = inferServerRoot(regexConfigPath, logPath, absoluteWorkingDirectory);
            if (serverRoot == null) {
                continue;
            }

            return serverRoot.resolve(SERVER_PROPERTIES_FILE).normalize();
        }

        return absoluteWorkingDirectory.resolve(SERVER_PROPERTIES_FILE).normalize();
    }

    private String readLogPath(Path regexConfigPath) {
        try (InputStream inputStream = Files.newInputStream(regexConfigPath)) {
            Yaml yaml = new Yaml();
            Object yamlObject = yaml.load(inputStream);
            if (!(yamlObject instanceof Map)) {
                logger.warn("regex.yml 内容不是 Map 结构，无法读取 log_path：{}", regexConfigPath);
                return null;
            }

            Object logPathObject = ((Map<?, ?>) yamlObject).get(LOG_PATH_KEY);
            if (!(logPathObject instanceof String)) {
                logger.warn("regex.yml 未配置 log_path，无法获取 server.properties：{}", regexConfigPath);
                return null;
            }

            String logPath = ((String) logPathObject).trim();
            if (logPath.isEmpty()) {
                logger.warn("regex.yml 的 log_path 为空，无法获取 server.properties：{}", regexConfigPath);
                return null;
            }
            return logPath;
        } catch (Exception e) {
            logger.warn("读取 regex.yml 失败，无法获取 server.properties：{}，错误：{}", regexConfigPath, e.getMessage());
            return null;
        }
    }

    private Path inferServerRoot(Path regexConfigPath, String logPath, Path workingDirectory) {
        final Path logPathObject;
        try {
            logPathObject = Paths.get(logPath);
        } catch (Exception e) {
            logger.warn("log_path 非法，无法获取 server.properties：{}，错误：{}", logPath, e.getMessage());
            return null;
        }

        if (logPathObject.isAbsolute()) {
            return extractServerRootFromLogPath(logPathObject.normalize());
        }

        Path regexBasedRoot = deriveRootFromRegexConfig(regexConfigPath);
        Path[] rootCandidates = new Path[]{regexBasedRoot, workingDirectory};

        Path firstInferredRoot = null;
        for (Path rootCandidate : rootCandidates) {
            if (rootCandidate == null) {
                continue;
            }

            Path resolvedLogPath = rootCandidate.resolve(logPathObject).normalize();
            Path inferredRoot = extractServerRootFromLogPath(resolvedLogPath);
            if (inferredRoot == null) {
                continue;
            }
            if (firstInferredRoot == null) {
                firstInferredRoot = inferredRoot;
            }

            Path serverPropertiesPath = inferredRoot.resolve(SERVER_PROPERTIES_FILE).normalize();
            if (Files.isRegularFile(serverPropertiesPath)) {
                return inferredRoot;
            }
        }
        return firstInferredRoot;
    }

    private static Path deriveRootFromRegexConfig(Path regexConfigPath) {
        Path normalizedPath = regexConfigPath.toAbsolutePath().normalize();
        Path moduleDirectory = normalizedPath.getParent();
        if (moduleDirectory == null) {
            return null;
        }

        Path moduleParent = moduleDirectory.getParent();
        if (moduleParent == null) {
            return null;
        }

        if (equalsIgnoreCase(moduleDirectory.getFileName(), BaseConstant.MODULE_NAME) && equalsIgnoreCase(moduleParent.getFileName(), CONFIG_DIRECTORY)) {
            return moduleParent.getParent();
        }

        return moduleParent;
    }

    private static boolean equalsIgnoreCase(Path pathSegment, String text) {
        return pathSegment != null && pathSegment.toString().equalsIgnoreCase(text);
    }

    private static Path extractServerRootFromLogPath(Path resolvedLogPath) {
        Path logDirectory = resolvedLogPath.getParent();
        if (logDirectory == null) {
            return null;
        }

        if (equalsIgnoreCase(logDirectory.getFileName(), LOGS_DIRECTORY) && logDirectory.getParent() != null) {
            return logDirectory.getParent();
        }

        return logDirectory;
    }

    private void initPingTarget(Path serverPropertiesPath) {
        String host = DEFAULT_SERVER_HOST;
        int port = DEFAULT_SERVER_PORT;
        Path normalizedPath = serverPropertiesPath.toAbsolutePath().normalize();

        if (!Files.isRegularFile(normalizedPath)) {
            setPingTarget(host, port, true);
            return;
        }

        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(normalizedPath)) {
            properties.load(inputStream);
            String configuredHost = properties.getProperty(SERVER_IP_KEY, "").trim();
            String configuredPort = properties.getProperty(SERVER_PORT_KEY, String.valueOf(DEFAULT_SERVER_PORT)).trim();

            if (!configuredHost.isEmpty()) {
                host = configuredHost;
            }
            port = parsePort(configuredPort);
        } catch (IOException e) {
            logger.warn("读取 server.properties 失败，状态接口将使用默认地址 {}:{}，错误：{}", host, port, e.getMessage());
            setPingTarget(host, port, false);
            return;
        }

        setPingTarget(host, port, true);
    }

    private void setPingTarget(String host, int port, boolean available) {
        synchronized (lifecycleLock) {
            PingTarget current = pingTarget;
            if (current.sameEndpoint(host, port, available)) {
                return;
            }

            PingTarget replacement = new PingTarget(host, port, available, targetVersion.incrementAndGet());
            pingTarget = replacement;
            snapshotCache = null;
            snapshotWaiter.result.completeExceptionally(
                    new java.util.concurrent.CancellationException("Ping target changed"));
            snapshotWaiter = new SnapshotWaiter(replacement);
            // 目标变化后立即刷新，不等待原来的周期；旧刷新结果会因 target version 不匹配而被丢弃。
            if (refreshExecutor != null && !refreshExecutor.isShutdown()) {
                scheduleRefreshLocked(0L);
            }
        }
    }

    private PingTarget unavailableTarget(String host, int port) {
        return new PingTarget(host, port, false, targetVersion.incrementAndGet());
    }

    private int parsePort(String portText) {
        try {
            int parsedPort = Integer.parseInt(portText);
            if (parsedPort <= 0 || parsedPort > 65535) {
                logger.warn("server-port 配置越界（{}），将使用默认端口 {}", portText, DEFAULT_SERVER_PORT);
                return DEFAULT_SERVER_PORT;
            }
            return parsedPort;
        } catch (NumberFormatException e) {
            logger.warn("server-port 配置非法（{}），将使用默认端口 {}", portText, DEFAULT_SERVER_PORT);
            return DEFAULT_SERVER_PORT;
        }
    }

    /**
     * 返回最近一次定时采集的完整状态快照。
     *
     * <p>定时采集在运行时，此方法不执行网络 Ping 或系统指标采集，只读取已发布的快照；
     * 只有首次采集尚未完成时，调用方才等待共享的首轮结果，等待有明确上限。
     * 采集器尚未启动（未调用 {@link #startRefreshScheduler(int)}）时保留同步采集回退。
     *
     * @return 状态快照 Map
     */
    public Map<String, Object> collectStatusSnapshot() {
        return getOrCollectSnapshot().toMap();
    }

    private ServerStatusSnapshot getOrCollectSnapshot() {
        ScheduledThreadPoolExecutor executor = refreshExecutor;
        if (executor != null && !executor.isShutdown()) {
            for (int attempt = 0; attempt < 3; attempt++) {
                PingTarget target = pingTarget;
                SnapshotCache cached = snapshotCache;
                if (cached != null && cached.targetVersion == target.version) {
                    return cached.snapshot;
                }

                SnapshotWaiter waiter = snapshotWaiter;
                if (waiter.target.version != target.version) {
                    continue;
                }
                try {
                    SnapshotCache firstSnapshot = waiter.result.get(
                            INITIAL_SNAPSHOT_WAIT_MILLIS, TimeUnit.MILLISECONDS);
                    if (firstSnapshot.targetVersion == pingTarget.version) {
                        return firstSnapshot.snapshot;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return createSnapshot(pingTarget, timeoutPingResult(pingTarget));
                } catch (ExecutionException | TimeoutException e) {
                    return createSnapshot(pingTarget, timeoutPingResult(pingTarget));
                }
            }
            PingTarget target = pingTarget;
            return createSnapshot(target, timeoutPingResult(target));
        }

        PingTarget target = pingTarget;
        SnapshotCache cached = snapshotCache;
        if (cached != null && cached.targetVersion == target.version
                && !cached.isExpired(System.nanoTime())) {
            return cached.snapshot;
        }

        SnapshotCache fresh = collectSnapshot(target);
        if (target == pingTarget) {
            snapshotCache = fresh;
        }
        return fresh.snapshot;
    }

    private void refreshAndPublish() {
        ScheduledThreadPoolExecutor executor = refreshExecutor;
        PingTarget target = pingTarget;
        SnapshotWaiter waiter = snapshotWaiter;
        try {
            SnapshotCache fresh = collectSnapshot(target);
            synchronized (lifecycleLock) {
                if (executor != null && executor == refreshExecutor && !executor.isShutdown()
                        && target == pingTarget) {
                    snapshotCache = fresh;
                    if (waiter == snapshotWaiter && waiter.target == target) {
                        waiter.result.complete(fresh);
                    }
                }
            }
        } catch (RuntimeException e) {
            logger.error("采集服务器状态快照失败，保留当前缓存", e);
            if (waiter.target == target) {
                waiter.result.completeExceptionally(e);
            }
        }
    }

    private SnapshotCache collectSnapshot(PingTarget target) {
        ServerStatusSnapshot snapshot = createSnapshot(target, collectServerListPing(target));
        return new SnapshotCache(snapshot, System.nanoTime(), target.version);
    }

    private ServerStatusSnapshot createSnapshot(PingTarget target, ServerListPingResult pingResult) {
        return new ServerStatusSnapshot(
                getOrNull(serverTypeSupplier),
                getOrNull(serverVersionSupplier),
                pingResult,
                metrics.collectCpuInformation(),
                metrics.collectMemoryInformation());
    }

    private static String getOrNull(Supplier<String> supplier) {
        return supplier == null ? null : supplier.get();
    }

    private static ServerListPingResult timeoutPingResult(PingTarget target) {
        return ServerListPingResult.of(
                target.available, target.host, target.port, PING_REASON_TIMEOUT,
                "等待状态采集超时", null);
    }

    /** 快照、单调时间和探测目标版本一起发布，读取时三者保持一致。 */
    private static final class SnapshotCache {

        private final ServerStatusSnapshot snapshot;
        private final long createdAtNanos;
        private final long targetVersion;

        private SnapshotCache(ServerStatusSnapshot snapshot, long createdAtNanos, long targetVersion) {
            this.snapshot = snapshot;
            this.createdAtNanos = createdAtNanos;
            this.targetVersion = targetVersion;
        }

        private boolean isExpired(long nowNanos) {
            return nowNanos - createdAtNanos >= TimeUnit.MILLISECONDS.toNanos(SNAPSHOT_CACHE_TTL_MILLIS);
        }
    }

    private static final class SnapshotWaiter {
        private final PingTarget target;
        private final CompletableFuture<SnapshotCache> result = new CompletableFuture<>();

        private SnapshotWaiter(PingTarget target) {
            this.target = target;
        }
    }

    private ServerListPingResult collectServerListPing(PingTarget currentTarget) {
        if (!currentTarget.available) {
            return ServerListPingResult.of(false, currentTarget.host, currentTarget.port, PING_REASON_NOT_CONFIGURED, null, null);
        }

        try {
            MinecraftPingResponse pingResponse = PING_CLIENT.fetchStatus(currentTarget.host, currentTarget.port);
            return ServerListPingResult.of(true, currentTarget.host, currentTarget.port, PING_REASON_OK, null, pingResponse);
        } catch (MinecraftPingException e) {
            String reason = resolvePingFailureReason(e);
            String error = resolvePingErrorMessage(e);
            logger.warn("Minecraft Server List Ping failed, reason={}, host={}, port={}, error={}", reason, currentTarget.host, currentTarget.port, error);
            return ServerListPingResult.of(true, currentTarget.host, currentTarget.port, reason, error, null);
        }
    }

    private static String resolvePingFailureReason(MinecraftPingException exception) {
        Throwable cause = rootCause(exception);
        if (cause instanceof SocketTimeoutException) {
            return PING_REASON_TIMEOUT;
        }
        if (cause instanceof ConnectException || cause instanceof UnknownHostException || cause instanceof NoRouteToHostException || cause instanceof UnresolvedAddressException) {
            return PING_REASON_OFFLINE;
        }
        return PING_REASON_ERROR;
    }

    private static String resolvePingErrorMessage(MinecraftPingException exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return exception.getClass().getSimpleName();
        }
        return message;
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable result = throwable;
        while (result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }
}
