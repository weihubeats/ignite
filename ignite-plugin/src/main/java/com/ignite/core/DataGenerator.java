package com.ignite.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiParameterList;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.PsiTypesUtil;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.datafaker.Faker;

public class DataGenerator {

    private static final Faker faker = new Faker(Locale.CHINA);
    private static final ObjectMapper mapper = new ObjectMapper();

    public static String generateMethodArgsJson(PsiMethod method) {
        ObjectNode jsonObject = mapper.createObjectNode();

        PsiParameterList parameterList = method.getParameterList();

        if (parameterList.getParametersCount() == 0) {
            return "{}";
        }

        for (PsiParameter parameter : parameterList.getParameters()) {
            String paramName = parameter.getName();
            PsiType type = parameter.getType();
            addMockValue(jsonObject, paramName, type, 0);
        }

        return jsonObject.toPrettyString();
    }

    private static void addMockValue(ObjectNode node, String key, PsiType type, int depth) {
        // 防止递归过深
        if (depth > 3) {
            node.putNull(key);
            return;
        }

        String typeName = type.getCanonicalText();

        // 基础类型
        if (typeName.equals("int") || typeName.equals("java.lang.Integer")) {
            node.put(key, faker.number().numberBetween(1, 100));
        } else if (typeName.equals("long") || typeName.equals("java.lang.Long")) {
            node.put(key, faker.number().randomNumber(10, false));
        } else if (typeName.equals("double") || typeName.equals("java.lang.Double")) {
            node.put(key, faker.number().randomDouble(2, 1, 10000));
        } else if (typeName.equals("float") || typeName.equals("java.lang.Float")) {
            node.put(key, (float) faker.number().randomDouble(2, 1, 10000));
        } else if (typeName.equals("boolean") || typeName.equals("java.lang.Boolean")) {
            node.put(key, faker.bool().bool());
        } else if (typeName.equals("byte") || typeName.equals("java.lang.Byte")) {
            node.put(key, faker.number().numberBetween(-128, 127));
        } else if (typeName.equals("short") || typeName.equals("java.lang.Short")) {
            node.put(key, faker.number().numberBetween(-32768, 32767));
        } else if (typeName.equals("char") || typeName.equals("java.lang.Character")) {
            node.put(key, faker.lorem().characters(1));
        }
        // 字符串类型
        else if (typeName.equals("java.lang.String")) {
            node.put(key, faker.name().fullName());
        }
        // 日期时间类型
        else if (typeName.equals("java.util.Date")) {
            Date date = faker.date().birthday();
            node.put(key, date.getTime());
        } else if (typeName.equals("java.time.LocalDate")) {
            node.put(key, LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        } else if (typeName.equals("java.time.LocalDateTime")) {
            node.put(key, LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        } else if (typeName.equals("java.time.LocalTime")) {
            node.put(key, java.time.LocalTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME));
        }
        // BigDecimal
        else if (typeName.equals("java.math.BigDecimal")) {
            node.put(key, faker.number().randomDouble(2, 1, 1000000));
        }
        // UUID
        else if (typeName.equals("java.util.UUID")) {
            node.put(key, UUID.randomUUID().toString());
        }
        // 数组类型
        else if (type instanceof PsiArrayType) {
            ArrayNode arrayNode = node.putArray(key);
            PsiType componentType = ((PsiArrayType) type).getComponentType();
            // 生成 1-3 个元素
            int size = faker.number().numberBetween(1, 4);
            for (int i = 0; i < size; i++) {
                ObjectNode elementNode = mapper.createObjectNode();
                addMockValueToArrayElement(arrayNode, componentType, depth + 1);
            }
        }
        // List 类型
        else if (typeName.startsWith("java.util.List") || typeName.startsWith("java.util.Collection")) {
            ArrayNode arrayNode = node.putArray(key);
            if (type instanceof PsiClassType) {
                PsiType[] parameters = ((PsiClassType) type).getParameters();
                if (parameters.length > 0) {
                    PsiType genericType = parameters[0];
                    int size = faker.number().numberBetween(1, 4);
                    for (int i = 0; i < size; i++) {
                        addMockValueToArrayElement(arrayNode, genericType, depth + 1);
                    }
                }
            }
        }
        // Set 类型
        else if (typeName.startsWith("java.util.Set")) {
            ArrayNode arrayNode = node.putArray(key);
            if (type instanceof PsiClassType) {
                PsiType[] parameters = ((PsiClassType) type).getParameters();
                if (parameters.length > 0) {
                    PsiType genericType = parameters[0];
                    int size = faker.number().numberBetween(1, 4);
                    for (int i = 0; i < size; i++) {
                        addMockValueToArrayElement(arrayNode, genericType, depth + 1);
                    }
                }
            }
        }
        // Map 类型
        else if (typeName.startsWith("java.util.Map")) {
            ObjectNode mapNode = node.putObject(key);
            if (type instanceof PsiClassType) {
                PsiType[] parameters = ((PsiClassType) type).getParameters();
                if (parameters.length >= 2) {
                    // 生成 1-2 个随机的 key-value
                    int size = faker.number().numberBetween(1, 3);
                    for (int i = 0; i < size; i++) {
                        String mapKey = "key" + i;
                        addMockValue(mapNode, mapKey, parameters[1], depth + 1);
                    }
                }
            }
        }
        // 枚举类型
        else if (type instanceof PsiClassType) {
            PsiClass psiClass = ((PsiClassType) type).resolve();
            if (psiClass != null && psiClass.isEnum()) {
                PsiField[] enumConstants = psiClass.getFields();
                if (enumConstants.length > 0) {
                    // 随机选择一个枚举值
                    int index = faker.number().numberBetween(0, enumConstants.length);
                    for (int i = 0; i < enumConstants.length; i++) {
                        if (enumConstants[i] instanceof PsiEnumConstant) {
                            if (i == index) {
                                node.put(key, enumConstants[i].getName());
                                break;
                            }
                        }
                    }
                } else {
                    node.putNull(key);
                }
            }
            // 普通对象类型 - 递归生成
            else if (psiClass != null) {
                ObjectNode objectNode = node.putObject(key);
                generateObjectFields(objectNode, psiClass, depth + 1);
            } else {
                node.putNull(key);
            }
        } else {
            node.putNull(key);
        }
    }

    private static void addMockValueToArrayElement(ArrayNode arrayNode, PsiType type, int depth) {
        String typeName = type.getCanonicalText();

        if (typeName.equals("int") || typeName.equals("java.lang.Integer")) {
            arrayNode.add(faker.number().numberBetween(1, 100));
        } else if (typeName.equals("long") || typeName.equals("java.lang.Long")) {
            arrayNode.add(faker.number().randomNumber(10, false));
        } else if (typeName.equals("double") || typeName.equals("java.lang.Double")) {
            arrayNode.add(faker.number().randomDouble(2, 1, 10000));
        } else if (typeName.equals("boolean") || typeName.equals("java.lang.Boolean")) {
            arrayNode.add(faker.bool().bool());
        } else if (typeName.equals("java.lang.String")) {
            arrayNode.add(faker.lorem().word());
        } else if (type instanceof PsiClassType) {
            PsiClass psiClass = ((PsiClassType) type).resolve();
            if (psiClass != null && !psiClass.isEnum()) {
                ObjectNode elementNode = mapper.createObjectNode();
                generateObjectFields(elementNode, psiClass, depth);
                arrayNode.add(elementNode);
            } else {
                arrayNode.addNull();
            }
        } else {
            arrayNode.addNull();
        }
    }

    private static void generateObjectFields(ObjectNode node, PsiClass psiClass, int depth) {
        if (depth > 3) return;

        // 获取所有字段（包括父类）
        PsiField[] fields = psiClass.getAllFields();
        int fieldCount = 0;
        for (PsiField field : fields) {
            // 跳过静态字段和常量
            if (field.hasModifierProperty(PsiModifier.STATIC) ||
                field.hasModifierProperty(PsiModifier.FINAL)) {
                continue;
            }

            // 只生成前 10 个字段，避免对象过大
            if (fieldCount++ >= 10) break;

            String fieldName = field.getName();
            PsiType fieldType = field.getType();
            addMockValue(node, fieldName, fieldType, depth);
        }
    }
}
