package io.github.theword.queqiao.core.api.platform;

import com.google.gson.JsonElement;

/**
 * 平台广播能力 SPI
 *
 * <p>只表达一件事：<b>平台能够把消息广播出去</b>。
 *
 * <p>刻意保持最小——不包含 Logger、Runtime、Config、Scheduler。
 * 平台实现<b>不需要</b>处理：JSON 解析、协议字段、api 名字、响应组装，
 * 这些全部由 Core 的 {@code BroadcastApi} 负责。
 *
 * <p><b>线程模型</b>：当前契约是<b>同步调用</b>。实现必须保证在被调用时
 * 所处的线程上下文中可以安全工作（平台适配器负责切换到正确的线程）。
 * 调度器/线程池抽象不在本阶段范围内。
 *
 * @since 0.7.0
 */
public interface BroadcastService {

    /**
     * 广播一条消息
     *
     * @param message 消息内容，与请求体中的 {@code data.message} 一致（允许为 null，
     *                与迁移前 {@code HandleApiService#handleBroadcastMessage} 的入参语义保持一致）
     */
    void broadcast(JsonElement message);
}
