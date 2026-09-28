package io.github.theword.queqiao.core.api;

import java.util.ArrayList;
import java.util.List;

import io.github.theword.queqiao.core.platform.AbstractPlatformContext;
import io.github.theword.queqiao.core.protocol.RconCommandExecutor;
import io.github.theword.queqiao.core.protocol.handler.status.ServerStatusCollector;
import org.slf4j.Logger;

/**
 * 内置 API 的默认批次
 *
 * <p>core 提供一整套现成的协议实现。是否使用、使用哪些，由使用方决定——
 * 把 {@link #all} 的返回值直接交给 {@code ProtocolRouter} 即全部启用；
 * 想裁剪就从列表里删，想扩展就往列表里加。
 *
 * <pre>
 * // 全都要
 * new ProtocolRouter(logger, DefaultApis.all(ctx, status, rcon, logger));
 *
 * // 只要广播
 * new ProtocolRouter(logger, Collections.singletonList(new BroadcastApi(ctx)));
 *
 * // 默认 + 追加自己的
 * List&lt;Api&lt;?, ?&gt;&gt; apis = new ArrayList&lt;&gt;(DefaultApis.all(ctx, status, rcon, logger));
 * apis.add(new MyCustomApi());
 * new ProtocolRouter(logger, apis);
 * </pre>
 *
 * @since 0.7.0
 */
public final class DefaultApis {

    private DefaultApis() {
    }

    /**
     * 构造内置 API 的完整列表
     *
     * @param platformContext       平台上下文，不得为 null
     * @param serverStatusCollector 状态采集器，供 get_status 使用
     * @param rconCommandExecutor   RCON 执行器，供 send_rcon_command 使用
     * @param logger                日志实现；显式传入而非在 Api 内部静态获取，
     *                              以保持"不访问静态全局状态"的约束
     * @return 可变的 API 列表，调用方可自由增删
     */
    public static List<Api<?, ?>> all(
            AbstractPlatformContext<?, ?, ?, ?> platformContext,
            ServerStatusCollector serverStatusCollector,
            RconCommandExecutor rconCommandExecutor,
            Logger logger
    ) {
        List<Api<?, ?>> apis = new ArrayList<>();
        apis.add(new BroadcastApi(platformContext));
        apis.add(new SendTitleApi(platformContext, logger));
        apis.add(new SendActionBarApi(platformContext));
        apis.add(new SendPrivateMessageApi(platformContext));
        apis.add(new SendCommandApi());
        apis.add(new SendRconCommandApi(rconCommandExecutor, logger));
        apis.add(new GetStatusApi(serverStatusCollector, logger));
        return apis;
    }
}
