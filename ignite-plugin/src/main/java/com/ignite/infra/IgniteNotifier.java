package com.ignite.infra;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

public final class IgniteNotifier {

    public static final String NOTIFICATION_GROUP_ID = "Ignite Notification Group";

    private IgniteNotifier() {}

    public static void notify(@Nullable Project project, String content, NotificationType type) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(content, type)
            .notify(project);
    }
}
