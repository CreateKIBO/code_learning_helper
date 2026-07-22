package com.codementor.review;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 评审会话 —— 当前评审状态的唯一真实来源（方案第 7 节架构约束）。
 *
 * <p>UI（教练侧栏）不自己保存状态，而是订阅这个会话，状态一变就刷新。
 * 这样保证"数据只有一份"，不会出现 UI 和实际状态对不上的 bug。
 *
 * <p>线程模型：评审是后台任务发起的，结果可能从任意线程回来；
 * 但监听器回调约定在 EDT（UI 线程）执行，由发起方负责切线程。
 * 阶段 0 用 {@link CopyOnWriteArrayList} 保证监听器增删的线程安全。
 */
public class ReviewSession {

    /** 单例：整个插件共享一个当前评审会话（个人工具，一次只评一段）。 */
    private static final ReviewSession INSTANCE = new ReviewSession();

    public static ReviewSession getInstance() { return INSTANCE; }

    private ReviewSession() {}

    public interface Listener {
        /** 状态或内容变化时回调（约定在 EDT 执行）。 */
        void onSessionChanged(ReviewSession session);
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private ReviewStatus status = ReviewStatus.IDLE;
    private ReviewContext context;          // 本次评审的上下文（含指纹）
    private List<Suggestion> suggestions;   // 评审结果（SUCCESS 时有值）
    private String errorMessage;            // FAILED 时的错误信息
    private String requestId;               // 请求 ID，用于日志追踪

    // ---------------- 订阅 ----------------

    public void addListener(Listener l) { listeners.add(l); }

    private void fireChanged() {
        for (Listener l : listeners) {
            try {
                l.onSessionChanged(this);
            } catch (Exception e) {
                // 单个监听器出错不影响其他监听器和会话本身
                // 阶段 0 用 println；阶段 1 换 Logger
                System.err.println("[CodeMentor] listener error: " + e.getMessage());
            }
        }
    }

    // ---------------- 状态变更方法（由 ReviewService 调用） ----------------

    /** 进入请求中状态，记录上下文（含指纹，供后续过期判定）。 */
    public void startRequest(ReviewContext ctx, String requestId) {
        this.context = ctx;
        this.requestId = requestId;
        this.status = ReviewStatus.REQUESTING;
        this.suggestions = null;
        this.errorMessage = null;
        fireChanged();
    }

    /** 请求成功，填入建议列表。 */
    public void succeed(List<Suggestion> suggestions) {
        this.suggestions = suggestions;
        this.status = ReviewStatus.SUCCESS;
        this.errorMessage = null;
        fireChanged();
    }

    /** 请求失败。 */
    public void fail(String message) {
        this.status = ReviewStatus.FAILED;
        this.errorMessage = message;
        this.suggestions = null;
        fireChanged();
    }

    /** 旧结果过期（文档已变，结果不再适用）。 */
    public void expire() {
        if (status == ReviewStatus.REQUESTING || status == ReviewStatus.SUCCESS) {
            this.status = ReviewStatus.EXPIRED;
            fireChanged();
        }
    }

    /** 重置为空闲（新评审开始前或用户主动清空）。 */
    public void reset() {
        this.status = ReviewStatus.IDLE;
        this.context = null;
        this.suggestions = null;
        this.errorMessage = null;
        fireChanged();
    }

    // ---------------- 读取方法（由 UI 调用） ----------------

    public ReviewStatus getStatus() { return status; }
    public ReviewContext getContext() { return context; }
    public List<Suggestion> getSuggestions() { return suggestions; }
    public String getErrorMessage() { return errorMessage; }
}
