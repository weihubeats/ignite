package com.ignite.action;

import com.ignite.infra.IgniteAutoAttacher;
import com.ignite.infra.IgniteNotifier;
import com.intellij.icons.AllIcons;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * 切换“新进程自动 attach Arthas”的开关（Run 菜单）。
 */
public class IgniteToggleAutoAttachAction extends ToggleAction {

    public IgniteToggleAutoAttachAction() {
        super("Ignite: 自动 Attach 新进程", "开启后，项目内启动的 Java 进程会被自动 attach Arthas", AllIcons.Actions.Execute);
    }

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
        return IgniteAutoAttacher.isAutoAttachEnabled();
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
        IgniteAutoAttacher.setAutoAttachEnabled(state);
        Project project = e.getProject();
        IgniteNotifier.notify(project,
            state ? "Ignite 自动 Attach 已开启" : "Ignite 自动 Attach 已关闭，手动 Ignite Run 不受影响",
            NotificationType.INFORMATION);
    }
}
