package io.github.theword.queqiao.core.protocol.handler;

import io.github.theword.queqiao.core.payload.EmptyPayload;
import io.github.theword.queqiao.core.handle.HandleApiService;
import io.github.theword.queqiao.core.protocol.AbstractProtocolHandler;
import io.github.theword.queqiao.core.protocol.handler.status.ServerStatusCollector;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Objects;

public class GetStatusHandler extends AbstractProtocolHandler<EmptyPayload, Map<String, Object>> {

    /**
     * 状态采集器（Runtime 实例级，由 QueQiaoRuntime 创建后注入）
     */
    private final ServerStatusCollector serverStatusCollector;

    public GetStatusHandler(String apiName, Logger logger, HandleApiService handleApiService, ServerStatusCollector serverStatusCollector) {
        super(apiName, logger, handleApiService, EmptyPayload.class);
        this.serverStatusCollector = Objects.requireNonNull(serverStatusCollector, "serverStatusCollector");
    }

    /**
     * 返回服务器状态快照
     *
     * <p>Runtime 启动后由后台任务按配置间隔采集完整快照，正常情况下本处理器只读取最近一次
     * 已发布的快照，不在连接处理线程中发起 Ping。
     *
     * <p><b>但"不发起 Ping"并非绝对</b>：当首轮采集尚未完成时，本处理器会等待共享的首轮结果
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
    protected Map<String, Object> handlePayload(EmptyPayload payload) {
        this.logger.debug("收到 get_status 请求，返回服务器状态快照");
        return serverStatusCollector.collectStatusSnapshot();
    }
}
