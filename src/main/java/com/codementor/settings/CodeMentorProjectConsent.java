package com.codementor.settings;

import com.codementor.model.ModelRequestConfig;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Consent to send learning context, scoped to one project and one normalized endpoint.
 * Stored in the project's workspace file so it is not shared through version control.
 *
 * <p>同一份项目级状态还保存“是否允许读取整个项目来建立项目画像”的决定。两者相互独立：
 * 读取授权只决定本机能否扫描并记住项目结构；画像摘要是否随请求发出，仍受发送授权、
 * 发送预览和出站守卫约束。
 */
@Service(Service.Level.PROJECT)
@State(
        name = "CodeMentorProjectConsent",
        storages = @Storage(
                value = StoragePathMacros.WORKSPACE_FILE,
                roamingType = RoamingType.DISABLED))
public final class CodeMentorProjectConsent
        implements PersistentStateComponent<CodeMentorProjectConsent.State> {

    /** 用户对“读取整个项目”的决定；UNDECIDED 表示还没决定，每次 IDE 会话最多主动询问一次。 */
    public enum ProjectScanDecision {
        UNDECIDED,
        GRANTED,
        DENIED
    }

    public static class State {
        public String consentedEndpoint = "";
        /** {@link ProjectScanDecision} 的名称；未知值按 UNDECIDED 处理。 */
        public String projectScanDecision = ProjectScanDecision.UNDECIDED.name();
    }

    private State state = new State();

    public static CodeMentorProjectConsent getInstance(Project project) {
        return project.getService(CodeMentorProjectConsent.class);
    }

    @Override
    public synchronized @Nullable State getState() {
        return state;
    }

    @Override
    public synchronized void loadState(@NotNull State loadedState) {
        state = loadedState;
    }

    public synchronized boolean hasConsentFor(ModelRequestConfig config) {
        return config != null
                && config.endpoint().equals(normalize(state.consentedEndpoint));
    }

    public synchronized void grant(ModelRequestConfig config) {
        state.consentedEndpoint = config == null ? "" : config.endpoint();
    }

    public synchronized void revoke() {
        state.consentedEndpoint = "";
    }

    public synchronized ProjectScanDecision getProjectScanDecision() {
        try {
            return ProjectScanDecision.valueOf(normalize(state.projectScanDecision));
        } catch (IllegalArgumentException e) {
            return ProjectScanDecision.UNDECIDED;
        }
    }

    public synchronized void setProjectScanDecision(ProjectScanDecision decision) {
        state.projectScanDecision =
                (decision == null ? ProjectScanDecision.UNDECIDED : decision).name();
    }

    public synchronized boolean isProjectScanGranted() {
        return getProjectScanDecision() == ProjectScanDecision.GRANTED;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
