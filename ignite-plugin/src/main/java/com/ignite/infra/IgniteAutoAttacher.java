package com.ignite.infra;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.ide.util.PropertiesComponent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class IgniteAutoAttacher implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(IgniteAutoAttacher.class);

    /** 自动 attach 开关（PropertiesComponent 持久化，默认开启） */
    public static final String AUTO_ATTACH_ENABLED_KEY = "IGNITE_AUTO_ATTACH_ENABLED";

    private static final ExecutorService ATTACH_EXECUTOR =
            Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "Ignite-AutoAttach-Thread");
                t.setDaemon(true);
                return t;
            });

    private static final Map<String, Boolean> attachedPids = new ConcurrentHashMap<>();

    public static boolean isAutoAttachEnabled() {
        return PropertiesComponent.getInstance().getBoolean(AUTO_ATTACH_ENABLED_KEY, true);
    }

    public static void setAutoAttachEnabled(boolean enabled) {
        PropertiesComponent.getInstance().setValue(AUTO_ATTACH_ENABLED_KEY, enabled, true);
    }

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {

        // 0. 预解压 Arthas JAR，让用户首次运行时不等待
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                ArthasController.getArthasJarPath();
            } catch (Exception e) {
                LOG.info("Failed to pre-extract Arthas JAR: " + e.getMessage());
            }
        });

        if (!isAutoAttachEnabled()) {
            return null;
        }

        // 1. 检查当前已经运行的进程并尝试 attach
        checkAndAttachRunningProcesses(project);

        // 2. 监听新启动的进程
        project.getMessageBus().connect().subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
            @Override
            public void processStarted(@NotNull String executorId, @NotNull ExecutionEnvironment env,
                @NotNull ProcessHandler handler) {
                if (!isAutoAttachEnabled()) {
                    return;
                }
                // 只自动 attach Java 应用运行配置：
                // 排除 Gradle daemon、测试 JVM 等短生命周期进程，避免注入开销与干扰构建
                var settings = env.getRunnerAndConfigurationSettings();
                if (settings == null
                    || !(settings.getConfiguration() instanceof ApplicationConfiguration)) {
                    return;
                }
                triggerBackgroundAttach(project, handler);
            }

            @Override
            public void processTerminated(@NotNull String executorId, @NotNull ExecutionEnvironment env,
                @NotNull ProcessHandler handler, int exitCode) {
                // 进程结束时清理记录
                String pid = PidUtils.getPidFromHandler(handler);
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
                        IgniteNotifier.notify(project, "Ignite 已自动连接到: " + app.displayName, NotificationType.INFORMATION);
                    } catch (Exception e) {
                        LOG.info("Auto-attach failed for PID " + app.pid + ": " + e.getMessage());
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

                String pid = PidUtils.getPidFromHandler(handler);
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
                        IgniteNotifier.notify(project, "Ignite 已自动连接到新启动的应用 (PID: " + pid + ")", NotificationType.INFORMATION);
                        return;
                    } catch (Exception e) {
                        // 还没准备好，继续等待
                        Thread.sleep(1000);
                        waitedSeconds++;
                    }
                }

                // 超时了，记录日志但不打扰用户
                LOG.info("Auto-attach timeout for PID " + pid);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
