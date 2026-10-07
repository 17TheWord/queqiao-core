package io.github.theword.queqiao.core.command.builtin;

import io.github.theword.queqiao.core.command.CommandExecutionContext;
import io.github.theword.queqiao.core.command.CommandNode;
import org.slf4j.Logger;

import java.util.List;

public class HelpCommand<NCS> extends CommandNode<NCS> {

    public HelpCommand(Logger logger) {
        super(logger);
    }

    /**
     * 获取命令名称
     *
     * @return help
     */
    @Override
    public String getName() {
        return "help";
    }

    /**
     * 获取命令描述
     *
     * @return 获取命令帮助
     */
    @Override
    public String getDescription() {
        return "获取命令帮助";
    }

    /**
     * 执行命令 获取所有命令使用方法
     *
     * @param context 命令执行上下文
     * @param args            命令参数
     */
    @Override
    protected void onExecute(CommandExecutionContext<NCS> context, List<String> args) {
        CommandNode<NCS> root = this;
        while (root.getParent() != null) {
            root = root.getParent();
        }
        sendCommandTree(context, root);
    }
}
