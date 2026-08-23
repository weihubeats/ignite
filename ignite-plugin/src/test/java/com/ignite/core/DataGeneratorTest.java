package com.ignite.core;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.util.HashSet;
import java.util.Set;

public class DataGeneratorTest extends LightJavaCodeInsightFixtureTestCase {

    private PsiClass holder;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addClass("""
            package com.example;
            public enum Color { RED, GREEN, BLUE;
                public static final Color FALLBACK = RED;
            }
            """);
        myFixture.addClass("""
            package com.example;
            public class Address {
                private String city;
                public String getCity() { return city; }
                public void setCity(String city) { this.city = city; }
            }
            """);
        myFixture.addClass("""
            package com.example;
            public class Person {
                private String name;
                private int age;
                private com.example.Address address;
                private java.util.List<com.example.Address> addresses;
                public String getName() { return name; }
                public void setName(String name) { this.name = name; }
                public int getAge() { return age; }
                public void setAge(int age) { this.age = age; }
                public com.example.Address getAddress() { return address; }
                public void setAddress(com.example.Address address) { this.address = address; }
                public java.util.List<com.example.Address> getAddresses() { return addresses; }
                public void setAddresses(java.util.List<com.example.Address> addresses) { this.addresses = addresses; }
            }
            """);
        holder = myFixture.addClass("""
            package com.example;
            public class Holder {
                public void withEnum(com.example.Color color, com.example.Color other) {}
                public void withPojo(com.example.Person person) {}
                public void withRecursiveNode(com.example.Node node) {}
                public void noParams() {}
            }
            """);
        myFixture.addClass("""
            package com.example;
            public class Node {
                private com.example.Node child;
                private java.lang.String value;
                public com.example.Node getChild() { return child; }
                public void setChild(com.example.Node child) { this.child = child; }
                public java.lang.String getValue() { return value; }
                public void setValue(java.lang.String value) { this.value = value; }
            }
            """);
    }

    @Override
    protected com.intellij.testFramework.LightProjectDescriptor getProjectDescriptor() {
        // 默认的 JAVA_LATEST 在 IC 发行包中缺少 mockJDK 目录导致类型解析失败；
        // JAVA_11 走仓库下载的 mockjdk-base-java，类型解析正常
        return JAVA_11;
    }

    private JsonNode generate(String methodName) throws Exception {
        PsiMethod method = holder.findMethodsByName(methodName, false)[0];
        String json = DataGenerator.generateMethodArgsJson(method);
        return JacksonSupport.mapper().readTree(json);
    }

    // ==================== 枚举 ====================

    public void testEnumMockCoversAllConstantsIncludingLast() throws Exception {
        Set<String> picked = new HashSet<>();
        for (int i = 0; i < 80; i++) {
            JsonNode json = generate("withEnum");
            picked.add(json.get("color").asText());
        }
        assertTrue("缺少枚举常量: " + picked,
            picked.contains("RED") && picked.contains("GREEN") && picked.contains("BLUE"));
    }

    public void testEnumMockIgnoresNonConstantFields() throws Exception {
        // 枚举体内声明的 public static 字段（FALLBACK）不应被当作常量选中
        Set<String> allowed = Set.of("RED", "GREEN", "BLUE");
        for (int i = 0; i < 40; i++) {
            JsonNode json = generate("withEnum");
            String v1 = json.get("color").asText();
            String v2 = json.get("other").asText();
            assertTrue(allowed.contains(v1));
            assertTrue(allowed.contains(v2));
        }
    }

    // ==================== 嵌套 POJO / 递归深度 ====================

    public void testNestedPojoGeneratesValidStructure() throws Exception {
        JsonNode json = generate("withPojo");
        assertNotNull(json.get("person"));
        JsonNode person = json.get("person");
        assertNotNull(person.get("name"));
        assertNotNull(person.get("address"));
        assertTrue(person.get("address").get("city").isTextual());
        assertTrue("列表属性应生成数组", person.get("addresses").isArray());
    }

    public void testRecursiveClassIsCappedAndParsesQuickly() throws Exception {
        long start = System.currentTimeMillis();
        JsonNode json = generate("withRecursiveNode");
        assertTrue("生成耗时应小于 5s: " + (System.currentTimeMillis() - start),
            System.currentTimeMillis() - start < 5000);

        // 沿 child 链验证递归深度封顶
        JsonNode current = json.get("node");
        int depth = 0;
        while (current != null && current.has("child") && !current.get("child").isNull()) {
            current = current.get("child");
            depth++;
            assertTrue("递归深度应封顶", depth <= 5);
        }
    }

    public void testNoParamMethodReturnsEmptyObject() {
        PsiMethod method = holder.findMethodsByName("noParams", false)[0];
        assertEquals("{}", DataGenerator.generateMethodArgsJson(method));
    }
}
