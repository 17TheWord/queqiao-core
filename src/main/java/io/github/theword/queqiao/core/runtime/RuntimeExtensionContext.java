package io.github.theword.queqiao.core.runtime;

import io.github.theword.queqiao.core.api.Api;
import io.github.theword.queqiao.core.extension.QueQiaoExtensionContext;

import java.util.List;
import java.util.Objects;

/**
 * {@link QueQiaoExtensionContext} 的 Runtime 侧实现
 *
 * <p><b>刻意 package-private</b>：它属于 Runtime 的内部装配细节，不进 public API surface。
 * 扩展只应通过 {@link QueQiaoExtensionContext} 接口使用其能力。
 *
 * <p>实现极其薄：只持有一份"待装配的 API 列表"引用，把 {@code registerApi} 追加进去。
 * 不提供任何读取该列表的入口 —— 扩展只能"贡献"，不能"查看或改动"其它 API。
 *
 * <p><b>seal 语义</b>：{@link #seal()} 后 {@link #registerApi(Api)} 抛
 * {@link IllegalStateException}。{@code open} 标记为 {@code volatile}，
 * 因为最典型的误用形态正是"扩展把 context 存起来、稍后（可能在另一个线程）再注册"，
 * 只有跨线程可见才能可靠拦截。
 */
final class RuntimeExtensionContext implements QueQiaoExtensionContext {

    private final List<Api<?, ?>> apis;

    /**
     * 注册阶段是否仍开放；{@code false} 表示已 seal
     */
    private volatile boolean open = true;

    /**
     * @param apis Runtime 正在装配的 API 列表，不得为 null
     */
    RuntimeExtensionContext(List<Api<?, ?>> apis) {
        this.apis = Objects.requireNonNull(apis, "apis");
    }

    @Override
    public void registerApi(Api<?, ?> api) {
        if (!open) {
            throw new IllegalStateException("Extension registration phase has already ended");
        }
        apis.add(Objects.requireNonNull(api, "api"));
    }

    /**
     * 关闭注册阶段；幂等
     */
    void seal() {
        open = false;
    }
}
