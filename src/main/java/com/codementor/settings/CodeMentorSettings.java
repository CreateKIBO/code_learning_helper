package com.codementor.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Application-level, non-secret CodeMentor settings.
 *
 * <p>The API key is deliberately stored through {@link ApiKeyStore}. Consent is
 * deliberately not stored here either: it is project-scoped and endpoint-bound
 * in {@link CodeMentorProjectConsent}.
 */
@Service(Service.Level.APP)
@State(
        name = "CodeMentorSettings",
        storages = @Storage(value = "codeMentor.xml", roamingType = RoamingType.DISABLED))
public final class CodeMentorSettings
        implements PersistentStateComponent<CodeMentorSettings.State> {

    public enum TeachingLevel {
        BEGINNER("初学"),
        INTERMEDIATE("进阶"),
        JAVAWEB("JavaWeb");

        private final String displayName;

        TeachingLevel(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /** Defaults shared by the settings UI and immutable request snapshots. */
    public static final class Defaults {
        public static final String API_BASE_URL =
                "https://open.bigmodel.cn/api/paas/v4/chat/completions";
        public static final String MODEL = "glm-5.2";
        public static final TeachingLevel TEACHING_LEVEL = TeachingLevel.JAVAWEB;
        public static final boolean ENABLE_REVIEW = true;
        public static final boolean ENABLE_SAVE_REVIEW = false;
        public static final boolean PREVIEW_EVERY_MANUAL_REQUEST = false;
        public static final boolean ALLOW_INSECURE_LOCAL_ENDPOINT = false;
        public static final String EXCLUDED_FILE_PATTERNS = "";

        private Defaults() {}
    }

    /** Public mutable fields are required by IntelliJ's XML serializer. */
    public static class State {
        public String apiBaseUrl = Defaults.API_BASE_URL;
        public String model = Defaults.MODEL;
        public String teachingLevel = Defaults.TEACHING_LEVEL.name();
        public boolean enableReview = Defaults.ENABLE_REVIEW;
        public boolean enableSaveReview = Defaults.ENABLE_SAVE_REVIEW;
        public boolean previewEveryManualRequest =
                Defaults.PREVIEW_EVERY_MANUAL_REQUEST;
        public boolean allowInsecureLocalEndpoint =
                Defaults.ALLOW_INSECURE_LOCAL_ENDPOINT;
        public String excludedFilePatterns = Defaults.EXCLUDED_FILE_PATTERNS;

        /**
         * Legacy 1.0 field retained only so old XML can be read. It is ignored:
         * application-wide consent is not safe for multiple projects/endpoints.
         */
        @Deprecated public boolean consentedToSend = false;
    }

    private State state = new State();

    public static CodeMentorSettings getInstance() {
        return ApplicationManager.getApplication().getService(CodeMentorSettings.class);
    }

    @Override
    public synchronized @Nullable State getState() {
        return state;
    }

    @Override
    public synchronized void loadState(@NotNull State loadedState) {
        loadedState.consentedToSend = false;
        if ("glm-4-plus".equals(loadedState.model)) {
            loadedState.model = Defaults.MODEL;
        }
        state = loadedState;
    }

    public synchronized String getApiBaseUrl() {
        return nonBlankOrDefault(state.apiBaseUrl, Defaults.API_BASE_URL);
    }

    public synchronized void setApiBaseUrl(String url) {
        state.apiBaseUrl = normalize(url);
    }

    public synchronized String getModel() {
        return nonBlankOrDefault(state.model, Defaults.MODEL);
    }

    public synchronized void setModel(String model) {
        state.model = normalize(model);
    }

    public synchronized TeachingLevel getTeachingLevel() {
        try {
            return TeachingLevel.valueOf(state.teachingLevel);
        } catch (Exception ignored) {
            return Defaults.TEACHING_LEVEL;
        }
    }

    public synchronized void setTeachingLevel(TeachingLevel level) {
        state.teachingLevel = (level == null ? Defaults.TEACHING_LEVEL : level).name();
    }

    public synchronized boolean isReviewEnabled() {
        return state.enableReview;
    }

    public synchronized void setReviewEnabled(boolean enabled) {
        state.enableReview = enabled;
    }

    public synchronized boolean isSaveReviewEnabled() {
        return state.enableSaveReview;
    }

    public synchronized void setSaveReviewEnabled(boolean enabled) {
        state.enableSaveReview = enabled;
    }

    public synchronized boolean isPreviewEveryManualRequest() {
        return state.previewEveryManualRequest;
    }

    public synchronized void setPreviewEveryManualRequest(boolean enabled) {
        state.previewEveryManualRequest = enabled;
    }

    public synchronized boolean isInsecureLocalEndpointAllowed() {
        return state.allowInsecureLocalEndpoint;
    }

    public synchronized void setInsecureLocalEndpointAllowed(boolean allowed) {
        state.allowInsecureLocalEndpoint = allowed;
    }

    public synchronized String getExcludedFilePatterns() {
        return state.excludedFilePatterns == null ? "" : state.excludedFilePatterns;
    }

    public synchronized void setExcludedFilePatterns(String patterns) {
        state.excludedFilePatterns = normalize(patterns);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nonBlankOrDefault(String value, String fallback) {
        String normalized = normalize(value);
        return normalized.isEmpty() ? fallback : normalized;
    }
}
