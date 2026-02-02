package com.ignite.action;

import com.ignite.core.CommandBuilder;
import com.ignite.core.DataGenerator;
import com.ignite.core.OgnlConverter;
import com.ignite.infra.ArthasController;
import com.ignite.infra.IgniteConsoleService;
import com.ignite.infra.PidUtils;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.lang.Language;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;

import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.LanguageTextField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class IgniteRunAction extends AnAction {

    private static final String HISTORY_KEY_PREFIX = "IGNITE_HISTORY_";

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        PsiMethod method = (PsiMethod) e.getData(CommonDataKeys.PSI_ELEMENT);

        if (project == null || method == null) {
            showNotification(project, "请先将光标放置在 Java 方法名上", NotificationType.WARNING);
            return;
        }

        List<PidUtils.RunningApp> runningApps = PidUtils.getRunningApps(project);
        if (runningApps.isEmpty()) {
            showNotification(project, "未找到运行中的 Java 服务！", NotificationType.WARNING);
            return;
        }

        // 1. 【参数记忆】尝试读取历史记录
        String cacheKey = getCacheKey(method);
        String historyJson = PropertiesComponent.getInstance(project).getValue(cacheKey);

        String initialJson;
        if (historyJson != null && !historyJson.isBlank()) {
            initialJson = historyJson; // 有历史用历史
        } else {
            try {
                initialJson = DataGenerator.generateMethodArgsJson(method); // 没历史生成 Mock
            } catch (Exception ex) {
                initialJson = "{}";
            }
        }

        // 2. 弹出窗口
        IgniteDialog dialog = new IgniteDialog(project, method.getName(), initialJson, runningApps);
        if (!dialog.showAndGet())
            return;

        String jsonArgs = dialog.getJsonInput();
        PidUtils.RunningApp selectedApp = dialog.getSelectedApp();

        // 3. 【参数记忆】保存本次运行的参数
        PropertiesComponent.getInstance(project).setValue(cacheKey, jsonArgs);

        // 4. 异步执行
        new Task.Backgroundable(project, "Ignite: " + method.getName(), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setText("连接 " + selectedApp.displayName + "...");
                    ArthasController.attach(selectedApp.pid);

                    indicator.setText("构建指令...");
                    String command = ReadAction.compute(() -> {
                        com.intellij.psi.PsiParameter[] parameters = method.getParameterList().getParameters();
                        String ognlArgs = OgnlConverter.jsonToOgnl(jsonArgs, parameters);
                        return CommandBuilder.build(method, ognlArgs);
                    });

                    indicator.setText("执行中...");
                    String rawResult = ArthasController.sendCommand(command);
                    String friendlyResult = parseResult(rawResult);

                    String className = method.getContainingClass() != null ? method.getContainingClass().getQualifiedName() : "UnknownClass";
                    String methodSig = className + "." + method.getName();

                    // 2. 调用新的打印方法
                    ApplicationManager.getApplication().invokeLater(() -> {
                        IgniteConsoleService.getInstance(project).printExecution(
                            selectedApp.displayName,  // 服务名 (e.g. UserService (12345))
                            methodSig,                // 方法签名
                            jsonArgs,                 // 入参 JSON
                            friendlyResult            // 结果 JSON
                        );
                    });

                } catch (Exception ex) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        String msg = ex.getMessage();
                        // 针对 Debug 超时的友好提示
                        if (msg.contains("Read timed out") || msg.contains("timeout")) {
                            msg += "\n\n【提示】: 检测到超时。如果当前处于 Debug 断点暂停状态，Arthas 无法执行代码。请 Resume 放行断点后再试。";
                        }
                        showNotification(project, "执行失败: " + msg, NotificationType.ERROR);
                    });
                }
            }
        }.queue();
    }

    // 生成唯一的缓存 Key: 包名.类名.方法名(参数个数)
    private String getCacheKey(PsiMethod method) {
        String className = method.getContainingClass() != null ? method.getContainingClass().getQualifiedName() : "Unknown";
        return HISTORY_KEY_PREFIX + className + "." + method.getName() + "_" + method.getParameterList().getParametersCount();
    }

    // 在编辑器中打开结果 (JSON 高亮)
    private void openResultInEditor(Project project, String appName, String methodName, String content) {
        // 创建一个内存中的虚拟文件
        String fileName = "Ignite_Result_" + methodName + ".json";
        LightVirtualFile virtualFile = new LightVirtualFile(fileName, content);

        // 尝试设置语言为 JSON 以获得高亮
        Language jsonLang = Language.findLanguageByID("JSON");
        if (jsonLang != null) {
            virtualFile.setLanguage(jsonLang);
        }

        // 打开文件
        FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, virtualFile), true);

        // 发个轻量通知告知用户
        showNotification(project, "执行成功，结果已在编辑器打开", NotificationType.INFORMATION);
    }

    private static class IgniteDialog extends DialogWrapper {
        private final Project project;
        private final List<PidUtils.RunningApp> apps;
        private ComboBox<PidUtils.RunningApp> appComboBox;
        private LanguageTextField jsonEditor;
        private final String defaultJson;

        protected IgniteDialog(Project project, String methodName, String defaultJson, List<PidUtils.RunningApp> apps) {
            super(project, true); // true = canBeParent
            this.project = project;
            this.apps = apps;
            this.defaultJson = defaultJson;
            setTitle("Ignite Run - " + methodName);
            setResizable(true); // 【窗口优化】允许调整大小
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout(0, 10));

            // 顶部：服务选择
            JPanel topPanel = new JPanel(new BorderLayout());
            topPanel.add(new JLabel("目标服务: "), BorderLayout.WEST);
            appComboBox = new ComboBox<>(apps.toArray(new PidUtils.RunningApp[0]));
            if (!apps.isEmpty())
                appComboBox.setSelectedIndex(0);
            topPanel.add(appComboBox, BorderLayout.CENTER);
            panel.add(topPanel, BorderLayout.NORTH);

            // 中间：JSON 编辑器
            Language jsonLanguage = Language.findLanguageByID("JSON");
            if (jsonLanguage == null)
                jsonLanguage = Language.ANY;

            jsonEditor = new LanguageTextField(jsonLanguage, project, defaultJson, false);
            jsonEditor.setOneLineMode(false); // 允许多行

            // 自动格式化一下 JSON
            formatJson(jsonEditor.getDocument(), project);

            // 包装在 ScrollPane 里看起来更像编辑器
            JScrollPane scrollPane = new JBScrollPane(jsonEditor);
            // 【窗口优化】设置一个适中的首选大小 (Goldilocks size)
            scrollPane.setPreferredSize(JBUI.size(600, 400));

            JPanel editorPanel = new JPanel(new BorderLayout());
            editorPanel.add(new JLabel("入参 (JSON):"), BorderLayout.NORTH);
            editorPanel.add(scrollPane, BorderLayout.CENTER);

            panel.add(editorPanel, BorderLayout.CENTER);

            return panel;
        }

        private void formatJson(Document document, Project project) {
            WriteCommandAction.runWriteCommandAction(project, () -> {
                PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(document);
                if (file != null)
                    CodeStyleManager.getInstance(project).reformat(file);
            });
        }

        public PidUtils.RunningApp getSelectedApp() {
            return (PidUtils.RunningApp) appComboBox.getSelectedItem();
        }

        public String getJsonInput() {
            return jsonEditor.getText();
        }
    }

    // 解析结果逻辑保持不变
    private String parseResult(String raw) {
        if (raw == null)
            return "";
        String[] lines = raw.split("\n");
        StringBuilder resultBuilder = new StringBuilder();
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("vmtool") || line.contains("--action") || line.isEmpty())
                continue;
            if (line.startsWith("@")) {
                int firstBracket = line.indexOf('[');
                int lastBracket = line.lastIndexOf(']');
                if (firstBracket > 0 && lastBracket > firstBracket) {
                    resultBuilder.append(line, firstBracket + 1, lastBracket).append("\n");
                } else {
                    resultBuilder.append(line).append("\n");
                }
            } else {
                resultBuilder.append(line).append("\n");
            }
        }
        return resultBuilder.toString().trim();
    }

    private void showNotification(Project project, String content, NotificationType type) {
        Notifications.Bus.notify(new Notification("Ignite Notification Group", "Ignite", content, type), project);
    }
}
