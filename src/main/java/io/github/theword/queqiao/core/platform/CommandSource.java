package io.github.theword.queqiao.core.platform;

/**
 * 命令来源
 *
 * <p>Core 命令层只认识这个抽象：不再接收 {@code Object}，也不再认识任何平台类型
 * （{@code CommandSourceStack} / {@code CommandSender} / {@code ServerCommandSource} …）。
 *
 * <p><b>职责边界</b>：只描述"这次命令是谁发起的，以及如何回复 / 判断其权限"。
 * 平台整体能力（广播、私聊、Title、ActionBar…）属于 {@link AbstractPlatformContext}，
 * 不要混进本接口。
 *
 * <p><b>第一版只做两项能力</b>：{@link #reply(String)} 与 {@link #hasPermission(String)}。
 * 没有实际调用方的能力（{@code isPlayer} / {@code isConsole} / {@code getPlayer} /
 * {@code nativeSource} …）一律不加——不为"以后可能用到"提前扩大公共 API。
 *
 * <p><b>平台侧</b>直接实现本接口，在实现内部持有平台原生命令来源：
 * <pre>
 * public final class FabricCommandSource implements CommandSource {
 *
 *     private final CommandSourceStack source;   // 平台类型只出现在这里
 *
 *     &#064;Override  public void reply(String message) { ... }
 *     &#064;Override  public boolean hasPermission(String permission) { ... }
 * }
 * </pre>
 *
 * <p><b>本接口刻意不携带泛型</b>：一旦泛型化，{@code CS} 会重新传染进 Core 命令树与 Runtime，
 * 抽象就白做了。抽它的目的正是"把平台类型关进实现类"。
 *
 * @since 0.7.0
 */
public interface CommandSource {

    /**
     * 没有实际命令来源时使用的空实现
     *
     * <p>仅用于 Runtime 启动、WebSocket 内部生命周期等<b>非用户命令</b>的调用链，
     * 不应作为真实用户命令的来源。
     *
     * <p>语义是"当前调用没有真实发送者，因此不能向任何人回执"，<b>不是</b>"一个匿名用户"。
     * 因此：
     * <ul>
     *     <li>{@link #reply(String)} 静默忽略；</li>
     *     <li>{@link #hasPermission(String)} 返回 {@code true}——
     *         内部生命周期调用不应因为命令权限检查而失败。</li>
     * </ul>
     *
     * <p>有了它，整条调用链都不再需要 {@code null}，也不再需要
     * {@code if (source != null)} 这类判断。
     */
    CommandSource NONE = new CommandSource() {

        @Override
        public void reply(String message) {
            // 无实际接收者，静默忽略
        }

        @Override
        public boolean hasPermission(String permission) {
            // 内部调用不进行命令权限拦截
            return true;
        }
    };

    /**
     * 向命令来源回执一行文本
     *
     * <p>"文本如何变成平台组件"由平台实现决定——Core 不关心是否走 JSON 转换、用什么样式。
     * 因此这里只传 {@code String}，不暴露平台组件类型。
     *
     * @param message 回执内容
     */
    void reply(String message);

    /**
     * 判断命令来源是否具备指定权限节点
     *
     * <p>语义是"这个命令来源是否有权执行"，<b>不是</b>"某个玩家是否有权限"。
     * 命令来源可能是玩家、控制台、命令方块或平台特有的来源，
     * 各自如何判定由平台实现决定——Core 不假定"只有玩家才可能有权限"，
     * 也不在公共方法里写 {@code if (!isPlayer()) return false;} 这类规则。
     *
     * @param permission 权限节点
     * @return 是否具备该权限
     */
    boolean hasPermission(String permission);
}
