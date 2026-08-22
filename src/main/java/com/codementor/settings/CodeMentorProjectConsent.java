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
 */
@Service(Service.Level.PROJECT)
@State(
        name = "CodeMentorProjectConsent",
        storages = @Storage(
                value = StoragePathMacros.WORKSPACE_FILE,
                roamingType = RoamingType.DISABLED))
public final class CodeMentorProjectConsent
        implements PersistentStateComponent<CodeMentorProjectConsent.State> {

    public static class State {
        public String consentedEndpoint = "";
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

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
