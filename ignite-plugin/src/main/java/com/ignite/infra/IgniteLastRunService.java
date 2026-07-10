package com.ignite.infra;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Service(Service.Level.PROJECT)
public final class IgniteLastRunService {

    private volatile LastRunRecord lastRun;

    public static IgniteLastRunService getInstance(@NotNull Project project) {
        return project.getService(IgniteLastRunService.class);
    }

    public void save(@NotNull LastRunRecord record) {
        this.lastRun = record;
    }

    public boolean hasLastRun() {
        return lastRun != null;
    }

    public @Nullable LastRunRecord getLastRun() {
        return lastRun;
    }

    public @Nullable String getLastRunDescription() {
        if (lastRun == null) {
            return null;
        }
        return "重新运行: " + lastRun.getMethodSignature() + " @ " + lastRun.getAppDisplayName();
    }

    public @Nullable PsiMethod resolveMethod(@NotNull Project project, @NotNull LastRunRecord record) {
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        PsiClass psiClass = facade.findClass(record.getClassQualifiedName(), GlobalSearchScope.allScope(project));
        if (psiClass == null) {
            return null;
        }
        for (PsiMethod method : psiClass.findMethodsByName(record.getMethodName(), false)) {
            if (method.getParameterList().getParametersCount() == record.getParameterCount()) {
                return method;
            }
        }
        return null;
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
