package com.ignite.infra;

import com.intellij.execution.ExecutionManager;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.project.Project;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

public class PidUtils {

    public static List<RunningApp> getRunningApps(Project project) {
        List<RunningApp> apps = new ArrayList<>();
        if (project == null) return apps;

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
    }

    private static String getPidFromHandler(ProcessHandler handler) {
        try {
            Method getProcessMethod = handler.getClass().getMethod("getProcess");
            getProcessMethod.setAccessible(true);
            Process process = (Process) getProcessMethod.invoke(handler);
            if (process != null) return String.valueOf(process.pid());
        } catch (Exception ignored) {}
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
                temp = StringUtils.substringAfterLast(temp, ".");
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
