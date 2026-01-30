package com.ignite.core;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;

public class CommandBuilder {

    public static String build(PsiMethod method, String ognlArgs) {
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) {
            throw new IllegalArgumentException("无法获取方法所属的类");
        }
        String className = containingClass.getQualifiedName();
        String methodName = method.getName();

        // 1. 静态方法
        if (method.hasModifierProperty(PsiModifier.STATIC)) {
            return String.format("ognl '@%s@%s(%s)' -x 3", className, methodName, ognlArgs);
        }

        // 2. 实例方法
        // [修复点]：将 instances.isEmpty() 改为 instances.length == 0
        String instanceLogic = String.format(
            "(#target=(instances.length == 0 ? new %s() : instances[0]))",
            className
        );

        String express = String.format("%s.%s(%s)", instanceLogic, methodName, ognlArgs);

        return String.format(
            "vmtool --action getInstances --className %s --express '%s' -x 3",
            className,
            express
        );
    }
}
