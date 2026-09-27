package io.github.theword.queqiao.core.api;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API 注册中心
 *
 * <p>回答一个问题：<b>这个 Runtime 当前对外开放哪些 API？</b>
 * 答案完全由"谁注册了什么"决定，而不是由 Core 里的固定 API 表决定。
 *
 * <p><b>生命周期</b>（与 {@code ConfigRegistry} 的设计哲学一致）：
 * <pre>
 * create()
 *     ↓  平台 / Addon 注册 API
 * start()
 *     ↓  freeze()
 * 运行期：只读
 * </pre>
 * 冻结之后禁止再注册，因此运行期不存在动态修改 API 集合的可能——
 * 协议层因此可以无锁地并发读取。
 *
 * <p><b>线程安全</b>：{@link #register(Api)} / {@link #freeze()} 之间用同一把锁串行化，
 * 保证"冻结后注册"不会漏网；{@link #find(String)} / {@link #contains(String)}
 * 基于 {@link ConcurrentHashMap} 读取，不需要加锁。
 *
 * @since 0.7.0
 */
public final class ApiRegistry {

    private final Map<String, Api> apis = new ConcurrentHashMap<>();

    /**
     * 串行化 register / freeze，保证"冻结"与"注册"之间不出现竞态
     */
    private final Object lock = new Object();

    private volatile boolean frozen = false;

    /**
     * 注册一个 API
     *
     * @param api API 实现，不得为 null
     * @throws NullPointerException      {@code api} 为 null
     * @throws IllegalArgumentException 名称为 null / 空白，或同名 API 已注册（不允许覆盖）
     * @throws IllegalStateException    注册中心已冻结
     */
    public void register(Api api) {
        Objects.requireNonNull(api, "api");

        String name = api.getName();
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Api 名称不能为 null 或空白: " + api.getClass().getName());
        }

        synchronized (lock) {
            if (frozen) {
                throw new IllegalStateException("ApiRegistry 已冻结，不能再注册 API: " + name);
            }
            Api previous = apis.putIfAbsent(name, api);
            if (previous != null) {
                throw new IllegalArgumentException("Api 名称重复，不允许覆盖: " + name);
            }
        }
    }

    /**
     * 按名称查找 API
     *
     * @param name API 名称，允许为 null
     * @return 命中的 API；未注册时返回 {@link Optional#empty()}（协议层据此返回 404）
     */
    public Optional<Api> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(apis.get(name));
    }

    /**
     * 判断某个名称是否已注册
     *
     * @param name API 名称，允许为 null
     * @return 是否已注册
     */
    public boolean contains(String name) {
        return name != null && apis.containsKey(name);
    }

    /**
     * 冻结注册中心：之后任何 {@link #register(Api)} 都会失败
     *
     * <p>幂等：重复调用不会抛异常。
     */
    public void freeze() {
        synchronized (lock) {
            frozen = true;
        }
    }

    /**
     * @return 注册中心是否已冻结
     */
    public boolean isFrozen() {
        return frozen;
    }
}
