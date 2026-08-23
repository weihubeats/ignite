package com.ignite.core;

import com.ignite.infra.ArthasController;
import com.ignite.infra.IgniteConsoleService;
import com.ignite.infra.IgniteNotifier;
import com.ignite.infra.PidUtils;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.PerformInBackgroundOption;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import org.jetbrains.annotations.NotNull;

public final class IgniteMethodExecutor {

    private IgniteMethodExecutor() {}

    public static void execute(
        @NotNull Project project,
        @NotNull PsiMethod method,
        @NotNull String jsonArgs,
        @NotNull PidUtils.RunningApp selectedApp
    ) {
        new Task.Backgroundable(project, "Ignite: " + method.getName(), true, PerformInBackgroundOption.ALWAYS_BACKGROUND) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                IgniteConsoleService console = IgniteConsoleService.getInstance(project);
                try {
                    indicator.setText("检查连接状态...");
                    console.printLog("[Ignite] 检查 Arthas 连接状态...");

                    if (!ArthasController.isArthasConnected(selectedApp.pid)) {
                        indicator.setText("连接到 " + selectedApp.displayName + "...");
                        console.printLog("[Ignite] 连接到 " + selectedApp.displayName + " (PID: " + selectedApp.pid + ")");
                        ArthasController.attach(selectedApp.pid, console);
                    } else {
                        console.printLog("[Ignite] Arthas 已连接，直接使用现有连接");
                    }

                    indicator.setText("构建指令...");
                    console.printLog("[Ignite] 构建 OGNL 指令...");
                    String arthasLibPath = ArthasController.getArthasLibPath();
                    String[] commandAndOgnl = ReadAction.nonBlocking(() -> {
                        PsiParameter[] parameters = method.getParameterList().getParameters();
                        String ognlArgs = OgnlConverter.jsonToOgnl(jsonArgs, parameters);
                        String cmd = CommandBuilder.build(method, ognlArgs, arthasLibPath);
                        StringBuilder paramNames = new StringBuilder();
                        for (int i = 0; i < parameters.length; i++) {
                            if (i > 0) {
                                paramNames.append(", ");
                            }
                            paramNames.append(parameters[i].getName());
                        }
                        return new String[]{cmd, "params=[" + paramNames + "] ognlArgs=" + ognlArgs};
                    }).executeSynchronously();
                    String command = commandAndOgnl[0];
                    console.printLog("[Ignite] " + commandAndOgnl[1]);
                    console.printLog("[Ignite] 指令: " + command);

                    indicator.setText("执行中...");
                    console.printLog("[Ignite] 发送命令到 Arthas...");
                    ArthasController.sendCommandWithoutResult(command);
                    console.printLog("[Ignite] 命令已发送（不等待方法返回结果）");

                    if (!ArthasController.isArthasConnected(selectedApp.pid)) {
                        indicator.setText("连接断开，重新连接...");
                        console.printLog("[Ignite] 连接断开，尝试重新 attach...");
                        ArthasController.attach(selectedApp.pid, console);
                        ArthasController.sendCommandWithoutResult(command);
                        console.printLog("[Ignite] 重试完成");
                    }

                    String methodSig = ReadAction.nonBlocking(() -> {
                        String className = method.getContainingClass() != null
                            ? method.getContainingClass().getQualifiedName()
                            : "UnknownClass";
                        return className + "." + method.getName();
                    }).executeSynchronously();

                    ApplicationManager.getApplication().invokeLater(() ->
                        console.printExecution(selectedApp.displayName, methodSig, jsonArgs)
                    );
                } catch (Exception ex) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        String msg = ex.getMessage();
                        if (msg != null && (msg.contains("Read timed out") || msg.contains("timeout"))) {
                            msg += "\n\n【提示】: 检测到超时。如果当前处于 Debug 断点暂停状态，Arthas 无法执行代码。请 Resume 放行断点后再试。";
                        }
                        IgniteNotifier.notify(project, "执行失败: " + msg, NotificationType.ERROR);
                    });
                }
            }
        }.queue();
    }
}
