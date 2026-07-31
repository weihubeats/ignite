package com.ignite.infra;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(
    name = "IgniteLastRun",
    storages = @Storage("IgniteLastRunSettings.xml")
)
@Service(Service.Level.PROJECT)
public final class IgniteLastRunService implements PersistentStateComponent<LastRunRecordState> {

    private volatile LastRunRecordState state = new LastRunRecordState();

    public static IgniteLastRunService getInstance(@NotNull Project project) {
        return project.getService(IgniteLastRunService.class);
    }

    public void save(@NotNull LastRunRecord record) {
        this.state = LastRunRecordState.from(record);
    }

    public boolean hasLastRun() {
        return state.hasRun();
    }

    public @Nullable LastRunRecord getLastRun() {
        return state.hasRun() ? state.toRecord() : null;
    }

    public @Nullable String getLastRunDescription() {
        if (!state.hasRun()) {
            return null;
        }
        return "重新运行: " + state.classQualifiedName + "." + state.methodName + " @ " + state.appDisplayName;
    }

    @Nullable
    @Override
    public LastRunRecordState getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull LastRunRecordState state) {
        this.state = state;
    }

    public @Nullable PsiMethod resolveMethod(@NotNull Project project, @NotNull LastRunRecord record) {
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        PsiClass psiClass = facade.findClass(record.getClassQualifiedName(), GlobalSearchScope.allScope(project));
        if (psiClass == null) {
            return null;
        }
        for (PsiMethod method : psiClass.findMethodsByName(record.getMethodName(), false)) {
            if (method.getParameterList().getParametersCount() != record.getParameterCount()) {
                continue;
            }
            if (psiParameterTypesMatch(method, record)) {
                return method;
            }
        }
        return null;
    }

    private boolean psiParameterTypesMatch(PsiMethod method, @NotNull LastRunRecord record) {
        PsiParameter[] parameters = method.getParameterList().getParameters();
        String[] expectedTypes = record.getParameterTypes() != null ? record.getParameterTypes() : null;
        if (expectedTypes == null) {
            return true;
        }
        if (parameters.length != expectedTypes.length) {
            return false;
        }
        for (int i = 0; i < parameters.length; i++) {
            String actual = parameters[i].getType().getCanonicalText();
            if (actual == null || !actual.equals(expectedTypes[i])) {
                return false;
            }
        }
        return true;
    }

    public @Nullable PidUtils.RunningApp resolveRunningApp(@NotNull Project project, @NotNull LastRunRecord record) {
        for (PidUtils.RunningApp app : PidUtils.getRunningApps(project)) {
            if (record.getPid().equals(app.pid)) {
                return app;
            }
        }
        return null;
    }
}
