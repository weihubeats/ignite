package com.ignite.infra;

import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;

public class IgniteToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // 1. 创建原生的控制台视图
        ConsoleView consoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();

        // 2. 将控制台添加到工具窗口里
        Content content = toolWindow.getContentManager().getFactory().createContent(consoleView.getComponent(), "", false);
        toolWindow.getContentManager().addContent(content);

        // 3. 【关键】将创建好的 ConsoleView 塞给 Service
        // 这样 Service 里的 print 方法就有地方输出了
        IgniteConsoleService.getInstance(project).initConsoleView(consoleView);
    }
}
