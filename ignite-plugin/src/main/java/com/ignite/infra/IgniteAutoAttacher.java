package com.ignite.infra;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class IgniteAutoAttacher implements ProjectActivity {

    // 线程池用于后台 attach 任务
    private static final ExecutorService ATTACH_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "Ignite-AutoAttach-Thread");
        t.setDaemon(true);
        return t;
    });

    // 记录已 attach 的 PID，避免重复 attach
    private static final Map<String, Boolean> attachedPids = new ConcurrentHashMap<>();

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {

        // 1. 检查当前已经运行的进程并尝试 attach
        checkAndAttachRunningProcesses(project);

        // 2. 监听新启动的进程
        project.getMessageBus().connect().subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
            @Override
            public void processStarted(@NotNull String executorId, @NotNull ExecutionEnvironment env,
                @NotNull ProcessHandler handler) {
                triggerBackgroundAttach(project, handler);
            }

            @Override
            public void processTerminated(@NotNull String executorId, @NotNull ExecutionEnvironment env,
                @NotNull ProcessHandler handler, int exitCode) {
                // 进程结束时清理记录
                String pid = getPidFromHandler(handler);
                if (pid != null) {
                    attachedPids.remove(pid);
                }
            }
        });
        return null;
    }

    /**
     * 检查并 attach 当前正在运行的进程
     */
    private void checkAndAttachRunningProcesses(Project project) {
        ATTACH_EXECUTOR.submit(() -> {
            try {
                // 等待一小段时间确保 IDE 完全初始化
                Thread.sleep(2000);

                var runningApps = PidUtils.getRunningApps(project);
                if (runningApps.isEmpty()) {
                    return;
                }

                for (var app : runningApps) {
                    if (attachedPids.containsKey(app.pid)) {
                        continue; // 已经 attach 过了
                    }

                    try {
                        ArthasController.attach(app.pid);
                        attachedPids.put(app.pid, true);
                        showNotification(project, "Ignite 已自动连接到: " + app.displayName, NotificationType.INFORMATION);
                    } catch (Exception e) {
                        System.out.println("[Ignite] Auto-attach failed for PID " + app.pid + ": " + e.getMessage());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void triggerBackgroundAttach(Project project, ProcessHandler handler) {
        ATTACH_EXECUTOR.submit(() -> {
            try {
                // 等待 JVM 完全初始化（Spring Boot 等应用需要更长时间）
                // 分阶段等待：先等 3 秒，然后每 1 秒检查一次，最多等 30 秒
                int maxWaitSeconds = 30;
                int waitedSeconds = 0;

                while (waitedSeconds < 3) {
                    Thread.sleep(1000);
                    waitedSeconds++;
                    if (handler.isProcessTerminated() || handler.isProcessTerminating()) {
                        return;
                    }
                }

                String pid = getPidFromHandler(handler);
                if (pid == null || attachedPids.containsKey(pid)) {
                    return;
                }

                // 继续等待应用启动完成（通过尝试 attach 来判断）
                while (waitedSeconds < maxWaitSeconds) {
                    if (handler.isProcessTerminated() || handler.isProcessTerminating()) {
                        return;
                    }

                    try {
                        ArthasController.attach(pid);
                        attachedPids.put(pid, true);
                        showNotification(project, "Ignite 已自动连接到新启动的应用 (PID: " + pid + ")", NotificationType.INFORMATION);
                        return;
                    } catch (Exception e) {
                        // 还没准备好，继续等待
                        Thread.sleep(1000);
                        waitedSeconds++;
                    }
                }

                // 超时了，记录日志但不打扰用户
                System.out.println("[Ignite] Auto-attach timeout for PID " + pid);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private String getPidFromHandler(ProcessHandler handler) {
        try {
            if (handler instanceof com.intellij.execution.process.BaseProcessHandler) {
                Process process = ((com.intellij.execution.process.BaseProcessHandler<?>) handler).getProcess();
                return String.valueOf(process.pid());
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private void showNotification(Project project, String content, NotificationType type) {
        Notifications.Bus.notify(new Notification("Ignite Notification Group", "Ignite", content, type), project);
    }
}