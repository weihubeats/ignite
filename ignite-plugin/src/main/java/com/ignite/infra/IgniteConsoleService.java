package com.ignite.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import java.text.SimpleDateFormat;
import java.util.Date;

@Service(Service.Level.PROJECT)
public final class IgniteConsoleService {

    public static final String TOOL_WINDOW_ID = "Ignite Console";
    private ConsoleView consoleView;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Project project;

    public IgniteConsoleService(Project project) {
        this.project = project;
    }

    public static IgniteConsoleService getInstance(Project project) {
        return project.getService(IgniteConsoleService.class);
    }

    public void initConsoleView(ConsoleView consoleView) {
        this.consoleView = consoleView;
    }

    /**
     * 【核心升级】打印完整的执行报告
     */
    public void printExecution(String serviceName, String classMethod, String argsJson, String resultJson) {
        activateWindow();
        if (consoleView == null) return;

        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());

        // 1. 打印分割线和头部
        consoleView.print("════════════════════════════════════════════════════════════════════════\n", ConsoleViewContentType.SYSTEM_OUTPUT);
        consoleView.print("[" + time + "] EXECUTION REPORT\n", ConsoleViewContentType.LOG_INFO_OUTPUT);

        // 2. 打印元数据 (Target & Method)
        printKV("Target Service", serviceName);
        printKV("Method Info   ", classMethod);

        // 3. 打印入参 (Parameters)
        consoleView.print("\n", ConsoleViewContentType.NORMAL_OUTPUT); // 空行
        consoleView.print("▶ Parameters:\n", ConsoleViewContentType.SYSTEM_OUTPUT);
        printPrettyJson(argsJson, ConsoleViewContentType.USER_INPUT); // 入参用斜体/灰色表示

        // 4. 打印结果 (Return Value)
        consoleView.print("\n", ConsoleViewContentType.NORMAL_OUTPUT); // 空行
        consoleView.print("▶ Return Value:\n", ConsoleViewContentType.SYSTEM_OUTPUT);
        printPrettyJson(resultJson, ConsoleViewContentType.NORMAL_OUTPUT); // 结果用正常颜色

        consoleView.print("\n", ConsoleViewContentType.NORMAL_OUTPUT);
    }

    public void printError(String error) {
        activateWindow();
        if (consoleView != null) {
            String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
            consoleView.print("[" + time + "] ERROR: " + error + "\n", ConsoleViewContentType.ERROR_OUTPUT);
        }
    }

    // 辅助方法：打印 Key-Value
    private void printKV(String key, String value) {
        consoleView.print(key + ": ", ConsoleViewContentType.SYSTEM_OUTPUT);
        consoleView.print(value + "\n", ConsoleViewContentType.NORMAL_OUTPUT);
    }

    // 辅助方法：美化并打印 JSON
    private void printPrettyJson(String raw, ConsoleViewContentType contentType) {
        if (raw == null || raw.isBlank()) {
            consoleView.print("(Empty)\n", contentType);
            return;
        }
        try {
            Object json = objectMapper.readValue(raw, Object.class);
            String pretty = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
            consoleView.print(pretty + "\n", contentType);
        } catch (Exception e) {
            // 如果不是 JSON (比如简单字符串)，直接打印
            consoleView.print(raw + "\n", contentType);
        }
    }

    private void activateWindow() {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow != null && (!toolWindow.isVisible() || consoleView == null)) {
            toolWindow.show(null);
        }
    }
}