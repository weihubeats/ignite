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
import org.jetbrains.annotations.Nullable;

public final class IgniteMethodExecutor {

    private IgniteMethodExecutor() {}

    /**
     * @param onCommandAccepted 命令成功送达 Arthas 后回调（用于参数记忆/最近一次运行记录），可为 null
     */
    public static void execute(
        @NotNull Project project,
        @NotNull PsiMethod method,
        @NotNull String jsonArgs,
        @NotNull PidUtils.RunningApp selectedApp,
        @Nullable Runnable onCommandAccepted
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
                        if (!method.isValid()) {
                            throw new IllegalStateException("方法定义已失效（源码可能已变更），请重新定位后执行");
                        }
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
                    console.printLog("[Ignite] 发送命令到 Arthas 并等待结果...");
                    // 同步等待执行结果：方法返回值/异常直接展示到 Console；
                    // Debug 断点暂停场景由 telnet 超时兜底，不会无限阻塞
                    String result = ArthasController.sendCommand(selectedApp.pid, command, indicator::isCanceled);

                    // 命令成功送达后才记忆参数与最近一次运行记录
                    if (onCommandAccepted != null) {
                        onCommandAccepted.run();
                    }

                    String methodSig = ReadAction.nonBlocking(() -> {
                        if (!method.isValid()) {
                            return "(方法定义已失效)";
                        }
                        String className = method.getContainingClass() != null
                            ? method.getContainingClass().getQualifiedName()
                            : "UnknownClass";
                        return className + "." + method.getName();
                    }).executeSynchronously();

                    String finalResult = result;
                    ApplicationManager.getApplication().invokeLater(() ->
                        console.printExecution(selectedApp.displayName, methodSig, jsonArgs, finalResult)
                    );
                } catch (Exception ex) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        // 用户主动取消：不打扰，仅记录日志
                        if (indicator.isCanceled()) {
                            console.printLog("[Ignite] 执行已取消");
                            return;
                        }
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

    /** 兼容旧签名 */
    public static void execute(
        @NotNull Project project,
        @NotNull PsiMethod method,
        @NotNull String jsonArgs,
        @NotNull PidUtils.RunningApp selectedApp
    ) {
        execute(project, method, jsonArgs, selectedApp, null);
    }
}
