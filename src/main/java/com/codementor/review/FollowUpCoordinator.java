package com.codementor.review;

import com.codementor.CodeMentorService;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 管理针对单条建议的追问，并取消同一建议尚未完成的旧追问。 */
@Service(Service.Level.PROJECT)
public final class FollowUpCoordinator implements Disposable {

    private final Project project;
    private final Map<String, CancellableRequest> pending = new HashMap<>();
    private final Map<String, String> pendingRequestIds = new HashMap<>();

    public FollowUpCoordinator(Project project) {
        this.project = project;
    }

    public static FollowUpCoordinator getInstance(Project project) {
        return project.getService(FollowUpCoordinator.class);
    }

    public void ask(Suggestion suggestion, String question) {
        String normalizedQuestion = question == null ? "" : question.trim();
        if (normalizedQuestion.isEmpty()) return;

        ReviewSession session = ReviewSession.getInstance(project);
        ReviewContext context = session.getContext();
        if (context == null) return;

        CancellableRequest previous = pending.remove(suggestion.getId());
        if (previous != null) {
            previous.cancel();
        }

        String requestId = UUID.randomUUID().toString().substring(0, 8);
        pendingRequestIds.put(suggestion.getId(), requestId);
        session.startFollowUp(suggestion.getId(), requestId);
        CancellableRequest request = CodeMentorService.getInstance()
                .getReviewService()
                .followUp(
                        new FollowUpRequest(context, suggestion, normalizedQuestion),
                        requestId,
                        new ReviewService.FollowUpCallback() {
                            @Override
                            public void onSuccess(String answer) {
                                ApplicationManager.getApplication().invokeLater(() -> {
                                    if (!requestId.equals(
                                            pendingRequestIds.get(suggestion.getId()))) {
                                        return;
                                    }
                                    pending.remove(suggestion.getId());
                                    pendingRequestIds.remove(suggestion.getId());
                                    session.completeFollowUp(
                                            suggestion.getId(), requestId, answer);
                                });
                            }

                            @Override
                            public void onFailure(String errorMessage) {
                                ApplicationManager.getApplication().invokeLater(() -> {
                                    if (!requestId.equals(
                                            pendingRequestIds.get(suggestion.getId()))) {
                                        return;
                                    }
                                    pending.remove(suggestion.getId());
                                    pendingRequestIds.remove(suggestion.getId());
                                    session.failFollowUp(
                                            suggestion.getId(), requestId, errorMessage);
                                });
                            }
                        });
        pending.put(suggestion.getId(), request);
    }

    @Override
    public void dispose() {
        for (CancellableRequest request : pending.values()) {
            request.cancel();
        }
        pending.clear();
        pendingRequestIds.clear();
    }
}
