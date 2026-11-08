package io.github.theword.queqiao.core.runtime;

/**
 * {@link QueQiaoRuntime} 的生命周期状态
 *
 * <p>采用"一次创建、一次启动、一次关闭"模型，状态迁移是单向的：
 *
 * <pre>
 * NEW ──start()──&gt; STARTING ──成功──&gt; RUNNING ──shutdown()──&gt; STOPPING ──&gt; STOPPED
 *                                └──失败──&gt; FAILED
 * </pre>
 *
 * <p><b>不支持重新启动</b>：{@code STOPPED} / {@code FAILED} / {@code RUNNING} / {@code STARTING}
 * 状态下调用 {@code start()} 都会抛出 {@link IllegalStateException}。
 * 需要"重启"时应创建新的 Runtime 实例，而不是复用旧实例。
 *
 * <p>{@code NEW} 状态下调用 {@code shutdown()} 是 no-op，状态保持 {@code NEW}，
 * 因此"先 shutdown 再 start"仍然可行。
 *
 * @since 0.7.0
 */
public enum RuntimeState {
    /**
     * 已创建、尚未启动
     */
    NEW,

    /**
     * 正在启动（{@code doStart()} 执行中）
     */
    STARTING,

    /**
     * 启动成功，正常运行
     */
    RUNNING,

    /**
     * 正在关闭（资源清理中）
     */
    STOPPING,

    /**
     * Runtime 的关闭流程已执行完毕，不可再次启动
     *
     * <p><b>只表示"关闭流程已经跑完"</b>，不代表所有底层资源都已确认释放：
     * 个别资源可能停止失败（例如 WebSocket Server 的 selector 线程未退出），
     * 而 WebSocket Client 采用异步 close，也不会等待对端关闭握手完成。
     * 具体结果见 {@code QueQiaoRuntime#getShutdownFailures()}——该列表为空同样只表示
     * "没有观察到明确的停止失败"，不表示"全部资源已释放"。
     */
    STOPPED,

    /**
     * 启动失败，已完成资源清理，不可再次启动
     */
    FAILED
}
