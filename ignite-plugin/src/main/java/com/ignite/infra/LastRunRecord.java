package com.ignite.infra;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class LastRunRecord {

    private final @NotNull String classQualifiedName;
    private final @NotNull String methodName;
    private final int parameterCount;
    private final @NotNull String jsonArgs;
    private final @NotNull String pid;
    private final @NotNull String appDisplayName;
    private final @Nullable String[] parameterTypes;

    public LastRunRecord(
        @NotNull String classQualifiedName,
        @NotNull String methodName,
        int parameterCount,
        @NotNull String jsonArgs,
        @NotNull String pid,
        @NotNull String appDisplayName
    ) {
        this(classQualifiedName, methodName, parameterCount, jsonArgs, pid, appDisplayName, null);
    }

    public LastRunRecord(
        @NotNull String classQualifiedName,
        @NotNull String methodName,
        int parameterCount,
        @NotNull String jsonArgs,
        @NotNull String pid,
        @NotNull String appDisplayName,
        @Nullable String[] parameterTypes
    ) {
        this.classQualifiedName = classQualifiedName;
        this.methodName = methodName;
        this.parameterCount = parameterCount;
        this.jsonArgs = jsonArgs;
        this.pid = pid;
        this.appDisplayName = appDisplayName;
        this.parameterTypes = parameterTypes;
    }

    public @NotNull String getClassQualifiedName() {
        return classQualifiedName;
    }

    public @NotNull String getMethodName() {
        return methodName;
    }

    public int getParameterCount() {
        return parameterCount;
    }

    public @NotNull String getJsonArgs() {
        return jsonArgs;
    }

    public @NotNull String getPid() {
        return pid;
    }

    public @NotNull String getAppDisplayName() {
        return appDisplayName;
    }

    public @Nullable String[] getParameterTypes() {
        return parameterTypes;
    }

    public @NotNull String getMethodSignature() {
        return classQualifiedName + "." + methodName;
    }
}
