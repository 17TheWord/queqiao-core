package io.github.theword.queqiao.core.protocol;

import io.github.theword.queqiao.core.api.Api;
import io.github.theword.queqiao.core.constant.BaseConstant;
import io.github.theword.queqiao.core.constant.ProtocolConstants;
import io.github.theword.queqiao.core.exception.protocol.ProtocolException;
import io.github.theword.queqiao.core.payload.BasePayload;
import io.github.theword.queqiao.core.response.Response;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 协议路由器
 *
 * <p>把请求的 {@code api} 字段映射到对应的 {@link Api} 并分发。
 *
 * <p><b>注册由外部决定</b>：本类不再自己构造 Api，而是接收一个 Api 集合。
 * 因此"注册哪些 api"是装配方的自由选择（core 提供 {@code DefaultApis} 作为默认批次），
 * 本类只负责查表与分发。
 *
 * <p><b>线程安全</b>：api 表在构造阶段一次性建好，之后<b>不可变</b>
 * （{@link Collections#unmodifiableMap}），因此本类可被多连接并发使用，且<b>不含任何锁</b>。
 * 把集合作为构造入参而不是提供 {@code register} 方法，正是为了让这一不变量
 * 由类型系统保证，而不是靠调用方自觉。
 *
 * <p><b>不依赖全局状态</b>：日志实现与 Api 集合均由构造器注入。
 *
 * @since 0.6.11
 */
public class ProtocolRouter {

    private final Logger logger;

    /**
     * api 名 → Api 实例（含别名），构造后只读
     */
    private final Map<String, Api<?, ?>> apis;

    /**
     * 构造路由器
     *
     * @param logger 日志实现，不得为 null
     * @param apis   Api 集合，不得为 null；名字为空或重复时抛 {@link IllegalArgumentException}
     */
    public ProtocolRouter(Logger logger, Collection<Api<?, ?>> apis) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.apis = buildApiMap(apis);
    }

    /**
     * 建立 api 名 → 实例的只读映射
     *
     * <p>名字重复时<b>快速失败</b>，而不是静默覆盖——后者会让"注册了两个同名的 api"
     * 这种装配错误一直潜伏到运行期才以"行为不符合预期"的形式暴露。
     */
    private static Map<String, Api<?, ?>> buildApiMap(Collection<Api<?, ?>> apis) {
        Objects.requireNonNull(apis, "apis");
        Map<String, Api<?, ?>> mutable = new HashMap<>();
        for (Api<?, ?> api : apis) {
            putUnique(mutable, api.name(), api);
            for (String alias : api.aliases()) {
                putUnique(mutable, alias, api);
            }
        }
        return Collections.unmodifiableMap(mutable);
    }

    private static void putUnique(Map<String, Api<?, ?>> target, String name, Api<?, ?> api) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("api 名称不能为空：" + api.getClass().getName());
        }
        if (target.containsKey(name)) {
            throw new IllegalArgumentException("重复注册的 api 名称：" + name);
        }
        target.put(name, api);
    }

    /**
     * 路由并处理一个请求
     *
     * <p>状态码语义：
     * <ul>
     *     <li>{@code 400} —— 请求本身不合法（payload 为 null、缺少 api 字段、负载解析失败）</li>
     *     <li>{@code 404} —— api 未注册</li>
     *     <li>Api 抛出的 {@link ProtocolException} —— 使用其自带状态码（如 400 / 500 / 503）</li>
     *     <li>{@code 500} —— 真正的服务端未预期异常</li>
     * </ul>
     *
     * @param payload 请求负载，允许为 null
     * @return 响应，永不为 null
     */
    public Response route(BasePayload payload) {
        if (payload == null) {
            this.logger.warn("请求负载为空，已拒绝");
            return Response.failed(ProtocolConstants.Status.BAD_REQUEST, ProtocolConstants.Message.PARSE_MESSAGE_FAILED);
        }

        String api = payload.getApi();
        if (api == null || api.trim().isEmpty()) {
            this.logger.warn("请求缺少 api 字段，已拒绝");
            return Response.failed(ProtocolConstants.Status.BAD_REQUEST, ProtocolConstants.Message.MISSING_API);
        }

        Api<?, ?> apiImpl = apis.get(api);
        if (apiImpl == null) {
            this.logger.warn(BaseConstant.UNKNOWN_API + "{}", api);
            return Response.failed(ProtocolConstants.Status.NOT_FOUND, BaseConstant.UNKNOWN_API + api);
        }

        try {
            Object data = apiImpl.execute(payload.getData());
            return Response.success(data);
        } catch (ProtocolException e) {
            return Response.failed(e.getCode(), e.getMessage(), e.getData());
        } catch (Exception e) {
            // 只有真正的内部异常才归 500；此处记录堆栈但不回传任何请求内容
            this.logger.error("处理 api={} 的请求时发生未预期异常", api, e);
            // 只回传稳定的通用文案：异常信息 / 堆栈 / 类名可能携带文件路径、URI、token 等内部细节
            return Response.failed(
                    ProtocolConstants.Status.INTERNAL_ERROR, ProtocolConstants.Message.INTERNAL_ERROR);
        }
    }
}
