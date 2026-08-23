package com.ignite.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OgnlConverterTest extends LightJavaCodeInsightFixtureTestCase {

    private static final Pattern BASE64_IN_DECODE = Pattern.compile(
        "@java\\.util\\.Base64@getDecoder\\(\\)\\.decode\\(\"([A-Za-z0-9+/=]+)\"\\)");

    private PsiClass paramsClass;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addClass("""
            package com.example;
            public enum Color { RED, GREEN, BLUE }
            """);
        myFixture.addClass("""
            package com.example;
            public class Item {
                private String name;
                public String getName() { return name; }
                public void setName(String name) { this.name = name; }
            }
            """);
        paramsClass = myFixture.addClass("""
            package com.example;
            public class Params {
                public void single(java.lang.String s) {}
                public void pojo(com.example.Item item) {}
                public void named(java.lang.String a, java.lang.String b) {}
                public void longParam(long id) {}
                public void enumParam(com.example.Color color) {}
                public void mapParam(java.util.Map<java.lang.String, com.example.Item> items) {}
                public void treeMapParam(java.util.TreeMap<java.lang.String, java.lang.String> sorted) {}
                public void grid(int[][] matrix) {}
                public void strList(java.util.List<java.lang.String> names) {}
                public void mixed(int num, java.lang.String name, boolean flag) {}
                public void intArray(int[] values) {}
                public void boolParam(boolean flag) {}
            }
            """);
    }

    @Override
    protected com.intellij.testFramework.LightProjectDescriptor getProjectDescriptor() {
        // 默认的 JAVA_LATEST 在 IC 发行包中缺少 mockJDK 目录导致类型解析失败；
        // JAVA_11 走仓库下载的 mockjdk-base-java，类型解析正常
        return JAVA_11;
    }

    private String convert(String methodName, String json) {
        PsiMethod method = paramsClass.findMethodsByName(methodName, false)[0];
        return OgnlConverter.jsonToOgnl(json, method.getParameterList().getParameters());
    }

    // ==================== 标量与单参数 ====================

    public void testScalarRootIsKeptForSingleStringParam() {
        String ognl = convert("single", "\"hello\"");
        assertEquals(decode(ognl), "hello");
    }

    public void testNumberAndBooleanScalarsPassThrough() {
        assertEquals("42", convert("longParam", "42").replace("L", ""));
        assertTrue(convert("longParam", "42").endsWith("L"));
        assertEquals("true", convert("boolParam", "true"));
    }

    public void testObjectRootMapsWholeObjectForSinglePojoParam() {
        String ognl = convert("pojo", "{\"name\":\"n1\"}");
        assertTrue(ognl.contains("@com.example.Item@class"));
        assertTrue(ognl.contains(JacksonSupport.OGNL_MAPPER.substring(0, 40)));
        assertEquals("{\"name\":\"n1\"}", decodeRawJson(ognl));
    }

    // ==================== 参数绑定启发式 ====================

    public void testNamedBindingWinsWhenKeysMatchParams() {
        String ognl = convert("named", "{\"b\":\"B2\",\"a\":\"A1\"}");
        // 按 key 绑定且表达式顺序跟随参数声明顺序：第一个参数是 A1（而非 JSON 中先出现的 B2）
        assertEquals(java.util.Arrays.asList("A1", "B2"), decodedInOrder(ognl));
    }

    public void testPositionalFallbackOnlyWhenNoKeyMatches() {
        String ognl = convert("named", "{\"x\":\"X1\",\"y\":\"Y2\"}");
        assertEquals("key 与参数名不相交时按位置映射并保持 JSON 顺序",
            java.util.Arrays.asList("X1", "Y2"), decodedInOrder(ognl));
    }

    // ==================== 枚举 ====================

    public void testEnumParamUsesValueOfNotString() {
        String ognl = convert("enumParam", "{\"color\":\"GREEN\"}");
        assertTrue("枚举必须生成 valueOf 调用", ognl.contains("@com.example.Color@valueOf"));
        assertEquals("GREEN", decode(ognl));
    }

    // ==================== Map 类型化转换 ====================

    public void testMapEntriesAreConvertedWithTypeAwareness() {
        String ognl = convert("mapParam", "{\"items\":{\"k1\":{\"name\":\"n1\"}}}");
        assertTrue("应生成 OGNL Map 字面量", ognl.contains("#{"));
        assertTrue("key 应为 Base64 解码字符串", ognl.contains("@java.util.Base64@getDecoder().decode(\"azE=\")")
            || decode(ognl).contains("k1"));
        // 值按泛型反序列化为 Item，而不是整包丢给 HashMap
        assertTrue(ognl.contains("@com.example.Item@class"));
    }

    public void testTreeMapParamIsWrappedAsSortedMap() {
        String ognl = convert("treeMapParam", "{\"sorted\":{\"a\":\"1\"}}");
        assertTrue(ognl.startsWith("new java.util.TreeMap(#{"));
        assertTrue(ognl.endsWith("})"));
    }

    // ==================== 数组 ====================

    public void testMultiDimIntArrayGeneratesValidNestedInitializers() {
        String ognl = convert("grid", "[[1,2],[3]]");
        assertEquals("new int[][]{new int[]{1, 2}, new int[]{3}}", ognl);
    }

    public void testPrimitiveArrayInitializer() {
        String ognl = convert("intArray", "[7,8]");
        assertEquals("new int[]{7, 8}", ognl);
    }

    public void testStringListBraceListWithBase64Elements() {
        String ognl = convert("strList", "{\"names\":[\"alice\",\"bob\"]}");
        assertTrue(ognl.startsWith("{"));
        assertTrue(ognl.endsWith("}"));
        assertEquals(2, countOccurrences(ognl, "@java.util.Base64@getDecoder()"));
        assertTrue(ognl.contains("YWxpY2U=")); // base64("alice")
    }

    // ==================== 多参数混合 ====================

    public void testMixedTypeParams() {
        String ognl = convert("mixed", "{\"num\":3,\"name\":\"tom\",\"flag\":true}");
        assertTrue("第一个参数应为 int 字面量", ognl.startsWith("3, "));
        assertTrue("最后一个参数应为 boolean 字面量", ognl.endsWith(", true"));
        assertEquals(java.util.Collections.singletonList("tom"), decodedInOrder(ognl));
    }

    public void testInvalidJsonThrowsMeaningfulError() {
        try {
            convert("single", "{invalid");
            fail("should throw");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("参数解析失败"));
        }
    }

    // ==================== 工具 ====================

    private static java.util.List<String> decodedInOrder(String ognlExpr) {
        Matcher m = BASE64_IN_DECODE.matcher(ognlExpr);
        java.util.List<String> values = new java.util.ArrayList<>();
        while (m.find()) {
            values.add(new String(Base64.getDecoder().decode(m.group(1)), StandardCharsets.UTF_8));
        }
        return values;
    }

    private static String decode(String ognlExpr) {
        Matcher m = BASE64_IN_DECODE.matcher(ognlExpr);
        assertTrue("no base64 payload found: " + ognlExpr, m.find());
        return new String(Base64.getDecoder().decode(m.group(1)), StandardCharsets.UTF_8);
    }

    private static String decodeRawJson(String ognlExpr) {
        Matcher m = Pattern.compile("decode\\(\"([A-Za-z0-9+/=]+)\"\\)").matcher(ognlExpr);
        StringBuilder all = new StringBuilder();
        while (m.find()) {
            all.append(new String(Base64.getDecoder().decode(m.group(1)), StandardCharsets.UTF_8));
        }
        return all.toString();
    }

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
