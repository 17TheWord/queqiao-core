package io.github.theword.queqiao.core.api;

import io.github.theword.queqiao.core.exception.protocol.ProtocolException;
import com.google.gson.JsonElement;

/**
 * 通用 API SPI
 *
 * <p>Core 只定义这一层协议能力边界，<b>不规定完整 API 集</b>：
 * 一个 {@code api} 名称能否被调用，完全取决于它是否被注册进
 * {@link ApiRegistry}，而不是取决于 Core 里是否存在某个固定的 API 表。
 *
 * <p>因此：
 * <ul>
 *     <li>Core 自带的 API（如私聊）与第三方 Addon 的 API 使用<b>同一套</b> SPI；</li>
 *     <li>Core <b>不需要知道</b>具体实现类的存在，也不需要为新增 API 改动任何代码；</li>
 *     <li>平台若没有某项能力，可以选择<b>根本不注册</b>对应 API，
 *         客户端会得到 404，而不是运行时返回"不支持"。</li>
 * </ul>
 *
 * <p><b>不要求继承任何 Core 基类</b>：{@code Api} 是接口，第三方可以直接
 * {@code implements Api}。Core 自带的 {@code AbstractProtocolHandler} 只是
 * "把 {@code JsonElement} 解析成强类型 Payload"的一种<b>可选</b>实现方式。
 *
 * <p><b>线程安全约束</b>：一个 {@code Api} 实例会被多个连接、多个线程共享调用，
 * 实现<b>必须无状态</b>——不得持有任何随请求变化的字段，
 * 全部输入来自 {@link #handle(JsonElement)} 的参数，输出通过返回值表达。
 *
 * <p><b>线程模型</b>：当前契约是<b>同步调用</b>，即 {@link #handle(JsonElement)}
 * 在协议处理线程上直接执行完毕并返回。平台实现在被调用时所在的线程上下文
 * 由平台适配器自行保证；调度器/线程池抽象不在本阶段范围内。
 *
 * @since 0.7.0
 */
public interface Api {

    /**
     * 协议 API 名称
     *
     * <p>该名称是客户端请求体中 {@code api} 字段的唯一标识，
     * 在同一个 {@link ApiRegistry} 内必须唯一且非空白。
     *
     * @return API 名称
     */
    String getName();

    /**
     * 执行该 API
     *
     * @param data 请求体中的 {@code data} 字段，允许为 null
     * @return 执行结果，会被放进响应的 {@code data} 字段
     * @throws ProtocolException 请求不合法（400）或执行失败（500 / 503）时抛出，
     *                           由协议层转换成对应的状态码
     */
    Object handle(JsonElement data) throws ProtocolException;
}
