package com.ignite.core;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

public class CommandBuilder {

    private static final String SPRING_APPLICATION_CONTEXT = "org.springframework.context.ApplicationContext";
    private static final String SPRING_ADVISED = "org.springframework.aop.framework.Advised";
    private static final String CONTEXT_LOADER = "org.springframework.web.context.ContextLoader";

    /** 堆上 ApplicationContext 实例探测数量上限（父子容器场景） */
    private static final int MAX_CONTEXT_PROBES = 3;

    /**
     * Spring Bean 常见注解（含组合注解的根注解）。
     * 未命中时按普通类处理：直接在堆上找实例调用，兼容非 Spring 应用。
     */
    private static final Set<String> SPRING_BEAN_ANNOTATIONS = Set.of(
        "org.springframework.stereotype.Component",
        "org.springframework.stereotype.Service",
        "org.springframework.stereotype.Repository",
        "org.springframework.stereotype.Controller",
        "org.springframework.web.bind.annotation.RestController",
        "org.springframework.context.annotation.Configuration",
        "org.springframework.boot.context.properties.ConfigurationProperties"
    );

    /**
     * @param arthasLibPath Arthas lib 目录路径，供 vmtool 的 --libPath 使用
     */
    public static String build(PsiMethod method, String ognlArgs, String arthasLibPath) {
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) {
            throw new IllegalArgumentException("无法获取方法所属的类");
        }
        String className = containingClass.getQualifiedName();

        // 1. 静态方法（走 ognl，不需要实例）
        if (method.hasModifierProperty(PsiModifier.STATIC)) {
            return String.format("ognl '@%s@%s(%s)' -x 3", className, method.getName(), ognlArgs);
        }

        // 2. Spring Bean：从堆上 ApplicationContext 取 Bean（接口优先，兼容 JDK 动态代理 / CGLIB）
        if (isSpringBeanCandidate(containingClass)) {
            return buildVmtoolCommand(
                SPRING_APPLICATION_CONTEXT,
                buildSpringInstanceExpress(containingClass, method, ognlArgs),
                arthasLibPath,
                MAX_CONTEXT_PROBES
            );
        }

        // 3. 普通类（非 Spring）：直接搜索堆实例调用，不再强制要求存在 Spring 上下文
        return buildVmtoolCommand(
            className,
            buildPlainInstanceExpress(containingClass, method, ognlArgs),
            arthasLibPath,
            10
        );
    }

    // ==================== Spring Bean 调用 ====================

    private static String buildSpringInstanceExpress(PsiClass containingClass, PsiMethod method, String ognlArgs) {
        String className = containingClass.getQualifiedName();
        String interfaceName = resolveServiceInterface(containingClass, method);

        // 接口类型优先探测：JDK 代理与 CGLIB 代理都实现了业务接口，getBeanNamesForType 均能命中；
        // 且 getBeanNamesForType 只探测不抛异常，避免 getBean(Impl) 在 JDK 代理场景抛 NoSuchBeanDefinitionException
        LinkedHashSet<String> lookupTypes = new LinkedHashSet<>();
        if (interfaceName != null && !interfaceName.equals(className)) {
            lookupTypes.add(interfaceName);
        }
        lookupTypes.add(className);

        StringBuilder sb = new StringBuilder();
        sb.append("#cl=@").append(CONTEXT_LOADER).append("@getCurrentWebApplicationContext(), ");
        sb.append(buildBeanResolveChain(lookupTypes)).append(", ");

        String invoke = buildInvokeExpress(className, method, ognlArgs);
        sb.append("(#bean == null ? \"[Ignite] 未找到 Bean 实例\" : ").append(invoke).append(")");
        return "(" + sb + ")";
    }

    /**
     * 生成 Bean 解析链：ctx[0..N-1] × 候选类型逐个"先探测后获取"，全部失败回退 ContextLoader，最终为 null。
     */
    private static String buildBeanResolveChain(LinkedHashSet<String> lookupTypes) {
        Deque<String[][]> branches = new ArrayDeque<>();
        // 从低优先级到高优先级依次压入：loader 回退 → ctx2 → ctx1 → ctx0
        branches.push(loaderBranches(lookupTypes));
        for (int i = MAX_CONTEXT_PROBES - 1; i >= 0; i--) {
            branches.push(contextBranches(i, lookupTypes));
        }

        StringBuilder expr = new StringBuilder("null");
        while (!branches.isEmpty()) {
            String[][] contextBranches = branches.pop();
            for (String[] branch : contextBranches) {
                expr.insert(0, "(").append(")");
                expr.insert(0, branch[0] + " ? " + branch[1] + " : ");
            }
        }
        return "#bean=" + expr;
    }

    /** 单个上下文实例 × 全部候选类型的分支（保持 types 的插入顺序：接口在前） */
    private static String[][] contextBranches(int idx, LinkedHashSet<String> lookupTypes) {
        return lookupTypes.stream()
            .map(type -> new String[]{
                "(instances.length > " + idx + " && instances[" + idx + "].getBeanNamesForType(@" + type + "@class).length > 0)",
                "instances[" + idx + "].getBean(@" + type + "@class)"
            })
            .toArray(String[][]::new);
    }

    private static String[][] loaderBranches(LinkedHashSet<String> lookupTypes) {
        return lookupTypes.stream()
            .map(type -> new String[]{
                "(#cl != null && #cl.getBeanNamesForType(@" + type + "@class).length > 0)",
                "#cl.getBean(@" + type + "@class)"
            })
            .toArray(String[][]::new);
    }

    /**
     * 解析 ServiceImpl 对应的业务接口：
     * - checkBases=true 同时检查父接口（方法声明在 BaseService 时也能命中）
     * - 移除旧的 interfaces[0] 盲兜底：无关首接口（如 Serializable）会导致 getBean 类型错误
     */
    static String resolveServiceInterface(PsiClass containingClass, PsiMethod method) {
        if (containingClass.isInterface()) {
            return containingClass.getQualifiedName();
        }
        for (PsiClass iface : containingClass.getInterfaces()) {
            if (iface.findMethodBySignature(method, true) != null) {
                return iface.getQualifiedName();
            }
        }
        return null;
    }

    /**
     * 判断类是否应走 Spring Bean 解析路径。
     * 类自身或父类带 Spring 注解 → 是；纯接口默认按 Bean 类型处理；其余按普通类处理。
     */
    static boolean isSpringBeanCandidate(PsiClass clazz) {
        if (hasSpringAnnotation(clazz)) {
            return true;
        }
        PsiClass sup = clazz.getSuperClass();
        while (sup != null && !sup.getQualifiedName().equals("java.lang.Object")) {
            if (hasSpringAnnotation(sup)) {
                return true;
            }
            sup = sup.getSuperClass();
        }
        // 接口本身通常不带注解（如 UserService），按 Spring Bean 类型解析可覆盖懒加载场景
        return clazz.isInterface();
    }

    private static boolean hasSpringAnnotation(PsiClass clazz) {
        for (PsiAnnotation annotation : clazz.getAnnotations()) {
            String fqn = annotation.getQualifiedName();
            if (fqn != null && SPRING_BEAN_ANNOTATIONS.contains(fqn)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 方法调用表达式 ====================

    private static String buildInvokeExpress(String className, PsiMethod method, String ognlArgs) {
        String methodName = method.getName();
        if (needsReflection(method)) {
            // 非公有方法：先从代理解包真实对象再反射调用。
            // JDK 代理不是声明类的实例，直接对代理 invoke 会抛 IllegalArgumentException；
            // CGLIB 代理是子类实例但私有方法未被覆写，解包后调用同样安全。
            String paramTypes = buildParamTypesVarArgs(method);
            String getDeclaredMethod = paramTypes.isEmpty()
                ? String.format("@%s@class.getDeclaredMethod(\"%s\")", className, methodName)
                : String.format("@%s@class.getDeclaredMethod(\"%s\", %s)", className, methodName, paramTypes);
            String targetExpr = String.format(
                "(#bean instanceof %1$s ? ((%1$s)#bean).getTargetSource().getTarget() : #bean)",
                SPRING_ADVISED
            );
            return String.format(
                "(#t=%s, #m=%s, #m.setAccessible(true), #m.invoke(#t%s))",
                targetExpr,
                getDeclaredMethod,
                ognlArgs.isBlank() ? "" : ", " + ognlArgs
            );
        }
        return String.format("#bean.%s(%s)", methodName, ognlArgs);
    }

    // ==================== 非 Spring 普通类调用 ====================

    private static String buildPlainInstanceExpress(PsiClass containingClass, PsiMethod method, String ognlArgs) {
        String className = containingClass.getQualifiedName();
        String invoke;
        if (needsReflection(method)) {
            String paramTypes = buildParamTypesVarArgs(method);
            String getDeclaredMethod = paramTypes.isEmpty()
                ? String.format("@%s@class.getDeclaredMethod(\"%s\")", className, method.getName())
                : String.format("@%s@class.getDeclaredMethod(\"%s\", %s)", className, method.getName(), paramTypes);
            invoke = String.format(
                "(#m=%s, #m.setAccessible(true), #m.invoke(instances[0]%s))",
                getDeclaredMethod,
                ognlArgs.isBlank() ? "" : ", " + ognlArgs
            );
        } else {
            invoke = String.format("instances[0].%s(%s)", method.getName(), ognlArgs);
        }
        return String.format(
            "(instances.length > 0 ? %s : \"[Ignite] 堆上未找到 %s 的实例\")",
            invoke, className
        );
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
