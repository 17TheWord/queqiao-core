package io.github.theword.queqiao.core.command;

import java.util.Objects;

/**
 * 一次命令调用的执行上下文
 *
 * <p>语义是<b>一次具体的 Command Invocation</b>，<b>不是</b>：
 * <ul>
 *     <li>命令发送者本身（那是平台原生的 NCS）；</li>
 *     <li>平台上下文（那属于 {@code AbstractPlatformContext}）；</li>
 *     <li>Runtime 上下文 / 全局上下文。</li>
 * </ul>
 *
 * <p><b>NCS — Native Command Source</b>：平台原生的命令来源对象
 * （Bukkit / Paper 的 {@code CommandSender}、Fabric 的 {@code ServerCommandSource}、
 * Velocity 的 {@code CommandSource}、NeoForge 的 {@code CommandSourceStack} …）。
 * Core 不解释、不包装、不代理该对象，具体类型完全由平台适配层决定。
 *
 * <p><b>生命周期不变式</b>：
 * <pre>
 * 一次 Command Invocation
 *         ↓
 * 创建 CommandExecutionContext
 *         ↓
 * 绑定一个 Native Command Source
 *         ↓
 * 整个 invocation 生命周期中保持不变
 *         ↓
 * Invocation 结束
 * </pre>
 * 因此 {@link #nativeSource} 是 {@code public final} —— 它表示"当前执行上下文的固有数据"，
 * 而不是一个需要动态解析的操作。<b>不提供 getter，也不提供 setter</b>，
 * 唯一访问方式就是字段本身。
 *
 * <p><b>CommandExecutionContext is invocation-scoped.</b> 一个实例只服务于一次命令调用，
 * 用完即弃。由此推出三条使用契约：
 * <ol>
 *     <li><b>不得保存到实例字段</b> —— Command / CommandNode 实现不得把 context（或它的
 *         {@link #nativeSource}）缓存到成员变量，否则会跨 invocation 复用，破坏上面这条不变式；</li>
 *     <li><b>不得在 invocation 结束后继续持有</b> —— 实现不应假设 context 或 nativeSource
 *         在方法返回之后仍然有效；需要跨调用保存的信息必须转成 Core 自己的 DTO；</li>
 *     <li><b>nativeSource 可能具有平台线程亲和性</b> —— 例如 Bukkit 的命令发送者通常只能在
 *         主线程安全访问。Core <b>不</b>提供异步命令框架，跨线程 / 异步使用必须遵守
 *         具体平台自身的线程规则，Core 不做任何调度保证。</li>
 * </ol>
 *
 * <p><b>严格非 null</b>：一个真实 Command Invocation 必然存在 Native Source。
 * 没有真实命令来源的调用（Runtime 生命周期等）<b>不应该创建本对象</b>，
 * 因此本类型<b>不提供</b> {@code NONE} / {@code SYSTEM} / {@code INTERNAL} 之类的空上下文。
 *
 * <p><b>泛型边界（重要）</b>：{@code NCS} <b>只能</b>传播在 Command 子系统内部。
 * 禁止出现 {@code QueQiaoRuntime<NCS>}、{@code WebsocketManager<NCS>}、
 * {@code AbstractPlatformContext<..., NCS>}、{@code Api<NCS, ...>} 之类的类型 ——
 * Runtime / WebSocket / 平台上下文都<b>不知道</b>命令上下文的存在。
 *
 * @param <NCS> Native Command Source，平台原生命令来源类型
 * @since 0.7.0
 */
public abstract class CommandExecutionContext<NCS> {

    /**
     * 本次 Command Invocation 对应的平台原生 Command Source
     *
     * <p>Core 不解释、不包装、不代理该对象。
     * 该引用在整个 Command Invocation 生命周期内保持不变，且不得为 null。
     */
    public final NCS nativeSource;

    /**
     * @param nativeSource 平台原生命令来源，不得为 null
     */
    protected CommandExecutionContext(NCS nativeSource) {
        this.nativeSource = Objects.requireNonNull(nativeSource, "nativeSource");
    }

    /**
     * 向当前命令调用者发送回复
     *
     * <p>"文本如何变成平台组件"由平台实现决定 —— Core 不关心是否走 JSON 转换、用什么样式。
     *
     * @param message 回执内容
     */
    public abstract void reply(String message);

    /**
     * 判断当前命令调用者是否拥有指定权限
     *
     * <p>语义是"这个命令调用者是否有权执行"，<b>不是</b>"某个玩家是否有权限"。
     * 命令来源可能是玩家、控制台、命令方块或平台特有的来源，
     * 各自如何判定由平台实现决定 —— Core 不假定"只有玩家才可能有权限"。
     *
     * @param permission 权限节点
     * @return 是否具备该权限
     */
    public abstract boolean hasPermission(String permission);
}
