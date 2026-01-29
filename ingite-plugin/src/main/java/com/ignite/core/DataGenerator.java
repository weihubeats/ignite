package com.ignite.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiParameterList;
import com.intellij.psi.PsiType;
import java.util.Locale;
import net.datafaker.Faker;

public class DataGenerator {

    private static final Faker faker = new Faker(Locale.CHINA);
    private static final ObjectMapper mapper = new ObjectMapper();

    public static String generateMethodArgsJson(PsiMethod method) {
        // 修改为 ObjectNode，生成 {} 格式
        ObjectNode jsonObject = mapper.createObjectNode();

        PsiParameterList parameterList = method.getParameterList();

        // 无参直接返回 "{}"
        if (parameterList.getParametersCount() == 0) {
            return "{}";
        }

        for (PsiParameter parameter : parameterList.getParameters()) {
            String paramName = parameter.getName(); // 获取参数名 "a"
            PsiType type = parameter.getType();

            // 生成值并 put 进对象
            addMockValue(jsonObject, paramName, type);
        }

        return jsonObject.toPrettyString();
    }

    private static void addMockValue(ObjectNode node, String key, PsiType type) {
        String typeName = type.getCanonicalText();

        if (typeName.equals("int") || typeName.equals("java.lang.Integer")) {
            node.put(key, faker.number().numberBetween(1, 100));
        } else if (typeName.equals("long") || typeName.equals("java.lang.Long")) {
            node.put(key, faker.number().randomNumber());
        } else if (typeName.equals("boolean") || typeName.equals("java.lang.Boolean")) {
            node.put(key, faker.bool().bool());
        } else if (typeName.equals("java.lang.String")) {
            node.put(key, faker.name().fullName()); // 生成 "谭越彬"
        } else {
            // 对于复杂对象，暂置为 null，后续可扩展递归生成
            node.putNull(key);
        }
    }
}
