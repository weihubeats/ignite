package com.ignite.infra;

import com.intellij.execution.ExecutionManager;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.project.Project;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class PidUtils {

    /**
     * 自动查找当前项目中正在运行的 Java 进程 PID
     */
    public static String getCurrentAppPid(Project project) {
        if (project == null) return null;

        ExecutionManager executionManager = ExecutionManager.getInstance(project);

        // 策略优化：不再依赖 getSelectedContent()，而是遍历所有正在运行的进程
        ProcessHandler[] runningProcesses = executionManager.getRunningProcesses();

        for (ProcessHandler handler : runningProcesses) {
            // 1. 过滤掉已经结束的进程
            if (handler.isProcessTerminated() || handler.isProcessTerminating()) {
                continue;
            }

            // 2. 尝试提取 PID
            String pid = getPidFromHandler(handler);
            if (pid != null) {
                return pid;
            }
        }

        return null;
    }

    private static String getPidFromHandler(ProcessHandler handler) {
        try {
            // IDEA 的 ProcessHandler 实现类通常都有一个 getProcess() 方法返回原生 Process 对象
            // 我们通过反射调用它，避免依赖具体的实现类 (如 OSProcessHandler)
            Method getProcessMethod = handler.getClass().getMethod("getProcess");
            getProcessMethod.setAccessible(true);
            Process process = (Process) getProcessMethod.invoke(handler);

            // Java 9+ 原生支持 process.pid()，这是最稳健的方法
            // 你的项目编译级别是 Java 21，所以可以直接用
            if (process != null) {
                return String.valueOf(process.pid());
            }
        } catch (NoSuchMethodException e) {
            // 某些特殊的 ProcessHandler 可能没有 getProcess 方法，忽略
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
