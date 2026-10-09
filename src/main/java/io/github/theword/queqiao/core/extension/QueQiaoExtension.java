package io.github.theword.queqiao.core.extension;

/**
 * QueQiao Runtime 扩展入口
 *
 * <p>扩展实例由<b>具体平台宿主</b>负责发现与创建，并在 QueQiao Runtime 完成协议路由
 * 构造<b>之前</b>交给 {@code QueQiaoRuntime.create(...)}。Core 不包含任何平台发现机制
 * （不感知 Bukkit / Fabric / Forge / NeoForge / Velocity 的插件加载器）。
 *
 * <p><b>所有权</b>：扩展<b>不</b>拥有 Runtime，也<b>不</b>得自行创建
 * {@code QueQiaoRuntime}、{@code WebsocketManager} 或任何 WebSocket 实例。
 * 扩展只能向已存在的 Runtime <b>贡献能力</b>（当前仅限注册协议 API），
 * Runtime 自身拥有 Config、ProtocolRouter、HandleProtocolMessage、WebsocketManager
 * 等全部运行时资源。
 *
 * <p><b>注册时机</b>：{@link #register(QueQiaoExtensionContext)} 只在 Runtime 的
 * Extension registration phase 中被调用一次。该阶段结束后，{@code QueQiaoExtensionContext}
 * 会被 seal —— 扩展即使持有 context 引用，也无法再注册 API。
 * 当前版本<b>只支持启动装配阶段注册</b>，不支持运行期动态注册或卸载。
 *
 * <p><b>线程契约</b>：注册发生在 Runtime 构造线程上，且此时 Runtime 尚未发布给其它线程，
 * 因此扩展无需在 {@code register(...)} 内部额外同步。
 *
 * @since 0.7.0-beta.3
 */
public interface QueQiaoExtension {

    /**
     * 注册当前扩展提供的能力
     *
     * <p>该方法只会在 Runtime 的 Extension registration phase 中调用。
     * 若本方法抛出异常，Runtime 构造将<b>整体失败</b>（不会留下部分可用的 Runtime），
     * 因此扩展应在注册前完成自身的依赖校验，让装配错误尽早暴露。
     *
     * @param context 由当前 QueQiao Runtime 提供的扩展上下文，不得为 null
     */
    void register(QueQiaoExtensionContext context);
}
