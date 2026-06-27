package com.ignite.infra;

import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import java.awt.BorderLayout;
import javax.swing.JPanel;
import org.jetbrains.annotations.NotNull;

public class IgniteToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ConsoleView consoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();

        DefaultActionGroup toolbarGroup = new DefaultActionGroup();
        toolbarGroup.add(ActionManager.getInstance().getAction("IgniteRerun"));
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("IgniteConsoleToolbar", toolbarGroup, false);
        toolbar.setTargetComponent(consoleView.getComponent());

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(toolbar.getComponent(), BorderLayout.NORTH);
        panel.add(consoleView.getComponent(), BorderLayout.CENTER);

        ContentManager contentManager = toolWindow.getContentManager();
        ContentFactory contentFactory = contentManager.getFactory();
        Content content = contentFactory.createContent(panel, "", false);
        contentManager.addContent(content);

        IgniteConsoleService.getInstance(project).initConsoleView(consoleView);
    }
}
