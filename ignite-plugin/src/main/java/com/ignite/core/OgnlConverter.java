package com.ignite.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class OgnlConverter {

    private static final ObjectMapper mapper = JacksonSupport.mapper();

    public static String jsonToOgnl(String jsonArgs, PsiParameter[] parameters) {
        try {
            JsonNode root = mapper.readTree(jsonArgs == null || jsonArgs.isBlank() ? "{}" : jsonArgs);

            // 位置映射仅在"没有任何 key 与参数名匹配"时启用，避免同名数量巧合导致参数绑错
            List<JsonNode> valuesByIndex = null;
            if (root.isObject() && parameters.length > 1 && root.size() == parameters.length
                && !anyKeyMatchesParamName(root, parameters)) {
                final List<JsonNode> list = new ArrayList<>(parameters.length);
                root.fields().forEachRemaining(e -> list.add(e.getValue()));
                valuesByIndex = list;
            }

            List<String> ognlArgs = new ArrayList<>();

            for (int i = 0; i < parameters.length; i++) {
                PsiParameter parameter = parameters[i];
                String paramName = parameter.getName();
                PsiType type = parameter.getType();

                JsonNode valueNode = null;
                if (root.isObject()) {
                    valueNode = root.get(paramName);
                    if (valueNode == null && paramName != null && !paramName.startsWith("arg")) {
                        valueNode = root.get("arg" + i);
                    }
                }

                if (valueNode == null && valuesByIndex != null && i < valuesByIndex.size()) {
                    valueNode = valuesByIndex.get(i);
                }

                // 单参数：整个 JSON（含标量/数组）都作为该参数的值，不再丢弃标量根节点
                if (valueNode == null && parameters.length == 1) {
                    valueNode = root;
                }

                ognlArgs.add(convertNode(valueNode, type));
            }
            return String.join(", ", ognlArgs);
        } catch (Exception e) {
            throw new RuntimeException("参数解析失败: " + e.getMessage(), e);
        }
    }

    private static boolean anyKeyMatchesParamName(JsonNode root, PsiParameter[] parameters) {
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            String key = fields.next().getKey();
            for (PsiParameter parameter : parameters) {
                if (key.equals(parameter.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    static String convertNode(JsonNode node, PsiType type) {
        if (node == null || node.isNull()) return "null";

        // 0. 枚举类型：Base64 解码后走 valueOf，保证拿到真正的枚举实例而非 String
        if (node.isTextual() && isEnumType(type)) {
            String enumClass = bareClassName(type.getCanonicalText());
            String base64Str = Base64.getEncoder().encodeToString(node.asText().getBytes(StandardCharsets.UTF_8));
            return String.format(
                "@%s@valueOf(new java.lang.String(@java.util.Base64@getDecoder().decode(\"%s\"), \"UTF-8\"))",
                enumClass, base64Str);
        }

        // 1. 字符串：全面采用 Base64 传输，完美避开所有引号被吞、特殊字符转义失败的问题
        if (node.isTextual()) {
            String base64Str = Base64.getEncoder().encodeToString(node.asText().getBytes(StandardCharsets.UTF_8));
            // OGNL 将在目标 JVM 中直接执行 JDK 原生 Base64 解码
            return String.format("new java.lang.String(@java.util.Base64@getDecoder().decode(\"%s\"), \"UTF-8\")", base64Str);
        }

        if (node.isBoolean()) return node.asText();

        if (node.isNumber()) {
            if (type != null && (type.equalsToText("long") || type.equalsToText("java.lang.Long"))) {
                return node.asText() + "L";
            }
            return node.asText();
        }

        // 2. 处理数组 (Array)
        if (node.isArray()) {
            if (type instanceof PsiArrayType arrayType) {
                PsiType componentType = arrayType.getComponentType();
                String componentCanonical = bareClassName(componentType.getCanonicalText());
                StringBuilder sb = new StringBuilder("new ").append(componentCanonical).append("[]{");
                appendChildren(sb, node, componentType);
                return sb.append("}").toString();
            }

            // 集合泛型（List/Set 参数声明为 JSON 数组）：花括号列表即可，OGNL 自动适配 Collection
            PsiType elementType = firstGenericParam(type);
            StringBuilder sb = new StringBuilder("{");
            appendChildren(sb, node, elementType);
            return sb.append("}").toString();
        }

        // 3. 处理 Map：逐条目按泛型转换 key/value，生成具体 Map 实例，
        //    替代旧的整包 Base64 反序列化（会丢失元素类型导致 ClassCastException）
        if (node.isObject() && type != null && isMapType(type.getCanonicalText())) {
            return convertMap(node, type);
        }

        // 4. 复杂嵌套 POJO：Base64 编码原始 JSON -> 传过去 -> 解码还原 -> ObjectMapper 映射
        if (node.isObject() && type != null) {
            String className = bareClassName(type.getCanonicalText());
            String rawJsonString = node.toString();
            String base64Json = Base64.getEncoder().encodeToString(rawJsonString.getBytes(StandardCharsets.UTF_8));
            return String.format(
                "%s.readValue(new java.lang.String(@java.util.Base64@getDecoder().decode(\"%s\"), \"UTF-8\"), @%s@class)",
                JacksonSupport.OGNL_MAPPER, base64Json, className);
        }

        return "null";
    }

    private static void appendChildren(StringBuilder sb, JsonNode arrayNode, PsiType childType) {
        for (int i = 0; i < arrayNode.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(convertNode(arrayNode.get(i), childType));
        }
    }

    /**
     * 生成 OGNL Map 字面量并包装成与声明类型匹配的具体 Map。
     * key/value 分别按泛型递归转换：POJO 值会被正确反序列化为目标类型。
     */
    private static String convertMap(JsonNode node, PsiType type) {
        PsiType keyType = null;
        PsiType valueType = null;
        if (type instanceof PsiClassType classType && classType.getParameters().length >= 2) {
            keyType = classType.getParameters()[0];
            valueType = classType.getParameters()[1];
        }

        StringBuilder inner = new StringBuilder("#{");
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        boolean first = true;
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!first) inner.append(", ");
            inner.append(convertNode(TextNode.valueOf(entry.getKey()), keyType))
                 .append(": ")
                 .append(convertNode(entry.getValue(), valueType));
            first = false;
        }
        inner.append("}");

        return switch (bareClassName(type.getCanonicalText())) {
            case "java.util.TreeMap", "java.util.SortedMap", "java.util.NavigableMap" ->
                "new java.util.TreeMap(" + inner + ")";
            case "java.util.HashMap" -> "new java.util.HashMap(" + inner + ")";
            case "java.util.concurrent.ConcurrentHashMap" ->
                "new java.util.concurrent.ConcurrentHashMap(" + inner + ")";
            case "java.util.Hashtable" -> "new java.util.Hashtable(" + inner + ")";
            default -> inner.toString(); // LinkedHashMap / Map 接口
        };
    }

    private static boolean isEnumType(PsiType type) {
        if (!(type instanceof PsiClassType classType)) {
            return false;
        }
        PsiClass resolved = classType.resolve();
        return resolved != null && resolved.isEnum();
    }

    private static boolean isMapType(String canonicalText) {
        return canonicalText.startsWith("java.util.Map")
            || canonicalText.startsWith("java.util.HashMap")
            || canonicalText.startsWith("java.util.LinkedHashMap")
            || canonicalText.startsWith("java.util.TreeMap")
            || canonicalText.startsWith("java.util.SortedMap")
            || canonicalText.startsWith("java.util.NavigableMap")
            || canonicalText.startsWith("java.util.concurrent.ConcurrentHashMap")
            || canonicalText.startsWith("java.util.Hashtable");
    }

    private static PsiType firstGenericParam(PsiType type) {
        if (type instanceof PsiClassType classType && classType.getParameters().length > 0) {
            return classType.getParameters()[0];
        }
        return null;
    }

    private static String bareClassName(String canonicalText) {
        String name = canonicalText;
        int genericStart = name.indexOf('<');
        if (genericStart > 0) {
            name = name.substring(0, genericStart);
        }
        return name;
    }
}
