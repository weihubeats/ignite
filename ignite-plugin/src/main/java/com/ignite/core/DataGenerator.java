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
import com.intellij.psi.PsiTypeParameter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.datafaker.Faker;

public class DataGenerator {

    private static final Faker faker = new Faker(Locale.CHINA);
    private static final ObjectMapper mapper = JacksonSupport.mapper();
    private static final Set<String> OBJECT_METHODS = Set.of(
        "hashCode", "toString", "getClass", "notify", "notifyAll", "wait"
    );

    public static String generateMethodArgsJson(PsiMethod method) {
        ObjectNode jsonObject = mapper.createObjectNode();

        PsiParameterList parameterList = method.getParameterList();

        if (parameterList.getParametersCount() == 0) {
            return "{}";
        }

        for (PsiParameter parameter : parameterList.getParameters()) {
            String paramName = parameter.getName();
            PsiType type = parameter.getType();
            addMockValue(jsonObject, paramName, type, 0, Map.of());
        }

        return jsonObject.toPrettyString();
    }

    private static void addMockValue(
        ObjectNode node,
        String key,
        PsiType type,
        int depth,
        Map<String, PsiType> typeMap
    ) {
        if (depth > 3) {
            node.putNull(key);
            return;
        }

        PsiType resolvedType = resolveType(type, typeMap);
        String typeName = resolvedType.getCanonicalText();

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
        } else if (typeName.equals("java.lang.String")) {
            node.put(key, faker.name().fullName());
        } else if (typeName.equals("java.util.Date")) {
            Date date = faker.date().birthday();
            node.put(key, date.getTime());
        } else if (typeName.equals("java.time.LocalDate")) {
            node.put(key, LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        } else if (typeName.equals("java.time.LocalDateTime")) {
            node.put(key, LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        } else if (typeName.equals("java.time.LocalTime")) {
            node.put(key, java.time.LocalTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME));
        } else if (typeName.equals("java.time.Instant")) {
            node.put(key, Instant.now().toString());
        } else if (typeName.equals("java.math.BigDecimal")) {
            node.put(key, faker.number().randomDouble(2, 1, 1000000));
        } else if (typeName.equals("java.util.UUID")) {
            node.put(key, UUID.randomUUID().toString());
        } else if (typeName.startsWith("java.util.Optional")) {
            if (resolvedType instanceof PsiClassType optionalType) {
                PsiType[] parameters = optionalType.getParameters();
                if (parameters.length > 0) {
                    addMockValue(node, key, parameters[0], depth + 1, typeMap);
                } else {
                    node.putNull(key);
                }
            } else {
                node.putNull(key);
            }
        } else if (resolvedType instanceof PsiArrayType arrayType) {
            ArrayNode arrayNode = node.putArray(key);
            int size = faker.number().numberBetween(1, 4);
            for (int i = 0; i < size; i++) {
                addMockValueToArrayElement(arrayNode, arrayType.getComponentType(), depth + 1, typeMap);
            }
        } else if (typeName.startsWith("java.util.List") || typeName.startsWith("java.util.Collection")) {
            ArrayNode arrayNode = node.putArray(key);
            if (resolvedType instanceof PsiClassType classType) {
                PsiType[] parameters = classType.getParameters();
                if (parameters.length > 0) {
                    int size = faker.number().numberBetween(1, 4);
                    for (int i = 0; i < size; i++) {
                        addMockValueToArrayElement(arrayNode, parameters[0], depth + 1, typeMap);
                    }
                }
            }
        } else if (typeName.startsWith("java.util.Set")) {
            ArrayNode arrayNode = node.putArray(key);
            if (resolvedType instanceof PsiClassType classType) {
                PsiType[] parameters = classType.getParameters();
                if (parameters.length > 0) {
                    int size = faker.number().numberBetween(1, 4);
                    for (int i = 0; i < size; i++) {
                        addMockValueToArrayElement(arrayNode, parameters[0], depth + 1, typeMap);
                    }
                }
            }
        } else if (typeName.startsWith("java.util.Map")) {
            ObjectNode mapNode = node.putObject(key);
            if (resolvedType instanceof PsiClassType classType) {
                PsiType[] parameters = classType.getParameters();
                if (parameters.length >= 2) {
                    int size = faker.number().numberBetween(1, 3);
                    for (int i = 0; i < size; i++) {
                        addMockValue(mapNode, "key" + i, parameters[1], depth + 1, typeMap);
                    }
                }
            }
        } else if (resolvedType instanceof PsiClassType classType) {
            PsiClass psiClass = classType.resolve();
            if (psiClass != null && psiClass.isEnum()) {
                addEnumValue(node, key, psiClass);
            } else if (psiClass != null && psiClass.isInterface()) {
                node.putNull(key);
            } else if (psiClass != null) {
                ObjectNode objectNode = node.putObject(key);
                Map<String, PsiType> mergedTypeMap = mergeTypeMap(psiClass, classType, typeMap);
                generateObjectProperties(objectNode, psiClass, mergedTypeMap, depth + 1);
            } else {
                node.putNull(key);
            }
        } else {
            node.putNull(key);
        }
    }

    private static void addMockValueToArrayElement(
        ArrayNode arrayNode,
        PsiType type,
        int depth,
        Map<String, PsiType> typeMap
    ) {
        PsiType resolvedType = resolveType(type, typeMap);
        String typeName = resolvedType.getCanonicalText();

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
        } else if (resolvedType instanceof PsiClassType classType) {
            PsiClass psiClass = classType.resolve();
            if (psiClass != null && psiClass.isEnum()) {
                ObjectNode enumHolder = mapper.createObjectNode();
                addEnumValue(enumHolder, "value", psiClass);
                arrayNode.add(enumHolder.get("value"));
            } else if (psiClass != null && !psiClass.isInterface()) {
                ObjectNode elementNode = mapper.createObjectNode();
                Map<String, PsiType> mergedTypeMap = mergeTypeMap(psiClass, classType, typeMap);
                generateObjectProperties(elementNode, psiClass, mergedTypeMap, depth);
                arrayNode.add(elementNode);
            } else {
                arrayNode.addNull();
            }
        } else {
            arrayNode.addNull();
        }
    }

    /**
     * 按优先级为对象生成属性：公开构造器参数 > 公开 getter > 非 static 实例字段。
     * 解决 immutable 类（如 ConsumerRecord 全 final 字段）和仅有 accessor 方法的类。
     */
    private static void generateObjectProperties(
        ObjectNode node,
        PsiClass psiClass,
        Map<String, PsiType> typeMap,
        int depth
    ) {
        if (depth > 3) {
            return;
        }

        if (generateFromConstructor(node, psiClass, typeMap, depth)) {
            return;
        }
        if (generateFromGetters(node, psiClass, typeMap, depth)) {
            return;
        }
        generateFromFields(node, psiClass, typeMap, depth);
    }

    private static boolean generateFromConstructor(
        ObjectNode node,
        PsiClass psiClass,
        Map<String, PsiType> typeMap,
        int depth
    ) {
        PsiMethod bestConstructor = findShortestPublicConstructor(psiClass);
        if (bestConstructor == null) {
            return false;
        }

        int count = 0;
        for (PsiParameter parameter : bestConstructor.getParameterList().getParameters()) {
            if (count++ >= 10) {
                break;
            }
            String paramName = parameter.getName();
            PsiType paramType = resolveType(parameter.getType(), typeMap);
            addMockValue(node, paramName, paramType, depth, typeMap);
        }
        return count > 0;
    }

    private static boolean generateFromGetters(
        ObjectNode node,
        PsiClass psiClass,
        Map<String, PsiType> typeMap,
        int depth
    ) {
        int count = 0;
        for (PsiMethod method : psiClass.getMethods()) {
            PsiClass declaringClass = method.getContainingClass();
            if (declaringClass != null && !declaringClass.equals(psiClass)) {
                continue;
            }
            if (!isReadableAccessor(method)) {
                continue;
            }

            String propertyName = accessorPropertyName(method.getName());
            if (propertyName == null || node.has(propertyName)) {
                continue;
            }

            if (count++ >= 10) {
                break;
            }

            PsiType returnType = resolveType(method.getReturnType(), typeMap);
            if (returnType == null) {
                continue;
            }
            addMockValue(node, propertyName, returnType, depth, typeMap);
        }
        return count > 0;
    }

    private static void generateFromFields(
        ObjectNode node,
        PsiClass psiClass,
        Map<String, PsiType> typeMap,
        int depth
    ) {
        int count = 0;
        for (PsiField field : psiClass.getAllFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) {
                continue;
            }
            if (field.hasModifierProperty(PsiModifier.TRANSIENT)) {
                continue;
            }

            if (count++ >= 10) {
                break;
            }

            String fieldName = field.getName();
            PsiType fieldType = resolveType(field.getType(), typeMap);
            addMockValue(node, fieldName, fieldType, depth, typeMap);
        }
    }

    private static PsiMethod findShortestPublicConstructor(PsiClass psiClass) {
        PsiMethod best = null;
        int bestParamCount = Integer.MAX_VALUE;
        for (PsiMethod constructor : psiClass.getConstructors()) {
            if (!constructor.hasModifierProperty(PsiModifier.PUBLIC)) {
                continue;
            }
            int paramCount = constructor.getParameterList().getParametersCount();
            if (paramCount < bestParamCount) {
                bestParamCount = paramCount;
                best = constructor;
            }
        }
        return best;
    }

    private static boolean isReadableAccessor(PsiMethod method) {
        if (!method.hasModifierProperty(PsiModifier.PUBLIC)) {
            return false;
        }
        if (method.hasModifierProperty(PsiModifier.STATIC)) {
            return false;
        }
        if (method.isConstructor()) {
            return false;
        }
        if (method.getParameterList().getParametersCount() != 0) {
            return false;
        }
        PsiType returnType = method.getReturnType();
        if (returnType == null || PsiType.VOID.equals(returnType)) {
            return false;
        }
        return !OBJECT_METHODS.contains(method.getName());
    }

    private static String accessorPropertyName(String methodName) {
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
        }
        if (methodName.startsWith("is") && methodName.length() > 2) {
            return Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
        }
        if (methodName.startsWith("set") || methodName.startsWith("with")) {
            return null;
        }
        return methodName;
    }

    private static void addEnumValue(ObjectNode node, String key, PsiClass psiClass) {
        PsiField[] enumConstants = psiClass.getFields();
        if (enumConstants.length == 0) {
            node.putNull(key);
            return;
        }

        int index = faker.number().numberBetween(0, Math.max(0, enumConstants.length - 1));
        for (int i = 0; i < enumConstants.length; i++) {
            if (enumConstants[i] instanceof PsiEnumConstant && i == index) {
                node.put(key, enumConstants[i].getName());
                return;
            }
        }
        node.putNull(key);
    }

    private static Map<String, PsiType> mergeTypeMap(
        PsiClass psiClass,
        PsiClassType classType,
        Map<String, PsiType> parentTypeMap
    ) {
        Map<String, PsiType> merged = new HashMap<>(parentTypeMap);
        PsiTypeParameter[] typeParameters = psiClass.getTypeParameters();
        PsiType[] actualTypes = classType.getParameters();
        for (int i = 0; i < typeParameters.length && i < actualTypes.length; i++) {
            merged.put(typeParameters[i].getName(), actualTypes[i]);
        }
        return merged;
    }

    private static PsiType resolveType(PsiType type, Map<String, PsiType> typeMap) {
        if (type instanceof PsiClassType classType && classType.resolve() == null) {
            String typeName = type.getCanonicalText();
            PsiType mapped = typeMap.get(typeName);
            if (mapped != null) {
                return mapped;
            }
        }
        return type;
    }
}
