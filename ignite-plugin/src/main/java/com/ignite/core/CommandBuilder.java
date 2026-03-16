package com.ignite.core;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;

public class CommandBuilder {

    /**
     * @param arthasLibPath Arthas lib 目录路径，供 vmtool 的 --libPath 使用；可为 null（如静态方法走 ognl 时）
     */
    public static String build(PsiMethod method, String ognlArgs, String arthasLibPath) {
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) {
            throw new IllegalArgumentException("无法获取方法所属的类");
        }
        String className = containingClass.getQualifiedName();
        String methodName = method.getName();

        // 1. 静态方法（走 ognl，不需要 libPath）
        if (method.hasModifierProperty(PsiModifier.STATIC)) {
            return String.format("ognl '@%s@%s(%s)' -x 3", className, methodName, ognlArgs);
        }

        // 2. 实例方法（vmtool，需传入 --libPath 以便目标 JVM 加载 native 库）
        String instanceLogic = String.format(
            "(#target=(instances.length == 0 ? new %s() : instances[0]))",
            className
        );

        String express = String.format("%s.%s(%s)", instanceLogic, methodName, ognlArgs);
        // 用双引号包裹 express 并转义内部双引号，避免单引号或空格导致 Arthas 解析截断参数
        String expressEscaped = express.replace("\\", "\\\\").replace("\"", "\\\"");
        String vmtoolCmd = String.format(
            "vmtool --action getInstances --className %s --express \"%s\" -x 3",
            className,
            expressEscaped
        );
        if (arthasLibPath != null && !arthasLibPath.isEmpty()) {
            String pathArg = arthasLibPath.contains(" ") ? "\"" + arthasLibPath + "\"" : arthasLibPath;
            vmtoolCmd += " --libPath " + pathArg;
        }
        return vmtoolCmd;
    }
}
