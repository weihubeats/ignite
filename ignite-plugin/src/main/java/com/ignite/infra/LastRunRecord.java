package com.ignite.infra;

import org.jetbrains.annotations.NotNull;

/**
 * 记录最近一次 Ignite 方法调用的上下文，用于一键重跑。
 */
public final class LastRunRecord {

    private final @NotNull String classQualifiedName;
    private final @NotNull String methodName;
    private final int parameterCount;
    private final @NotNull String jsonArgs;
    private final @NotNull String pid;
    private final @NotNull String appDisplayName;

    public LastRunRecord(
        @NotNull String classQualifiedName,
        @NotNull String methodName,
        int parameterCount,
        @NotNull String jsonArgs,
        @NotNull String pid,
        @NotNull String appDisplayName
    ) {
        this.classQualifiedName = classQualifiedName;
        this.methodName = methodName;
        this.parameterCount = parameterCount;
        this.jsonArgs = jsonArgs;
        this.pid = pid;
        this.appDisplayName = appDisplayName;
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

    public @NotNull String getMethodSignature() {
        return classQualifiedName + "." + methodName;
    }
}
