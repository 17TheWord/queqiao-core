package io.github.theword.queqiao.core.extension;

import java.util.Collection;

/**
 * 扩展发现入口
 *
 * <p>平台宿主用它把"当前平台发现到的扩展实例"交给 Core。Core <b>不</b>规定发现方式 ——
 * Fabric 的 custom entrypoint、Bukkit 的 PluginManager、ServiceLoader……
 * 都由各平台自己决定，因此这里只有"给我扩展"这一项能力。
 *
 * <p>它被设计为函数式接口，于是平台可以直接用方法引用：
 * <pre>
 * runtime = QueQiaoRuntime.start(platform, FabricExtensionDiscovery::discover);
 * </pre>
 *
 * <p><b>契约</b>：
 * <ul>
 *     <li>{@link #discover()} 返回 {@code null} → 视为装配错误，快速失败；</li>
 *     <li>{@code discover()} 抛异常 → Runtime 创建失败，不产生半成品 Runtime；</li>
 *     <li>返回的集合本身不得包含 {@code null} 元素。</li>
 * </ul>
 *
 * <p>不支持扩展的平台<b>不需要</b>实现本接口，直接
 * {@code QueQiaoRuntime.start(platform)} 即可。
 *
 * @since 0.7.0-beta.3
 */
@FunctionalInterface
public interface QueQiaoExtensionDiscovery {

    /**
     * 发现当前平台上的全部扩展实例
     *
     * @return 扩展集合；没有扩展时返回空集合，<b>不得</b>返回 {@code null}
     */
    Collection<? extends QueQiaoExtension> discover();
}
