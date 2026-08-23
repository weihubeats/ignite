package com.ignite.infra;

import com.intellij.execution.ExecutionManager;
import com.intellij.execution.process.BaseProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import java.util.ArrayList;
import java.util.List;

public class PidUtils {

    public static List<RunningApp> getRunningApps(Project project) {
        List<RunningApp> apps = new ArrayList<>();
        if (project == null) return apps;

        // 在 ReadAction 中执行，避免线程问题
        return ReadAction.compute(() -> {
            ExecutionManager executionManager = ExecutionManager.getInstance(project);
            ProcessHandler[] runningProcesses = executionManager.getRunningProcesses();

            for (ProcessHandler handler : runningProcesses) {
                if (handler.isProcessTerminated() || handler.isProcessTerminating()) continue;

                String pid = getPidFromHandler(handler);
                if (pid != null) {
                    // handler.toString() 通常包含运行配置名
                    apps.add(new RunningApp(pid, handler.toString()));
                }
            }
            return apps;
        });
    }

    @SuppressWarnings("deprecation")
    public static String getPidFromHandler(ProcessHandler handler) {
        if (handler instanceof BaseProcessHandler) {
            Process process = ((BaseProcessHandler<?>) handler).getProcess();
            if (process != null) return String.valueOf(process.pid());
        }
        return null;
    }

    public static class RunningApp {
        public String pid;
        public String name;
        public String displayName; // 优化后的短名字

        public RunningApp(String pid, String rawName) {
            this.pid = pid;
            this.name = rawName;
            this.displayName = cleanName(rawName) + " (" + pid + ")";
        }

        // 【优化点】名称清洗逻辑
        private String cleanName(String raw) {
            if (raw == null) return "Unknown";
            // 1. 去除 "Run profile" 前缀
            String temp = raw.replace("Run profile '", "").replace("'", "");
            // 2. 如果包含包名，只取最后一段类名 (e.g., com.example.UserService -> UserService)
            if (temp.contains(".")) {
                int lastDot = temp.lastIndexOf('.');
                temp = lastDot != -1 ? temp.substring(lastDot + 1) : temp;
            }
            // 3. 去除 Application 后缀 (可选，UserServiceApplication -> UserService)
            if (temp.length() > 12 && temp.endsWith("Application")) {
                temp = temp.substring(0, temp.length() - 11);
            }
            return temp;
        }

        @Override
        public String toString() {
            return displayName; // 下拉框会显示这个
        }
    }
}
