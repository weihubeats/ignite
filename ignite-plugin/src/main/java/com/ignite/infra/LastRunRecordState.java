package com.ignite.infra;

import org.jetbrains.annotations.NotNull;

public final class LastRunRecordState {
    public String classQualifiedName;
    public String methodName;
    public int parameterCount = -1;
    public String jsonArgs;
    public String pid;
    public String appDisplayName;
    public String[] parameterTypes;

    public boolean hasRun() {
        return classQualifiedName != null && methodName != null && pid != null;
    }

    public @NotNull LastRunRecord toRecord() {
        return new LastRunRecord(
            classQualifiedName, methodName, parameterCount,
            jsonArgs != null ? jsonArgs : "",
            pid, appDisplayName != null ? appDisplayName : "",
            parameterTypes
        );
    }

    public static @NotNull LastRunRecordState from(@NotNull LastRunRecord record) {
        LastRunRecordState s = new LastRunRecordState();
        s.classQualifiedName = record.getClassQualifiedName();
        s.methodName = record.getMethodName();
        s.parameterCount = record.getParameterCount();
        s.jsonArgs = record.getJsonArgs();
        s.pid = record.getPid();
        s.appDisplayName = record.getAppDisplayName();
        s.parameterTypes = record.getParameterTypes();
        return s;
    }
}
