package com.ignite.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class OgnlConverter {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static String jsonToOgnl(String jsonArgs, PsiParameter[] parameters) {
        try {
            if (jsonArgs == null || jsonArgs.isBlank()) {
                jsonArgs = "{}";
            }
            JsonNode root = mapper.readTree(jsonArgs);
            if (!root.isObject() && !root.isArray()) {
                root = mapper.createObjectNode();
            }

            List<JsonNode> valuesByIndex = null;
            if (root.isObject() && root.size() == parameters.length) {
                final List<JsonNode> list = new ArrayList<>(parameters.length);
                root.fields().forEachRemaining(e -> list.add(e.getValue()));
                valuesByIndex = list;
            }

            List<String> ognlArgs = new ArrayList<>();
            AtomicInteger varCounter = new AtomicInteger(0);

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

                if (valueNode == null && parameters.length == 1) {
                    valueNode = root;
                }

                ognlArgs.add(convertNode(valueNode, type, varCounter));
            }
            return String.join(", ", ognlArgs);
        } catch (Exception e) {
            throw new RuntimeException("参数解析失败: " + e.getMessage(), e);
        }
    }

    private static String convertNode(JsonNode node, PsiType type, AtomicInteger counter) {
        if (node == null || node.isNull()) return "null";

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

        // 2. 处理数组和集合 (Array / List)
        if (node.isArray()) {
            PsiType componentType = null;
            if (type instanceof PsiArrayType) {
                componentType = ((PsiArrayType) type).getComponentType();
            } else if (type instanceof PsiClassType) {
                PsiType[] typeParams = ((PsiClassType) type).getParameters();
                if (typeParams.length > 0) {
                    componentType = typeParams[0];
                }
            }

            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < node.size(); i++) {
                sb.append(convertNode(node.get(i), componentType, counter));
                if (i < node.size() - 1) sb.append(", ");
            }
            sb.append("}");

            if (type != null && type.getCanonicalText().contains("[]")) {
                String arrayType = type.getCanonicalText().replace("[]", "");
                if (arrayType.contains("<")) arrayType = arrayType.substring(0, arrayType.indexOf("<"));
                return "new " + arrayType + "[]" + sb.toString();
            }
            return sb.toString();
        }

        // 3. 处理 Map 和 复杂嵌套 POJO (对象)
        if (node.isObject() && type != null) {
            String className = type.getCanonicalText();

            // 如果是 Map，直接让目标 JVM 反序列化为 HashMap (抛弃原本手动拼接 OGNL #{"k":"v"} 的做法，因为 key 里的引号也会出 Bug)
            if (className.startsWith("java.util.Map") || className.equals("java.util.HashMap")) {
                String rawJsonString = node.toString();
                String base64Json = Base64.getEncoder().encodeToString(rawJsonString.getBytes(StandardCharsets.UTF_8));
                return String.format("(new com.fasterxml.jackson.databind.ObjectMapper()).readValue(new java.lang.String(@java.util.Base64@getDecoder().decode(\"%s\"), \"UTF-8\"), @java.util.HashMap@class)", base64Json);
            }

            // 【核心】复杂嵌套 POJO 处理：Base64 编码原始 JSON -> 传过去 -> 解码还原 -> ObjectMapper 映射
            if (className.contains("<")) {
                className = className.substring(0, className.indexOf("<"));
            }

            String rawJsonString = node.toString();
            // 在 IDEA 端将极其复杂的嵌套 JSON 转成绝对安全的 Base64
            String base64Json = Base64.getEncoder().encodeToString(rawJsonString.getBytes(StandardCharsets.UTF_8));

            // 生成的 OGNL 会类似于： mapper.readValue(new String(Base64.decode("ey..."), "UTF-8"), @YourClass@class)
            return String.format("(new com.fasterxml.jackson.databind.ObjectMapper()).readValue(new java.lang.String(@java.util.Base64@getDecoder().decode(\"%s\"), \"UTF-8\"), @%s@class)", base64Json, className);
        }

        return "null";
    }
}