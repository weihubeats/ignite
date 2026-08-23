package com.ignite.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

public class CommandBuilderTest extends LightJavaCodeInsightFixtureTestCase {

    private PsiClass userService;
    private PsiClass baseService;
    private PsiClass serviceImpl;
    private PsiClass plainService;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        baseService = myFixture.addClass("""
            package com.example;
            public interface BaseService {
                void handle(String event);
            }
            """);
        userService = myFixture.addClass("""
            package com.example;
            public interface UserService extends com.example.BaseService {
                String queryUser(long id);
            }
            """);
        serviceImpl = myFixture.addClass("""
            package com.example;
            @org.springframework.stereotype.Service
            public class UserServiceImpl implements com.example.UserService, java.io.Serializable {
                @Override
                public void handle(String event) {}

                @Override
                public String queryUser(long id) { return null; }

                private String secret(String token) { return token; }

                public static int stat() { return 1; }
            }
            """);
        plainService = myFixture.addClass("""
            package com.example;
            public class PlainService {
                public String ping(String msg) { return msg; }
            }
            """);
    }

    @Override
    protected com.intellij.testFramework.LightProjectDescriptor getProjectDescriptor() {
        // 默认的 JAVA_LATEST 在 IC 发行包中缺少 mockJDK 目录导致类型解析失败；
        // JAVA_11 走仓库下载的 mockjdk-base-java，类型解析正常
        return JAVA_11;
    }

    private PsiMethod method(PsiClass clazz, String name) {
        PsiMethod[] methods = clazz.findMethodsByName(name, false);
        assertTrue("method not found: " + name, methods.length > 0);
        return methods[0];
    }

    // ==================== 静态方法 ====================

    public void testStaticMethodUsesOgnlCommand() {
        String cmd = CommandBuilder.build(method(serviceImpl, "stat"), "", null);
        assertTrue(cmd.startsWith("ognl '@com.example.UserServiceImpl@stat()'"));
        assertTrue(cmd.contains("-x 3"));
    }

    // ==================== ServiceImpl 方法：接口优先探测 ====================

    public void testImplMethodProbesInterfaceBeforeImpl() {
        String cmd = CommandBuilder.build(method(serviceImpl, "queryUser"), "1L", null);
        assertTrue(cmd.contains("--className org.springframework.context.ApplicationContext"));
        assertTrue(cmd.contains("getBeanNamesForType(@com.example.UserService@class)"));
        assertTrue(cmd.contains("getBeanNamesForType(@com.example.UserServiceImpl@class)"));
        // 接口探测必须出现在 Impl 探测之前：接口同时命中 JDK 代理与 CGLIB
        assertTrue("interface probe must come before impl probe",
            cmd.indexOf("@com.example.UserService@") < cmd.lastIndexOf("@com.example.UserServiceImpl@"));
        // 公有方法直接调用（保留 AOP 语义）
        assertTrue(cmd.contains("#bean.queryUser(1L)"));
    }

    public void testImplProbeUsesGetBeanNamesForTypeInsteadOfBlindGetBean() {
        String cmd = CommandBuilder.build(method(serviceImpl, "queryUser"), "1L", null);
        // 不再直接调用会抛 NoSuchBeanDefinitionException 的 getBean(Class)
        assertFalse(cmd.contains("getBean(@com.example.UserServiceImpl@class)."));
        int probes = countOccurrences(cmd, "getBeanNamesForType");
        int gets = countOccurrences(cmd, ".getBean(@");
        assertEquals("每个 getBean 都必须先经 getBeanNamesForType 探测", probes, gets);
    }

    public void testUnrelatedFirstInterfaceIsIgnored() {
        // UserServiceImpl 第一个接口是 Serializable，但业务方法声明在 UserService：
        // 必须解析出 UserService，绝不能出现 Serializable 探测分支
        String cmd = CommandBuilder.build(method(serviceImpl, "queryUser"), "1L", null);
        assertFalse(cmd.contains("java.io.Serializable"));
    }

    public void testMethodDeclaredOnlyInParentInterfaceStillResolves() {
        // handle 声明在 BaseService，UserService 继承之：checkBases=true 应命中 UserService
        assertEquals("com.example.UserService",
            CommandBuilder.resolveServiceInterface(serviceImpl, method(serviceImpl, "handle")));
        String cmd = CommandBuilder.build(method(serviceImpl, "handle"), "\"evt\"", null);
        assertTrue(cmd.contains("getBeanNamesForType(@com.example.UserService@class)"));
    }

    // ==================== 接口方法 ====================

    public void testInterfaceMethodInvokesThroughProxy() {
        String cmd = CommandBuilder.build(method(userService, "queryUser"), "1L", null);
        assertTrue(cmd.contains("--className org.springframework.context.ApplicationContext"));
        assertTrue(cmd.contains("#bean.queryUser(1L)"));
        assertFalse(cmd.contains("getDeclaredMethod"));
    }

    // ==================== 非公有方法：代理解包 + 反射 ====================

    public void testPrivateMethodUnwrapsProxyBeforeReflection() {
        String cmd = CommandBuilder.build(method(serviceImpl, "secret"), "\"t\"", null);
        // vmtool 命令中的引号会被转义
        assertTrue(cmd.contains("getDeclaredMethod(\\\"secret\\\""));
        assertTrue(cmd.contains("setAccessible(true)"));
        // 从 Advised 代理中解包真实对象，避免 JDK 代理不是声明类实例的问题
        assertTrue(cmd.contains("instanceof org.springframework.aop.framework.Advised"));
        assertTrue(cmd.contains(".getTargetSource().getTarget()"));
        // 反射目标是解包后的 #t 而非代理 #bean
        assertTrue(cmd.contains("#m.invoke(#t, "));
    }

    // ==================== 非 Spring 普通类 ====================

    public void testPlainClassFallsBackToHeapInstanceInvocation() {
        String cmd = CommandBuilder.build(method(plainService, "ping"), "\"hi\"", null);
        assertFalse("非 Spring 类不应再要求 ApplicationContext",
            cmd.contains("org.springframework.context.ApplicationContext"));
        assertTrue(cmd.contains("--className com.example.PlainService"));
        assertTrue(cmd.contains("instances[0].ping("));
        assertTrue(cmd.contains("堆上未找到 com.example.PlainService 的实例"));
    }

    public void testSpringAnnotationDetectionWalksSuperClasses() {
        assertTrue(CommandBuilder.isSpringBeanCandidate(serviceImpl));
        assertTrue(CommandBuilder.isSpringBeanCandidate(userService));
        assertFalse(CommandBuilder.isSpringBeanCandidate(plainService));

        PsiClass child = myFixture.addClass("""
            package com.example;
            public class ChildService extends com.example.AnnotatedParent {
                public void doIt() {}
            }
            """);
        myFixture.addClass("""
            package com.example;
            @org.springframework.stereotype.Component
            public abstract class AnnotatedParent {
                public abstract void doIt();
            }
            """);
        assertTrue("父类带 Spring 注解的子类应走 Bean 解析", CommandBuilder.isSpringBeanCandidate(child));
    }

    // ==================== 工具 ====================

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) != -1) {
            count++;
            idx += token.length();
        }
        return count;
    }
}
