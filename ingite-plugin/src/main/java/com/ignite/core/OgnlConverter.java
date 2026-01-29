package com.ignite.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.StringEscapeUtils;

public class OgnlConverter {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static String jsonToOgnl(String jsonArgs, PsiParameter[] parameters) {
        try {
            JsonNode root = mapper.readTree(jsonArgs);
            List<String> ognlArgs = new ArrayList<>();
            for (PsiParameter parameter : parameters) {
                String paramName = parameter.getName();
                PsiType type = parameter.getType();
                JsonNode valueNode = root.get(paramName);
                ognlArgs.add(convertNode(valueNode, type));
            }
            return String.join(", ", ognlArgs);
        } catch (Exception e) {
            throw new RuntimeException("参数解析失败: " + e.getMessage(), e);
        }
    }

    private static String convertNode(JsonNode node, PsiType type) {
        if (node == null || node.isNull()) return "null";

        // [核心修复]：字符串进行 Java 转义 (例如 "小奏" -> "\u5c0f\u594f")
        // 这样 OGNL 执行时就不会有乱码问题
        if (node.isTextual()) {
            return "\"" + StringEscapeUtils.escapeJava(node.asText()) + "\"";
        }

        if (node.isBoolean()) return node.asText();
        if (node.isNumber()) {
            if (type != null && (type.equalsToText("long") || type.equalsToText("java.lang.Long"))) {
                return node.asText() + "L";
            }
            return node.asText();
        }

        // POJO 处理
        if (node.isObject() && type != null) {
            String className = type.getCanonicalText();
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("(#v=new %s(),", className));

            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String setter = "set" + StringUtils.capitalize(field.getKey());
                // 递归调用，内部的字符串也会被转义
                String val = convertNode(field.getValue(), null);
                sb.append(String.format("#v.%s(%s),", setter, val));
            }

            if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
            sb.append(", #v)");
            return sb.toString();
        }
        return "null";
    }
}
