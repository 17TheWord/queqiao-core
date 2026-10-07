package io.github.theword.queqiao.core.command;

import io.github.theword.queqiao.core.constant.CommandConstant;
import io.github.theword.queqiao.core.utils.Tool;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 命令树节点
 *
 * <p>命令树中的一个节点，既可以是根节点，也可以是任意层级的子节点。
 *
 * <p><b>命令来源</b>：命令层只认识 {@link CommandExecutionContext} ——
 * 不认识任何平台类型，也不通过 {@code AbstractPlatformContext} 回执或判断权限。
 *
 * <h2>扩展点</h2>
 * <p>第三方命令只需覆盖以下方法：
 * <ul>
 *     <li>{@link #getName()} —— 节点名（同层唯一，忽略大小写）；</li>
 *     <li>{@link #getDescription()} —— 描述；</li>
 *     <li>{@link #getUsage()} / {@link #getPermissionNode()} —— 可选覆盖；</li>
 *     <li>{@link #onExecute} —— <b>实际命令业务扩展点</b>。</li>
 * </ul>
 * 其余方法均为 {@code final}：它们承担 Core 不变量（结构封装、注册校验、执行模板），
 * 不允许通过覆盖绕过。
 *
 * <h2>线程安全契约（重要）</h2>
 * <p>同一个 {@code CommandNode} 实例可能处理多个命令调用，实现<b>不得</b>把单次 invocation
 * 的可变状态（当前 sender / args / context / player）存储在实例字段中。
 * Invocation 状态必须全部来自方法参数；实例字段只允许存放构造期注入的不可变依赖。
 *
 * <h2>注册生命周期</h2>
 * <pre>
 * registration phase（可 addChild）
 *         ↓
 * command tree completed（freeze）
 *         ↓
 * dispatch phase（execute / tabComplete；不得再修改结构）
 * </pre>
 * 结构进入 dispatch 阶段后<b>不得并发修改</b>。{@link #freeze()} 之后任何
 * {@link #addChild} 都会快速失败，因此上述不变量无法被绕过。
 *
 * @param <NCS> Native Command Source，平台原生命令来源类型
 * @since 0.5.0
 */
public abstract class CommandNode<NCS> {

    /**
     * 父命令节点；根节点为 null
     *
     * <p>私有：外部只能通过 {@link #getParent()} 读取，不得直接改写。
     */
    private CommandNode<NCS> parent;

    /**
     * 子命令列表
     *
     * <p>私有：外部只能通过 {@link #getChildren()} 读取只读视图，
     * 结构修改必须经过 {@link #addChild}，以保证注册不变量无法被绕过。
     */
    private final List<CommandNode<NCS>> children = new ArrayList<>();

    /**
     * 日志实现
     */
    protected final Logger logger;

    /**
     * 结构是否已冻结
     *
     * <p>{@code volatile}：freeze 可能在一个线程发生，而 execute 在另一个线程读取。
     */
    private volatile boolean frozen = false;

    /**
     * 构造命令节点
     *
     * @param logger 日志实现，不得为 null
     */
    protected CommandNode(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // ------------------------------------------------------------------
    // 结构注册（registration phase）
    // ------------------------------------------------------------------

    /**
     * 添加子命令
     *
     * <p><b>注册不变量（全部 fail-fast，抛 {@link IllegalArgumentException}）</b>：
     * <ol>
     *     <li>{@code child} 不得为 null；</li>
     *     <li>子命令名称不得为 null 或空白；</li>
     *     <li>同一父节点下名称不得重复（忽略大小写，与路由的匹配规则一致）；</li>
     *     <li>{@code child} 不得已经属于另一个父节点 —— 不自动 detach；</li>
     *     <li>不得形成环（{@code child} 不能是自己或自己的祖先）；</li>
     *     <li>结构已冻结时不得再注册。</li>
     * </ol>
     *
     * <p>刻意不做静默覆盖：重复注册属于装配错误，应在构建期就暴露，
     * 而不是等到运行期表现为"某个子命令神秘消失"。
     *
     * @param child 子命令，不得为 null
     * @throws IllegalArgumentException 违反上述任一不变量
     * @throws IllegalStateException    结构已冻结
     */
    public final void addChild(CommandNode<NCS> child) {
        Objects.requireNonNull(child, "child");

        if (frozen) {
            throw new IllegalStateException(
                    "命令树已冻结，不能再注册子命令：" + child.getName()
                            + "。结构注册必须发生在 dispatch 开始之前。");
        }

        String childName = child.getName();
        if (childName == null || childName.trim().isEmpty()) {
            throw new IllegalArgumentException("子命令名称不能为 null 或空白");
        }

        if (child.parent != null) {
            throw new IllegalArgumentException(
                    "命令节点 " + childName + " 已属于父节点 " + child.parent.getName()
                            + "，不能重复注册到 " + getName());
        }

        if (this.isSelfOrAncestor(child)) {
            throw new IllegalArgumentException(
                    "注册 " + childName + " 到 " + getName() + " 会形成命令树环");
        }

        for (CommandNode<NCS> existing : children) {
            if (existing.getName().equalsIgnoreCase(childName)) {
                throw new IllegalArgumentException(
                        "同一父节点下已存在同名子命令：" + childName);
            }
        }

        child.parent = this;
        this.children.add(child);
    }

    /**
     * 冻结整棵命令树的结构
     *
     * <p><b>幂等</b>：重复调用无副作用。
     *
     * <p>冻结后：{@link #addChild} 抛 {@link IllegalStateException}，
     * 从而保证 dispatch 阶段不会与结构修改并发。
     *
     * <p>{@link CommandRouter#execute} / {@link CommandRouter#tabComplete} 会在首次调用时
     * 自动冻结，因此正常路径无需手动调用本方法；需要在装配完成后立即封板时可显式调用。
     */
    public final void freeze() {
        if (frozen) {
            return;
        }
        frozen = true;
        for (CommandNode<NCS> child : children) {
            child.freeze();
        }
    }

    /**
     * @return 结构是否已冻结
     */
    public final boolean isFrozen() {
        return frozen;
    }

    /**
     * 判断本节点是否等于 {@code candidate} 或为其祖先
     *
     * <p>用于环检测：把 {@code candidate} 挂到本节点之下时，
     * 若本节点自身就是 {@code candidate}，或 {@code candidate} 出现在本节点的祖先链上，
     * 那么 {@code candidate.parent = this} 会形成环。
     *
     * <p>例：{@code child} 的父是 {@code root}，此时执行 {@code child.addChild(root)}
     * —— 本节点 {@code child} 是 {@code root} 的后代，判定为环。
     *
     * @param candidate 待检查节点
     * @return {@code candidate} 是本节点自身或本节点的祖先时为 true
     */
    private boolean isSelfOrAncestor(CommandNode<NCS> candidate) {
        CommandNode<NCS> current = this;
        while (current != null) {
            if (current == candidate) {
                return true;
            }
            current = current.parent;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 结构读取
    // ------------------------------------------------------------------

    /**
     * 获取所有子命令
     *
     * @return 只读的子命令列表
     */
    public final List<CommandNode<NCS>> getChildren() {
        return Collections.unmodifiableList(children);
    }

    /**
     * 获取父命令
     *
     * @return 父命令节点，根节点返回 null
     */
    public final CommandNode<NCS> getParent() {
        return parent;
    }

    /**
     * 判断是否为根命令
     *
     * @return 是否为根命令
     */
    public final boolean isRoot() {
        return parent == null;
    }

    /**
     * 获取完整命令路径
     *
     * <p>示例：/queqiao client list
     *
     * @return 完整命令路径
     */
    public final String getFullPath() {
        if (parent == null) {
            return "/" + getName();
        }

        List<String> path = new ArrayList<>();
        CommandNode<NCS> current = this;
        while (current != null) {
            path.add(0, current.getName());
            current = current.parent;
        }

        return "/" + String.join(" ", path);
    }

    /**
     * 获取完整权限节点
     *
     * <p>从根节点开始拼接，示例：queqiao.client.list
     *
     * @return 完整权限节点
     */
    public final String getFullPermissionNode() {
        List<String> path = new ArrayList<>();
        CommandNode<NCS> current = this;
        while (current != null) {
            path.add(0, current.getName());
            current = current.parent;
        }
        return String.join(".", path);
    }

    // ------------------------------------------------------------------
    // 扩展点
    // ------------------------------------------------------------------

    /**
     * 获取命令名称
     *
     * @return 命令名称
     */
    public abstract String getName();

    /**
     * 获取命令描述
     *
     * @return 命令描述
     */
    public abstract String getDescription();

    /**
     * 获取命令用法
     *
     * <p>默认返回完整路径，子类可以覆盖以添加参数说明
     *
     * @return 命令用法
     */
    public String getUsage() {
        return getFullPath();
    }

    /**
     * 获取命令权限节点
     *
     * <p>默认返回完整权限节点，子类可以覆盖以自定义权限
     *
     * @return 权限节点
     */
    public String getPermissionNode() {
        return getFullPermissionNode();
    }

    // ------------------------------------------------------------------
    // 执行（dispatch phase）
    // ------------------------------------------------------------------

    /**
     * 执行命令
     *
     * <p><b>模板方法</b>：权限判定、分隔线、异常兜底都由本方法负责，
     * 子类不应覆盖（本方法为 {@code final}），业务逻辑写在 {@link #onExecute}。
     *
     * <p><b>异常处理</b>：命令体的异常只回执一句通用提示，完整堆栈写入日志 ——
     * 不把第三方命令的异常信息（可能含文件路径、内部状态、token / URL）暴露给命令调用者。
     *
     * @param context 本次命令调用的执行上下文，不得为 null
     * @param args    命令参数，不得为 null（无参数传空列表）
     * @return 执行信号（见 {@link CommandConstant}）
     * @throws NullPointerException context 或 args 为 null
     * @since 0.5.0
     */
    public final int execute(CommandExecutionContext<NCS> context, List<String> args) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(args, "args");

        try {
            if (!context.hasPermission(getPermissionNode())) {
                context.reply("您没有权限执行此命令。");
                return CommandConstant.FAIL_SIGNAL;
            }
            context.reply("============ 鹊桥 ===========");
            onExecute(context, args);
            context.reply("============================");
            return CommandConstant.SUCCESS_SIGNAL;
        } catch (Exception e) {
            context.reply("命令执行出错，请查看服务端日志。");
            logger.error("命令执行出错", e);
            return CommandConstant.FAIL_SIGNAL;
        }
    }

    /**
     * 执行命令逻辑
     *
     * <p><b>这是实际命令业务的唯一扩展点。</b>
     *
     * <p>入参均已由 {@link #execute} 校验非 null；实现<b>不得</b>把 context / args
     * 保存到实例字段（见类级线程安全契约）。
     *
     * @param context 本次命令调用的执行上下文，永不为 null
     * @param args    命令参数，永不为 null
     */
    protected abstract void onExecute(CommandExecutionContext<NCS> context, List<String> args);

    /**
     * 递归发送指定命令以及所有子命令的树形结构
     *
     * @param context 本次命令调用的执行上下文，不得为 null
     * @param command 当前命令节点，不得为 null
     * @throws NullPointerException context 或 command 为 null
     */
    public final void sendCommandTree(CommandExecutionContext<NCS> context, CommandNode<NCS> command) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(command, "command");

        String msg = Tool.format("{} - {}", command.getUsage(), command.getDescription());
        context.reply(msg);

        for (CommandNode<NCS> child : command.getChildren()) {
            sendCommandTree(context, child);
        }
    }
}
