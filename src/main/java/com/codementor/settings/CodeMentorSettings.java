package com.codementor.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * CodeMentor 应用级设置（方案第 12 节）。
 *
 * <p>用 {@link PersistentStateComponent} 把配置序列化到本地 XML。
 * <b>注意：API Key 不在这里</b>——敏感数据必须走 {@link ApiKeyStore}（PasswordSafe），
 * 不能落盘明文（方案第 11 节）。
 *
 * <p>这里只存：API 地址、模型名、教学级别、评审开关、是否已同意首次发送代码。
 * 用 {@link Service.Level#APP} 应用级（单用户、跨项目共享）。
 */
@Service(Service.Level.APP)
@State(name = "CodeMentorSettings", storages = @Storage("codeMentor.xml"))
public final class CodeMentorSettings implements PersistentStateComponent<CodeMentorSettings.State> {

    /** 教学级别（方案第 10 节）。 */
    public enum TeachingLevel { BEGINNER, INTERMEDIATE, JAVAWEB }

    /** 默认值集中在这里，便于设置页和客户端统一引用。 */
    public static final class Defaults {
        public static final String API_BASE_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
        public static final String MODEL = "glm-4-plus";
        public static final TeachingLevel TEACHING_LEVEL = TeachingLevel.JAVAWEB;
        public static final boolean ENABLE_REVIEW = true;
        public static final boolean ENABLE_SAVE_REVIEW = false;
        public static final boolean PREVIEW_EVERY_MANUAL_REQUEST = false;
        public static final String EXCLUDED_FILE_PATTERNS = "";
        private Defaults() {}
    }

    /** 可序列化的状态对象。字段必须 public + 非 final，由 IntelliJ 反射读写。 */
    public static class State {
        public String apiBaseUrl = Defaults.API_BASE_URL;
        public String model = Defaults.MODEL;
        public String teachingLevel = Defaults.TEACHING_LEVEL.name();
        public boolean enableReview = Defaults.ENABLE_REVIEW;
        public boolean enableSaveReview = Defaults.ENABLE_SAVE_REVIEW;
        public boolean previewEveryManualRequest =
                Defaults.PREVIEW_EVERY_MANUAL_REQUEST;
        public String excludedFilePatterns = Defaults.EXCLUDED_FILE_PATTERNS;
        /** 是否已同意"首次发送代码"（方案第 11 节，首次发送前预览确认，确认后不再弹）。 */
        public boolean consentedToSend = false;
    }

    private State state = new State();

    public static CodeMentorSettings getInstance() {
        return ApplicationManager.getApplication().getService(CodeMentorSettings.class);
    }

    @Override
    public @Nullable State getState() { return state; }

    @Override
    public void loadState(@NotNull State state) { this.state = state; }

    // ---------------- 业务 getter/setter ----------------

    public String getApiBaseUrl() { return state.apiBaseUrl; }
    public void setApiBaseUrl(String url) { state.apiBaseUrl = url; }

    public String getModel() { return state.model; }
    public void setModel(String model) { state.model = model; }

    public TeachingLevel getTeachingLevel() {
        try {
            return TeachingLevel.valueOf(state.teachingLevel);
        } catch (Exception e) {
            return Defaults.TEACHING_LEVEL;
        }
    }
    public void setTeachingLevel(TeachingLevel level) { state.teachingLevel = level.name(); }

    public boolean isReviewEnabled() { return state.enableReview; }
    public void setReviewEnabled(boolean enabled) { state.enableReview = enabled; }

    public boolean isSaveReviewEnabled() { return state.enableSaveReview; }
    public void setSaveReviewEnabled(boolean enabled) { state.enableSaveReview = enabled; }

    public boolean hasConsentedToSend() { return state.consentedToSend; }
    public void setConsentedToSend(boolean consented) { state.consentedToSend = consented; }

    public boolean isPreviewEveryManualRequest() {
        return state.previewEveryManualRequest;
    }
    public void setPreviewEveryManualRequest(boolean enabled) {
        state.previewEveryManualRequest = enabled;
    }

    public String getExcludedFilePatterns() {
        return state.excludedFilePatterns == null ? "" : state.excludedFilePatterns;
    }
    public void setExcludedFilePatterns(String patterns) {
        state.excludedFilePatterns = patterns == null ? "" : patterns;
    }
}
