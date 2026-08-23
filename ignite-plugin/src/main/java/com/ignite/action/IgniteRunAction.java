package com.ignite.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ignite.core.DataGenerator;
import com.ignite.core.IgniteMethodExecutor;
import com.ignite.core.JacksonSupport;
import com.ignite.infra.IgniteLastRunService;
import com.ignite.infra.IgniteNotifier;
import com.ignite.infra.LastRunRecord;
import com.ignite.infra.PidUtils;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.lang.Language;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;

import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.ui.LanguageTextField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.List;
import javax.swing.JButton;
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
            IgniteNotifier.notify(project, "请先将光标放置在 Java 方法名上", NotificationType.WARNING);
            return;
        }

        List<PidUtils.RunningApp> runningApps = PidUtils.getRunningApps(project);
        if (runningApps.isEmpty()) {
            IgniteNotifier.notify(project, "未找到运行中的 Java 服务！", NotificationType.WARNING);
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
        IgniteDialog dialog = new IgniteDialog(project, method, initialJson, runningApps);
        if (!dialog.showAndGet())
            return;

        String rawJson = dialog.getJsonInput();
        final String jsonArgs = (rawJson == null || rawJson.trim().isEmpty()) ? "{}" : rawJson.trim();
        PidUtils.RunningApp selectedApp = dialog.getSelectedApp();

        // 3. 【参数记忆】保存本次运行的参数
        PropertiesComponent.getInstance(project).setValue(cacheKey, jsonArgs);

        // 4. 记录最近一次运行，供 Ignite Rerun Last 一键重跑
        String className = method.getContainingClass() != null
            ? method.getContainingClass().getQualifiedName()
            : "Unknown";
        PsiParameter[] params = method.getParameterList().getParameters();
        String[] paramTypes = new String[params.length];
        for (int i = 0; i < params.length; i++) {
            paramTypes[i] = params[i].getType().getCanonicalText();
        }
        IgniteLastRunService.getInstance(project).save(new LastRunRecord(
            className,
            method.getName(),
            method.getParameterList().getParametersCount(),
            jsonArgs,
            selectedApp.pid,
            selectedApp.displayName,
            paramTypes
        ));

        // 5. 异步执行
        IgniteMethodExecutor.execute(project, method, jsonArgs, selectedApp);
    }

    // 生成唯一的缓存 Key: 包名.类名.方法名(参数类型列表)，区分同名重载方法
    private String getCacheKey(PsiMethod method) {
        String className = method.getContainingClass() != null ? method.getContainingClass().getQualifiedName() : "Unknown";
        StringBuilder key = new StringBuilder(HISTORY_KEY_PREFIX).append(className).append('.').append(method.getName()).append('(');
        PsiParameter[] params = method.getParameterList().getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) key.append(',');
            key.append(params[i].getType().getCanonicalText());
        }
        return key.append(')').toString();
    }

    private static class IgniteDialog extends DialogWrapper {
        private final Project project;
        private final PsiMethod method;
        private final List<PidUtils.RunningApp> apps;
        private ComboBox<PidUtils.RunningApp> appComboBox;
        private LanguageTextField jsonEditor;
        private final String defaultJson;

        protected IgniteDialog(Project project, PsiMethod method, String defaultJson, List<PidUtils.RunningApp> apps) {
            super(project, true); // true = canBeParent
            this.project = project;
            this.method = method;
            this.apps = apps;
            this.defaultJson = defaultJson;
            setTitle("Ignite Run - " + method.getName());
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
            JPanel labelRow = new JPanel(new BorderLayout());
            labelRow.add(new JLabel("入参 (JSON):"), BorderLayout.WEST);
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0));
            JButton randomParamsButton = new JButton("随机生成参数");
            randomParamsButton.addActionListener(ev -> generateRandomParams());
            JButton formatJsonButton = new JButton("格式化 JSON");
            formatJsonButton.addActionListener(ev -> formatJsonInDialog());
            buttonPanel.add(randomParamsButton);
            buttonPanel.add(formatJsonButton);
            labelRow.add(buttonPanel, BorderLayout.EAST);
            editorPanel.add(labelRow, BorderLayout.NORTH);
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

        /** 根据方法签名随机生成入参 JSON（用于「随机生成参数」按钮） */
        private void generateRandomParams() {
            try {
                String randomJson = DataGenerator.generateMethodArgsJson(method);
                jsonEditor.setText(randomJson);
                formatJson(jsonEditor.getDocument(), project);
                showDialogNotification(project, "已生成随机参数", NotificationType.INFORMATION);
            } catch (Exception ex) {
                showDialogNotification(project, "生成随机参数失败: " + ex.getMessage(), NotificationType.ERROR);
            }
        }

        /** 格式化入参 JSON 文本框内容（用于「格式化 JSON」按钮） */
        private void formatJsonInDialog() {
            String text = jsonEditor.getText().trim();
            if (text.isEmpty()) {
                showDialogNotification(project, "请输入 JSON 后再格式化", NotificationType.WARNING);
                return;
            }
            try {
                var mapper = JacksonSupport.createMapper().enable(SerializationFeature.INDENT_OUTPUT);
                JsonNode node = mapper.readTree(text);
                String formatted = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
                jsonEditor.setText(formatted);
                showDialogNotification(project, "已格式化", NotificationType.INFORMATION);
            } catch (Exception ex) {
                showDialogNotification(project, "不是有效的 JSON: " + ex.getMessage(), NotificationType.ERROR);
            }
        }

        private static void showDialogNotification(Project project, String content, NotificationType type) {
            IgniteNotifier.notify(project, content, type);
        }

        public PidUtils.RunningApp getSelectedApp() {
            return (PidUtils.RunningApp) appComboBox.getSelectedItem();
        }

        public String getJsonInput() {
            return jsonEditor.getText();
        }
    }
}
