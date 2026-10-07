package io.github.theword.queqiao.core.support;

import io.github.theword.queqiao.core.command.CommandExecutionContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 记录型命令执行上下文测试替身
 *
 * <p>记录收到的每一条回执，并可用固定值回答权限判定；
 * 同时把 {@code nativeSource} 原样保存，用于验证"命令内取到的就是同一个对象"。
 *
 * <p>权限返回固定值（而不是按节点判断）是有意的：命令层只需要一个可控的布尔结果，
 * 按节点判断属于平台实现细节，由平台自己的测试覆盖。
 *
 * @param <NCS> Native Command Source，平台原生命令来源类型
 */
public final class FakeCommandExecutionContext<NCS> extends CommandExecutionContext<NCS> {

    private final boolean permissionGranted;
    private final List<String> replies = Collections.synchronizedList(new ArrayList<>());

    /**
     * 构造默认允许全部权限的命令执行上下文
     *
     * @param nativeSource 平台原生命令来源，不得为 null
     */
    public FakeCommandExecutionContext(NCS nativeSource) {
        this(nativeSource, true);
    }

    /**
     * @param nativeSource      平台原生命令来源，不得为 null
     * @param permissionGranted {@link #hasPermission(String)} 的固定返回值
     */
    public FakeCommandExecutionContext(NCS nativeSource, boolean permissionGranted) {
        super(nativeSource);
        this.permissionGranted = permissionGranted;
    }

    @Override
    public void reply(String message) {
        replies.add(message);
    }

    @Override
    public boolean hasPermission(String permission) {
        return permissionGranted;
    }

    /**
     * @return 收到的全部回执快照
     */
    public List<String> getReplies() {
        synchronized (replies) {
            return new ArrayList<>(replies);
        }
    }

    /**
     * @return 收到的回执条数
     */
    public int getReplyCount() {
        synchronized (replies) {
            return replies.size();
        }
    }

    /**
     * @param fragment 片段
     * @return 是否存在包含该片段的回执
     */
    public boolean hasReplyContaining(String fragment) {
        synchronized (replies) {
            return replies.stream().anyMatch(reply -> reply.contains(fragment));
        }
    }

    /**
     * 清空已记录的回执
     */
    public void clearReplies() {
        replies.clear();
    }
}
