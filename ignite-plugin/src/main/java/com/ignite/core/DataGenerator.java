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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    /**
     * 简单类型的 mock 值生成器注册表，对象属性与数组元素共用，避免两处维护同样的类型判断。
     */
    private static final Map<String, java.util.function.Supplier<Object>> SIMPLE_TYPE_MOCKS = buildSimpleTypeMocks();

    private static Map<String, java.util.function.Supplier<Object>> buildSimpleTypeMocks() {
        Map<String, java.util.function.Supplier<Object>> mocks = new HashMap<>();
        mocks.put("int", () -> faker.number().numberBetween(1, 100));
        mocks.put("java.lang.Integer", mocks.get("int"));
        mocks.put("long", () -> faker.number().numberBetween(1_000_000_000L, 9_999_999_999L));
        mocks.put("java.lang.Long", mocks.get("long"));
        mocks.put("double", () -> faker.number().randomDouble(2, 1, 10000));
        mocks.put("java.lang.Double", mocks.get("double"));
        mocks.put("float", () -> (float) faker.number().randomDouble(2, 1, 10000));
        mocks.put("java.lang.Float", mocks.get("float"));
        mocks.put("boolean", () -> faker.bool().bool());
        mocks.put("java.lang.Boolean", mocks.get("boolean"));
        mocks.put("byte", () -> faker.number().numberBetween(-128, 127));
        mocks.put("java.lang.Byte", mocks.get("byte"));
        mocks.put("short", () -> faker.number().numberBetween(-32768, 32767));
        mocks.put("java.lang.Short", mocks.get("short"));
        mocks.put("char", () -> faker.lorem().characters(1));
        mocks.put("java.lang.Character", mocks.get("char"));
        mocks.put("java.lang.String", () -> faker.name().fullName());
        mocks.put("java.util.Date", () -> java.util.Date.from(
            faker.timeAndDate().birthday().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()).getTime());
        mocks.put("java.time.LocalDate", () -> LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        mocks.put("java.time.LocalDateTime", () -> LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        mocks.put("java.time.LocalTime", () -> java.time.LocalTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME));
        mocks.put("java.time.Instant", Instant::now);
        mocks.put("java.math.BigDecimal", () -> faker.number().randomDouble(2, 1, 1000000));
        mocks.put("java.util.UUID", () -> UUID.randomUUID().toString());
        return Map.copyOf(mocks);
    }

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

        java.util.function.Supplier<Object> simpleMock = SIMPLE_TYPE_MOCKS.get(typeName);
        if (simpleMock != null) {
            node.putPOJO(key, simpleMock.get());
            return;
        }

        if (typeName.startsWith("java.util.Optional")) {
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

        java.util.function.Supplier<Object> simpleMock = SIMPLE_TYPE_MOCKS.get(typeName);
        if (simpleMock != null) {
            arrayNode.addPOJO(simpleMock.get());
            return;
        }

        if (resolvedType instanceof PsiClassType classType) {
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
        // 只统计枚举常量本身：枚举体内声明的 public static 字段不应参与随机索引
        List<String> constants = new ArrayList<>();
        for (PsiField field : psiClass.getFields()) {
            if (field instanceof PsiEnumConstant) {
                constants.add(field.getName());
            }
        }
        if (constants.isEmpty()) {
            node.putNull(key);
            return;
        }
        int index = faker.number().numberBetween(0, constants.size());
        node.put(key, constants.get(index));
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
