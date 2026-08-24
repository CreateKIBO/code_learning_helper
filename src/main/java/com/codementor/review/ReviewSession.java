package com.codementor.review;

import com.codementor.history.KnowledgeReminder;
import com.codementor.practice.Exercise;
import com.codementor.practice.ExerciseResult;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 评审会话 —— 当前评审状态的唯一真实来源（方案第 7 节架构约束）。
 *
 * <p>UI（教练侧栏）不自己保存状态，而是订阅这个会话，状态一变就刷新。
 * 这样保证"数据只有一份"，不会出现 UI 和实际状态对不上的 bug。
 *
 * <p>线程模型：评审是后台任务发起的，结果可能从任意线程回来；
 * 但监听器回调约定在 EDT（UI 线程）执行，由发起方负责切线程。
 * 监听器列表使用 {@link CopyOnWriteArrayList}，保证订阅和退订线程安全。
 *
 * <p>迟到响应守卫：每次请求有唯一 requestId，{@link #succeed} / {@link #fail} /
 * {@link #expire} 都只接受"当前请求"的 id。快速连按 Alt+M 时，慢的旧响应回来
 * 会发现 id 已不匹配，直接丢弃——旧结果永远盖不到新会话上（方案第 8 节核心机制）。
 */
@Service(Service.Level.PROJECT)
public final class ReviewSession {

    private static final Logger LOG = Logger.getInstance(ReviewSession.class);

    public static ReviewSession getInstance(Project project) {
        return project.getService(ReviewSession.class);
    }

    public interface Listener {
        /** 状态或内容变化时回调（约定在 EDT 执行）。 */
        void onSessionChanged(ReviewSession session);
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private ReviewStatus status = ReviewStatus.IDLE;
    private ReviewContext context;          // 本次评审的上下文（含指纹）
    private List<Suggestion> suggestions;   // 评审结果（SUCCESS 时有值）
    private String summary = "";            // 模型对当前可见上下文的整体总结
    private String errorMessage;            // FAILED 时的错误信息
    private String requestId;               // 请求 ID，用于日志追踪
    private String learningReviewId;        // 学习事件关联 ID，结果过期后仍保留
    private final Map<String, VerificationResult> verificationResults = new HashMap<>();
    private final Map<String, String> verificationRequestIds = new HashMap<>();
    private final Map<String, FeedbackType> feedback = new HashMap<>();
    private final Map<String, FollowUpResult> followUpResults = new HashMap<>();
    private final Map<String, String> followUpRequestIds = new HashMap<>();
    private final Set<String> viewedExplanations = new HashSet<>();
    private final Set<String> expandedExplanations = new HashSet<>();
    private final Set<String> expandedExamples = new HashSet<>();
    private List<KnowledgeReminder> knowledgeReminders = List.of();
    private ExerciseResult exerciseResult = ExerciseResult.idle();
    private String exerciseRequestId;
    private int revealedExerciseHints;

    // ---------------- 订阅 ----------------

    public void addListener(Listener l) { listeners.add(l); }

    /** 退订。面板销毁时必须调用，避免项目服务持有已经销毁的 UI。 */
    public void removeListener(Listener l) { listeners.remove(l); }

    private void fireChanged() {
        for (Listener l : listeners) {
            try {
                l.onSessionChanged(this);
            } catch (ProcessCanceledException e) {
                throw e;
            } catch (Exception e) {
                // 单个监听器出错不影响其他监听器和会话本身
                LOG.warn("会话监听器回调异常", e);
            }
        }
    }

    // ---------------- 状态变更方法（由 ReviewService 调用） ----------------

    /** 进入请求中状态，记录上下文（含指纹，供后续过期判定）。 */
    public void startRequest(ReviewContext ctx, String requestId) {
        this.context = ctx;
        this.requestId = requestId;
        this.learningReviewId = requestId;
        this.status = ReviewStatus.REQUESTING;
        this.suggestions = null;
        this.summary = "";
        this.errorMessage = null;
        clearLearningProgress();
        fireChanged();
    }

    /** 请求成功，填入建议列表。迟到的旧响应（requestId 不匹配）直接丢弃。 */
    public boolean succeed(String requestId, ReviewResult result) {
        return succeed(requestId, result, requestId);
    }

    public boolean succeed(
            String requestId, ReviewResult result, String learningReviewId) {
        if (!isCurrentRequest(requestId)) return false;
        this.summary = result.summary();
        this.suggestions = result.suggestions();
        if (learningReviewId != null && !learningReviewId.isBlank()) {
            this.learningReviewId = learningReviewId;
        }
        this.status = ReviewStatus.SUCCESS;
        this.errorMessage = null;
        fireChanged();
        return true;
    }

    /** Compatibility helper for tests and local services that do not provide a summary. */
    public boolean succeed(String requestId, List<Suggestion> suggestions) {
        return succeed(requestId, new ReviewResult("", suggestions));
    }

    /** 请求失败。迟到的旧响应（requestId 不匹配）直接丢弃。 */
    public void fail(String requestId, String message) {
        if (!isCurrentRequest(requestId)) return;
        this.status = ReviewStatus.FAILED;
        this.errorMessage = message;
        this.suggestions = null;
        this.summary = "";
        fireChanged();
    }

    /** 旧结果过期（被评审的代码已变，结果不再适用）。只接受当前请求的 id。 */
    public void expire(String requestId) {
        if (!isCurrentRequest(requestId)) return;
        if (status == ReviewStatus.REQUESTING || status == ReviewStatus.SUCCESS) {
            this.status = ReviewStatus.EXPIRED;
            this.requestId = null; // 作废请求：随后迟到的成功/失败响应一并丢弃
            fireChanged();
        }
    }

    /** 重置为空闲（新评审开始前或用户主动清空）。同时作废进行中的请求。 */
    public void reset() {
        this.status = ReviewStatus.IDLE;
        this.context = null;
        this.suggestions = null;
        this.summary = "";
        this.errorMessage = null;
        this.requestId = null;
        this.learningReviewId = null;
        clearLearningProgress();
        fireChanged();
    }

    /** 开始验证一条建议。不同建议可各自拥有请求 ID，迟到响应不会串线。 */
    public void startVerification(String suggestionId, String verificationRequestId) {
        verificationRequestIds.put(suggestionId, verificationRequestId);
        verificationResults.put(suggestionId, VerificationResult.verifying());
        fireChanged();
    }

    public boolean completeVerification(String suggestionId, String verificationRequestId,
                                        VerificationResult result) {
        if (!isCurrentVerification(suggestionId, verificationRequestId)) return false;
        verificationRequestIds.remove(suggestionId);
        verificationResults.put(suggestionId, result);
        fireChanged();
        return true;
    }

    public boolean failVerification(String suggestionId, String verificationRequestId,
                                    String message) {
        return completeVerification(
                suggestionId, verificationRequestId, VerificationResult.failed(message));
    }

    /**
     * 清除已经不再对应当前文档版本的验证结论，但保留正在进行的验证；后者由
     * VerificationCoordinator 的文档监听器取消并给出明确失败原因。
     */
    public boolean invalidateCompletedVerifications() {
        boolean changed = verificationResults.entrySet().removeIf(
                entry -> entry.getValue().getStatus() != VerificationStatus.VERIFYING);
        if (changed) fireChanged();
        return changed;
    }

    public boolean setFeedback(String suggestionId, FeedbackType feedbackType) {
        FeedbackType previous = feedback.put(suggestionId, feedbackType);
        if (feedbackType == previous) return false;
        fireChanged();
        return true;
    }

    /** 只在首次展开时返回 true，供学习记录去重。 */
    public boolean markExplanationViewed(String suggestionId) {
        return viewedExplanations.add(suggestionId);
    }

    public boolean toggleExplanation(String suggestionId) {
        boolean expanded;
        if (expandedExplanations.remove(suggestionId)) {
            expandedExamples.remove(suggestionId);
            expanded = false;
        } else {
            expandedExplanations.add(suggestionId);
            expanded = true;
        }
        fireChanged();
        return expanded;
    }

    public boolean toggleExample(String suggestionId) {
        if (!expandedExplanations.contains(suggestionId)) return false;
        boolean expanded = !expandedExamples.remove(suggestionId);
        if (expanded) expandedExamples.add(suggestionId);
        fireChanged();
        return expanded;
    }

    public boolean isExplanationExpanded(String suggestionId) {
        return expandedExplanations.contains(suggestionId);
    }

    public boolean isExampleExpanded(String suggestionId) {
        return expandedExamples.contains(suggestionId);
    }

    public void startFollowUp(
            String suggestionId, String followUpRequestId, String question) {
        followUpRequestIds.put(suggestionId, followUpRequestId);
        followUpResults.put(suggestionId, FollowUpResult.requesting(question));
        fireChanged();
    }

    public boolean completeFollowUp(
            String suggestionId, String followUpRequestId, String answer) {
        if (!isCurrentFollowUp(suggestionId, followUpRequestId)) return false;
        followUpRequestIds.remove(suggestionId);
        String question = getFollowUpResult(suggestionId).getQuestion();
        followUpResults.put(suggestionId, FollowUpResult.success(question, answer));
        fireChanged();
        return true;
    }

    public boolean failFollowUp(
            String suggestionId, String followUpRequestId, String message) {
        if (!isCurrentFollowUp(suggestionId, followUpRequestId)) return false;
        followUpRequestIds.remove(suggestionId);
        String question = getFollowUpResult(suggestionId).getQuestion();
        followUpResults.put(suggestionId, FollowUpResult.failed(question, message));
        fireChanged();
        return true;
    }

    public boolean cancelFollowUp(String suggestionId) {
        if (!followUpRequestIds.containsKey(suggestionId)) return false;
        followUpRequestIds.remove(suggestionId);
        String question = getFollowUpResult(suggestionId).getQuestion();
        followUpResults.put(
                suggestionId,
                FollowUpResult.failed(question, "已取消本次追问。"));
        fireChanged();
        return true;
    }

    public void startExercise(String requestId) {
        exerciseRequestId = requestId;
        exerciseResult = ExerciseResult.generating();
        revealedExerciseHints = 0;
        fireChanged();
    }

    public boolean completeExercise(String requestId, Exercise exercise) {
        if (!isCurrentExercise(requestId)) return false;
        exerciseRequestId = null;
        exerciseResult = ExerciseResult.success(exercise);
        revealedExerciseHints = 0;
        fireChanged();
        return true;
    }

    public boolean failExercise(String requestId, String message) {
        if (!isCurrentExercise(requestId)) return false;
        exerciseRequestId = null;
        exerciseResult = ExerciseResult.failed(message);
        revealedExerciseHints = 0;
        fireChanged();
        return true;
    }

    public void cancelExercise() {
        if (exerciseResult.getStatus() == ExerciseResult.Status.IDLE) return;
        exerciseRequestId = null;
        exerciseResult = ExerciseResult.idle();
        revealedExerciseHints = 0;
        fireChanged();
    }

    /** Reveals one exercise hint at a time so the learner can try before seeing more help. */
    public boolean revealNextExerciseHint() {
        Exercise exercise = exerciseResult.getExercise();
        if (exerciseResult.getStatus() != ExerciseResult.Status.SUCCESS
                || exercise == null
                || revealedExerciseHints >= exercise.getHints().size()) {
            return false;
        }
        revealedExerciseHints++;
        fireChanged();
        return true;
    }

    private boolean isCurrentVerification(String suggestionId, String verificationRequestId) {
        return verificationRequestId != null
                && verificationRequestId.equals(verificationRequestIds.get(suggestionId));
    }

    private boolean isCurrentFollowUp(String suggestionId, String followUpRequestId) {
        return followUpRequestId != null
                && followUpRequestId.equals(followUpRequestIds.get(suggestionId));
    }

    private boolean isCurrentExercise(String requestId) {
        return requestId != null && requestId.equals(exerciseRequestId);
    }

    private void clearLearningProgress() {
        verificationResults.clear();
        verificationRequestIds.clear();
        feedback.clear();
        followUpResults.clear();
        followUpRequestIds.clear();
        viewedExplanations.clear();
        expandedExplanations.clear();
        expandedExamples.clear();
        knowledgeReminders = List.of();
        exerciseResult = ExerciseResult.idle();
        exerciseRequestId = null;
        revealedExerciseHints = 0;
    }

    /** 判断是否是当前正在等待结果的请求。 */
    private boolean isCurrentRequest(String requestId) {
        return requestId != null && requestId.equals(this.requestId);
    }

    // ---------------- 读取方法（由 UI 调用） ----------------

    public ReviewStatus getStatus() { return status; }
    public ReviewContext getContext() { return context; }
    public List<Suggestion> getSuggestions() { return suggestions; }
    public String getSummary() { return summary; }
    public String getErrorMessage() { return errorMessage; }
    public VerificationResult getVerificationResult(String suggestionId) {
        return verificationResults.getOrDefault(
                suggestionId,
                new VerificationResult(VerificationStatus.IDLE, "", ""));
    }
    public FeedbackType getFeedback(String suggestionId) { return feedback.get(suggestionId); }
    public FollowUpResult getFollowUpResult(String suggestionId) {
        return followUpResults.getOrDefault(suggestionId, FollowUpResult.idle());
    }
    public List<KnowledgeReminder> getKnowledgeReminders() { return knowledgeReminders; }
    public ExerciseResult getExerciseResult() { return exerciseResult; }
    public int getRevealedExerciseHints() { return revealedExerciseHints; }
    /** Single derived busy signal for every model-backed operation in this session. */
    public boolean hasPendingOperation() {
        return status == ReviewStatus.REQUESTING
                || verificationResults.values().stream().anyMatch(result ->
                        result.getStatus() == VerificationStatus.VERIFYING)
                || followUpResults.values().stream().anyMatch(result ->
                        result.getStatus() == FollowUpResult.Status.REQUESTING)
                || exerciseResult.getStatus() == ExerciseResult.Status.GENERATING;
    }
    public String getLearningReviewId() {
        return learningReviewId == null ? "" : learningReviewId;
    }

    public void setKnowledgeReminders(List<KnowledgeReminder> reminders) {
        knowledgeReminders = List.copyOf(reminders);
        fireChanged();
    }
}
