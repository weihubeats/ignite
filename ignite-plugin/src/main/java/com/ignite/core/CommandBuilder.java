package com.ignite.core;

import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;

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

        // 2. 实例方法：Spring getBean 与堆实例合并为一条命令，private 方法走反射
        return buildVmtoolCommand(
            className,
            buildUnifiedInstanceExpress(className, method, ognlArgs),
            arthasLibPath,
            3
        );
    }

    private static String buildUnifiedInstanceExpress(String className, PsiMethod method, String ognlArgs) {
        String resolveBean = String.format(
            "#heap=(instances.length > 0 ? instances[0] : null), "
                + "#ctx=@org.springframework.web.context.ContextLoader@getCurrentWebApplicationContext(), "
                + "#spring=(#ctx != null ? #ctx.getBean(@%s@class) : null), "
                + "#bean=(#spring != null ? #spring : #heap)",
            className
        );
        String invoke = buildInvokeExpress(className, method, ognlArgs);
        return String.format("(%s, #bean == null ? \"[Ignite] 未找到 Bean 实例\" : %s)", resolveBean, invoke);
    }

    private static String buildInvokeExpress(String className, PsiMethod method, String ognlArgs) {
        String methodName = method.getName();
        if (needsReflection(method)) {
            // 在声明类上 getDeclaredMethod + setAccessible，避免 CGLIB 代理与 ReflectionUtils 无法访问 private 方法。
            String paramTypes = buildParamTypesVarArgs(method);
            String getDeclaredMethod = paramTypes.isEmpty()
                ? String.format("@%s@class.getDeclaredMethod(\"%s\")", className, methodName)
                : String.format("@%s@class.getDeclaredMethod(\"%s\", %s)", className, methodName, paramTypes);
            return String.format(
                "(#m=%s, #m.setAccessible(true), #m.invoke(#bean%s))",
                getDeclaredMethod,
                ognlArgs.isBlank() ? "" : ", " + ognlArgs
            );
        }
        return String.format("#bean.%s(%s)", methodName, ognlArgs);
    }

    private static boolean needsReflection(PsiMethod method) {
        return method.hasModifierProperty(PsiModifier.PRIVATE)
            || method.hasModifierProperty(PsiModifier.PROTECTED)
            || method.hasModifierProperty(PsiModifier.PACKAGE_LOCAL);
    }

    private static String buildParamTypesVarArgs(PsiMethod method) {
        PsiParameter[] parameters = method.getParameterList().getParameters();
        if (parameters.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(psiTypeToOgnlClass(parameters[i].getType()));
        }
        return sb.toString();
    }

    private static String psiTypeToOgnlClass(PsiType type) {
        if (type instanceof PsiPrimitiveType) {
            return primitiveTypeToOgnlClass(type.getCanonicalText());
        }
        if (type instanceof PsiArrayType) {
            PsiType componentType = ((PsiArrayType) type).getComponentType();
            if (componentType instanceof PsiPrimitiveType) {
                return String.format("@java.lang.Class@forName(\"%s\")", arrayDescriptor(componentType.getCanonicalText()));
            }
            String componentName = componentType.getCanonicalText();
            if (componentName == null || componentName.isBlank()) {
                return "@java.lang.Object@class";
            }
            return String.format("@java.lang.Class@forName(\"[%s\")", classDescriptor(componentName));
        }

        String canonical = type.getCanonicalText();
        if (canonical == null || canonical.isBlank()) {
            return "@java.lang.Object@class";
        }
        if (canonical.contains("<")) {
            canonical = canonical.substring(0, canonical.indexOf('<'));
        }
        return "@" + canonical + "@class";
    }

    private static String primitiveTypeToOgnlClass(String primitiveName) {
        return switch (primitiveName) {
            case "boolean" -> "@java.lang.Boolean@TYPE";
            case "byte" -> "@java.lang.Byte@TYPE";
            case "char" -> "@java.lang.Character@TYPE";
            case "short" -> "@java.lang.Short@TYPE";
            case "int" -> "@java.lang.Integer@TYPE";
            case "long" -> "@java.lang.Long@TYPE";
            case "float" -> "@java.lang.Float@TYPE";
            case "double" -> "@java.lang.Double@TYPE";
            default -> "@java.lang.Object@class";
        };
    }

    private static String classDescriptor(String className) {
        return "L" + className.replace('.', '/') + ";";
    }

    private static String arrayDescriptor(String primitiveName) {
        return switch (primitiveName) {
            case "boolean" -> "[Z";
            case "byte" -> "[B";
            case "char" -> "[C";
            case "short" -> "[S";
            case "int" -> "[I";
            case "long" -> "[J";
            case "float" -> "[F";
            case "double" -> "[D";
            default -> "[Ljava.lang.Object;";
        };
    }

    private static String buildVmtoolCommand(String className, String express, String arthasLibPath, int limit) {
        String expressEscaped = express.replace("\\", "\\\\").replace("\"", "\\\"");
        String vmtoolCmd = String.format(
            "vmtool --action getInstances --className %s --express \"%s\" -x 3 --limit %d",
            className,
            expressEscaped,
            limit
        );
        if (arthasLibPath != null && !arthasLibPath.isEmpty()) {
            String pathArg = arthasLibPath.contains(" ") ? "\"" + arthasLibPath + "\"" : arthasLibPath;
            vmtoolCmd += " --libPath " + pathArg;
        }
        return vmtoolCmd;
    }
}
