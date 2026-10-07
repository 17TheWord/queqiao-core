package io.github.theword.queqiao.core.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Runtime 重载的结果
 *
 * <p>承载重载过程中<b>本应回执给命令调用者</b>的进度消息。
 *
 * <p><b>为什么是"返回结果"而不是 Runtime 直接回执</b>：
 * 按职责划分，用户可见的反馈统一由 Command 层发送，Runtime 与 WebsocketManager
 * 都<b>不</b>知道命令上下文的存在。因此 Runtime 把进度消息作为结果返回，
 * 由调用方（通常是 {@code ReloadCommand}）决定如何展示。
 *
 * <p>不可变：内部持有的是输入列表的副本。
 *
 * @since 0.7.0
 */
public final class ReloadResult {

    private final List<String> messages;

    /**
     * @param messages 重载过程中的进度消息，不得为 null（可为空列表）
     */
    public ReloadResult(List<String> messages) {
        this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
    }

    /**
     * @return 重载过程中的进度消息（只读），按发生顺序排列
     */
    public List<String> getMessages() {
        return messages;
    }
}
