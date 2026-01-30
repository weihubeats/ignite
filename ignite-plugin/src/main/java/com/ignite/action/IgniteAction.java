package com.ignite.action;

import com.ignite.core.CommandBuilder;
import com.ignite.core.DataGenerator;
import com.ignite.core.OgnlConverter;
import com.ignite.infra.ArthasController;
import com.ignite.infra.PidUtils;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;

import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import java.awt.Font;
import java.util.Arrays;
import javax.swing.Action;
import javax.swing.JComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class IgniteAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        PsiMethod method = (PsiMethod) e.getData(CommonDataKeys.PSI_ELEMENT);

        if (project == null || method == null) {
            showError(project, "请先将光标放置在 Java 方法名上");
            return;
        }

        // 1. 生成默认参数
        String defaultJson;
        try {
            defaultJson = DataGenerator.generateMethodArgsJson(method);
        } catch (Exception ex) {
            showError(project, "参数生成失败: " + ex.getMessage());
            return;
        }

        // 2. 使用自定义大弹窗让用户编辑参数 (修复输入框太小的问题)
        JsonInputDialog dialog = new JsonInputDialog(project, method.getName(), defaultJson);
        if (!dialog.showAndGet()) {
            return; // 用户点击取消
        }
        String jsonArgs = dialog.getInputJson();


        // 3. 异步执行任务
        new Task.Backgroundable(project, "正在执行 Ignite...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setText("正在连接目标进程...");
                    String pid = PidUtils.getCurrentAppPid(project);
                    if (pid == null) throw new RuntimeException("未找到运行中的 Java 进程！");

                    indicator.setText("检查 Arthas 状态...");
                    ArthasController.attach(pid);

                    indicator.setText("正在构建指令...");
                    String command = ReadAction.compute(() -> {
                        com.intellij.psi.PsiParameter[] parameters = method.getParameterList().getParameters();
                        String ognlArgs = OgnlConverter.jsonToOgnl(jsonArgs, parameters);
                        return CommandBuilder.build(method, ognlArgs);
                    });

                    indicator.setText("发送指令中...");
                    String rawResult = ArthasController.sendCommand(command);

                    // 4. 结果清洗 (修复返回值不友好的问题)
                    String friendlyResult = parseResult(rawResult);

                    ApplicationManager.getApplication().invokeLater(() -> {
                        // [修复点] 使用自定义的 ResultDialog 替代 Messages 工具类
                        new ResultDialog(
                            project,
                            "Ignite 执行结果",
                            friendlyResult
                        ).show();
                    });

                } catch (Exception ex) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        showError(project, "执行失败: " + ex.getMessage());
                    });
                    ex.printStackTrace();
                }
            }
        }.queue();
    }

    /**
     * 核心结果解析器
     * 去除 vmtool 命令回显，只提取 @Type[Value] 中的 Value
     */
    private String parseResult(String raw) {
        if (raw == null) return "";

        // 1. 按行分割
        String[] lines = raw.split("\n");
        StringBuilder resultBuilder = new StringBuilder();

        for (String line : lines) {
            line = line.trim();
            // 忽略包含 vmtool 命令本身的行 (这是 Telnet 的回显)
            if (line.startsWith("vmtool") || line.contains("--action")) {
                continue;
            }
            // 忽略空行
            if (line.isEmpty()) {
                continue;
            }

            // 2. 解析 Arthas 的返回值格式 @Type[Value]
            // 例如: @String[小奏佴子涵] -> 小奏佴子涵
            if (line.startsWith("@")) {
                int firstBracket = line.indexOf('[');
                int lastBracket = line.lastIndexOf(']');
                if (firstBracket > 0 && lastBracket > firstBracket) {
                    // 提取中括号里的内容
                    String content = line.substring(firstBracket + 1, lastBracket);
                    resultBuilder.append(content).append("\n");
                } else {
                    resultBuilder.append(line).append("\n");
                }
            } else {
                // 其他情况直接追加
                resultBuilder.append(line).append("\n");
            }
        }

        String finalRes = resultBuilder.toString().trim();
        return finalRes.isEmpty() ? "执行成功，无返回值 (void) 或结果为空。" : finalRes;
    }

    private void showError(Project project, String content) {
        Notifications.Bus.notify(new Notification("Ignite Notification Group", "Ignite Error", content, NotificationType.ERROR), project);
    }

    /**
     * 自定义 JSON 输入大弹窗
     */
    private static class JsonInputDialog extends DialogWrapper {
        private final JBTextArea textArea;

        protected JsonInputDialog(@Nullable Project project, String methodName, String defaultJson) {
            super(project);
            setTitle("Ignite Run - " + methodName);

            // 创建一个 15行 x 60列 的大文本域
            textArea = new JBTextArea(defaultJson, 15, 60);
            textArea.setFont(new Font("Monospaced", Font.PLAIN, 14)); // 等宽字体更适合 JSON
            textArea.setLineWrap(true);
            textArea.setWrapStyleWord(true);

            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            // 放入滚动面板，防止内容过多
            return new JBScrollPane(textArea);
        }

        @Override
        public @Nullable JComponent getPreferredFocusedComponent() {
            return textArea;
        }

        public String getInputJson() {
            return textArea.getText();
        }
    }

    /**
     * 自定义结果展示大弹窗 (只读)
     */
    private static class ResultDialog extends DialogWrapper {
        private final JBTextArea textArea;

        protected ResultDialog(@Nullable Project project, String title, String resultText) {
            super(project);
            setTitle(title);
            setModal(false); // 设置为非模态，这样用户可以一边看结果一边改代码

            // 创建大文本域 (15行 x 60列)
            textArea = new JBTextArea(resultText, 15, 60);
            textArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
            textArea.setLineWrap(true);
            textArea.setWrapStyleWord(true);
            textArea.setEditable(false); // 关键：设置为只读

            // 自动滚动到顶部
            textArea.setCaretPosition(0);

            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            return new JBScrollPane(textArea);
        }

        // 只显示 OK 按钮，不显示 Cancel
        @Override
        protected Action[] createActions() {
            return new Action[]{getOKAction()};
        }
    }
}
