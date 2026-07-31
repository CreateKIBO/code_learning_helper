package com.codementor.practice;

import com.codementor.CodeMentorService;
import com.codementor.history.KnowledgeReminder;
import com.codementor.history.LearningHistoryService;
import com.codementor.javaweb.JavaWebRole;
import com.codementor.review.CancellableRequest;
import com.codementor.review.ReviewContext;
import com.codementor.review.ReviewService;
import com.codementor.review.ReviewSession;
import com.codementor.review.Suggestion;
import com.codementor.settings.CodeMentorSettings;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;

import java.util.List;
import java.util.UUID;

/** 基于本地学习记录生成单知识点小练习，不读取或生成完整项目。 */
@Service(Service.Level.PROJECT)
public final class ExerciseCoordinator implements Disposable {

    private final Project project;
    private CancellableRequest currentRequest;

    public ExerciseCoordinator(Project project) {
        this.project = project;
    }

    public static ExerciseCoordinator getInstance(Project project) {
        return project.getService(ExerciseCoordinator.class);
    }

    public void generate() {
        cancelCurrent();
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        ReviewSession session = ReviewSession.getInstance(project);
        session.startExercise(requestId);
        LearningHistoryService.getInstance().findReminders(2, 1)
                .thenAccept(reminders ->
                        ApplicationManager.getApplication().invokeLater(
                                () -> startModelRequest(requestId, reminders)));
    }

    private void startModelRequest(String requestId, List<KnowledgeReminder> reminders) {
        if (project.isDisposed()) return;
        ReviewSession session = ReviewSession.getInstance(project);
        ExerciseTarget target = chooseTarget(session, reminders);
        if (target == null) {
            session.failExercise(
                    requestId,
                    "还没有可用于出题的学习记录。先完成一次代码评审，或让某个知识点累计出现两次。");
            return;
        }

        ReviewContext context = session.getContext();
        JavaWebRole role = context == null
                ? JavaWebRole.UNKNOWN
                : context.getJavaWebContext().getRole();
        ExerciseRequest request = new ExerciseRequest(
                target.knowledgePoint,
                target.occurrences,
                CodeMentorSettings.getInstance().getTeachingLevel(),
                role);
        ReviewService service = CodeMentorService.getInstance().getReviewService();
        currentRequest = service.generateExercise(
                request,
                requestId,
                new ReviewService.ExerciseCallback() {
                    @Override
                    public void onSuccess(Exercise exercise) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (session.completeExercise(requestId, exercise)) {
                                currentRequest = null;
                            }
                        });
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (session.failExercise(requestId, errorMessage)) {
                                currentRequest = null;
                            }
                        });
                    }
                });
    }

    private static ExerciseTarget chooseTarget(
            ReviewSession session, List<KnowledgeReminder> reminders) {
        if (!reminders.isEmpty()) {
            KnowledgeReminder reminder = reminders.get(0);
            return new ExerciseTarget(
                    reminder.getKnowledgePoint(), reminder.getOccurrences());
        }
        List<Suggestion> suggestions = session.getSuggestions();
        if (suggestions == null) return null;
        return suggestions.stream()
                .filter(suggestion -> !suggestion.getKnowledgePoint().isBlank())
                .findFirst()
                .map(suggestion -> new ExerciseTarget(
                        suggestion.getKnowledgePoint(), 1))
                .orElse(null);
    }

    private void cancelCurrent() {
        if (currentRequest != null) {
            currentRequest.cancel();
            currentRequest = null;
        }
    }

    @Override
    public void dispose() {
        cancelCurrent();
    }

    private record ExerciseTarget(String knowledgePoint, int occurrences) {}
}
