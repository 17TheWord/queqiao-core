package io.github.theword.queqiao.core.api;

import java.util.Map;
import java.util.Objects;

import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.payload.EmptyPayload;
import io.github.theword.queqiao.core.protocol.handler.status.ServerStatusCollector;
import org.slf4j.Logger;

/**
 * 查询服务器状态
 *
 * <p>协议名：{@code get_status}。无负载。
 *
 * <p>该 Api 与平台无关——它只读取 Runtime 实例级的状态采集器。
 */
public final class GetStatusApi extends Api<EmptyPayload, Map<String, Object>> {

    private final ServerStatusCollector serverStatusCollector;
    private final Logger logger;

    public GetStatusApi(ServerStatusCollector serverStatusCollector, Logger logger) {
        super(EmptyPayload.class);
        this.serverStatusCollector = Objects.requireNonNull(serverStatusCollector, "serverStatusCollector");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String name() {
        return ProtocolConstants.Api.GET_STATUS;
    }

    /**
     * 返回服务器状态快照
     *
     * <p>Runtime 启动后由后台任务按配置间隔采集完整快照，正常情况下本 Api 只读取最近一次
     * 已发布的快照，不在连接处理线程中发起 Ping。
     *
     * <p><b>但"不发起 Ping"并非绝对</b>：当首轮采集尚未完成时，会等待共享的首轮结果
     * （等待上限见 {@code ServerStatusCollector} 的 {@code INITIAL_SNAPSHOT_WAIT_MILLIS}），
     * 而该首轮采集本身包含一次 Minecraft Server List Ping（socket 超时 3 秒）。
     * 因此最坏情况下连接读线程会被阻塞至等待上限，而不是"永不执行 Ping"。
     *
     * <p>日志级别为 debug：该接口可能被高频轮询，INFO 级别会造成日志刷屏。
     *
     * @param payload 空负载
     * @return 状态快照 Map
     */
    @Override
    protected Map<String, Object> doExecute(EmptyPayload payload) {
        this.logger.debug("收到 get_status 请求，返回服务器状态快照");
        return serverStatusCollector.collectStatusSnapshot();
    }
}
