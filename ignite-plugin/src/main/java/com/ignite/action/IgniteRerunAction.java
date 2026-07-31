package com.ignite.action;

import com.ignite.core.IgniteMethodExecutor;
import com.ignite.infra.IgniteLastRunService;
import com.ignite.infra.LastRunRecord;
import com.ignite.infra.PidUtils;
import com.intellij.icons.AllIcons;
import com.intellij.notification.NotificationType;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;

public class IgniteRerunAction extends AnAction {

    public IgniteRerunAction() {
        super("Ignite Rerun Last", "使用上次的参数重新运行最近一次 Ignite 调用", AllIcons.Actions.Restart);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        IgniteLastRunService lastRunService = project != null ? IgniteLastRunService.getInstance(project) : null;
        boolean hasLast = lastRunService != null && lastRunService.hasLastRun();
        e.getPresentation().setEnabled(hasLast);
        if (hasLast) {
            String desc = lastRunService.getLastRunDescription();
            if (desc != null) {
                e.getPresentation().setDescription(desc);
            }
        }
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        IgniteLastRunService lastRunService = IgniteLastRunService.getInstance(project);
        LastRunRecord record = lastRunService.getLastRun();
        if (record == null) {
            showNotification(project, "暂无上次运行记录，请先通过 Ignite Run 执行一次方法", NotificationType.WARNING);
            return;
        }

        PsiMethod method = lastRunService.resolveMethod(project, record);
        if (method == null) {
            showNotification(
                project,
                "无法定位方法: " + record.getMethodSignature() + "，请确认源码仍在项目中",
                NotificationType.ERROR
            );
            return;
        }

        PidUtils.RunningApp selectedApp = lastRunService.resolveRunningApp(project, record);
        if (selectedApp == null) {
            showNotification(
                project,
                "上次目标进程 (PID: " + record.getPid() + ") 已不在运行，请重新 Ignite Run 选择服务",
                NotificationType.WARNING
            );
            return;
        }

        IgniteMethodExecutor.execute(project, method, record.getJsonArgs(), selectedApp);
    }

    private void showNotification(Project project, String content, NotificationType type) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Ignite Notification Group")
            .createNotification(content, type)
            .notify(project);
    }
}
