package io.github.theword.queqiao.core.support;

import io.github.theword.queqiao.core.platform.CommandSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 记录型命令来源测试替身
 *
 * <p>记录收到的每一条回执，并可用固定值回答权限判定——
 * 用于验证命令层"权限通过 / 权限拒绝"两条路径，以及回执内容是否正确。
 *
 * <p>权限返回固定值（而不是按节点判断）是有意的：命令层只需要一个可控的布尔结果，
 * 按节点判断属于平台实现细节，由平台自己的测试覆盖。
 */
public final class FakeCommandSource implements CommandSource {

    private final boolean permissionGranted;
    private final List<String> replies = Collections.synchronizedList(new ArrayList<>());

    /**
     * 构造默认允许全部权限的命令来源
     */
    public FakeCommandSource() {
        this(true);
    }

    /**
     * @param permissionGranted {@link #hasPermission(String)} 的固定返回值
     */
    public FakeCommandSource(boolean permissionGranted) {
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
