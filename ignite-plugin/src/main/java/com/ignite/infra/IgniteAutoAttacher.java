package com.ignite.infra;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class IgniteAutoAttacher implements ProjectActivity {

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {

        project.getMessageBus().connect().subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
            @Override
            public void processStarted(@NotNull String executorId, @NotNull ExecutionEnvironment env,
                @NotNull ProcessHandler handler) {
                // 只处理 Java 相关的运行配置
                triggerBackgroundAttach(project, handler);
            }
        });
        return null;
    }

    private void triggerBackgroundAttach(Project project, ProcessHandler handler) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                // 等待 JVM 初始化
                Thread.sleep(5000);

                if (handler.isProcessTerminated() || handler.isProcessTerminating()) {
                    return;
                }

                String pid = getPidFromHandler(handler);

                if (pid != null) {
                    // System.out.println("[Ignite] Detected app start (PID: " + pid + "), auto-attaching...");
                    try {
                        ArthasController.attach(pid);
                    } catch (Exception ignored) {
                        // 自动附着失败忽略即可，不打扰用户
                    }
                }
            } catch (InterruptedException ignored) {
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
}