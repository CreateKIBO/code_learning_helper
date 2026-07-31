package com.codementor.review;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import org.jetbrains.annotations.NotNull;

/**
 * 过期检测器 —— 自动检测评审期间的代码是否被用户修改（方案第 2、8 节核心安全机制）。
 *
 * <p>解决的问题：评审返回的建议是基于"某一时刻的代码"的。用户继续编辑后行号会漂移，
 * 旧建议如果还显示，就会错位——这是早期方案"只靠行号定位"的硬伤。
 * 所以一旦发现被评审的代码变了，立刻把会话标记为 EXPIRED，UI 提示"请重新评审"。
 *
 * <p>实现：实现 {@link DocumentListener}，从评审发起时挂载到目标文档。
 * 目标方法的范围用 {@link RangeMarker} 跟踪——它会随编辑自动平移/伸缩：
 * <ul>
 *   <li>用户改<strong>方法前面</strong>的代码 → marker 自动跟着移动，不误判、不漏判；</li>
 *   <li>改动落在 marker 范围内 → 调用 {@link ReviewSession#expire(String)} 标记过期，
 *       并自动卸载自己（一次性）；</li>
 *   <li>改动落在范围外（用户改了别的方法）→ 忽略，不打扰。</li>
 * </ul>
 * （早期版本直接拿发起时记录的静态 offset 比对，用户在方法外编辑后 offset 全部漂移，
 * 既会把"改别的方法"误判成过期，也会漏掉"改本方法"——RangeMarker 就是为此而生的。）
 *
 * <p>线程：DocumentListener 回调在 EDT。为稳妥，状态变更用 invokeLater 串行化。
 * expire 携带 requestId：即使本 watcher 的回调排队期间用户已发起新评审，
 * 旧 watcher 也无法误杀新会话（会话只接受当前请求的 id）。
 *
 * <p>生命周期：由 ReviewCoordinator 负责 attach（评审发起时）和 detach（新评审开始前）。
 */
public class ExpiryWatcher implements DocumentListener {

    private final Document document;
    private final ReviewContext context;
    private final ReviewSession session;
    private final String requestId;
    private final Runnable onExpired;
    /** 目标方法的活体范围，随编辑自动更新。选中模式下为 null（无固定范围）。 */
    private RangeMarker methodRange;
    private boolean armed = true; // 触发一次过期后自动 disarm，避免重复 fire

    public ExpiryWatcher(Document document, ReviewContext context,
                         ReviewSession session, String requestId) {
        this(document, context, session, requestId, () -> {});
    }

    public ExpiryWatcher(Document document, ReviewContext context,
                         ReviewSession session, String requestId,
                         Runnable onExpired) {
        this.document = document;
        this.context = context;
        this.session = session;
        this.requestId = requestId;
        this.onExpired = onExpired;
    }

    /** 挂载到文档，开始监听。 */
    public void attach() {
        if (context.getMethodStart() >= 0 && context.getMethodEnd() > context.getMethodStart()) {
            // surviveOnDeletion=true：方法被整体删除时 marker 缩为零宽而不是失效，
            // 由我们在下次改动时按"范围相交"判定过期，语义更可控。
            // 注：不需要 setGreedy*（那是内部接口 RangeMarkerEx 的能力）——
            // 边界插入已被 isInsideTargetRange 判定为触及并立即过期，greedy 与否无影响。
            methodRange = document.createRangeMarker(
                    context.getMethodStart(), context.getMethodEnd(), true);
        }
        document.addDocumentListener(this);
    }

    /** 卸载，停止监听（评审重置或新评审开始时调用）。可重复调用。 */
    public void detach() {
        armed = false;
        document.removeDocumentListener(this);
        if (methodRange != null) {
            methodRange.dispose();
            methodRange = null;
        }
    }

    @Override
    public void beforeDocumentChange(@NotNull DocumentEvent event) {
        if (!armed) return;
        if (isInsideTargetRange(event)) {
            armed = false; // 一次性：触发后不再响应，直到下次评审重新挂载
            onExpired.run();
            ApplicationManager.getApplication().invokeLater(() -> {
                session.expire(requestId);
                detach(); // 标记过期后自动卸载，避免后续每次输入都 fire
            });
        }
    }

    /**
     * 判断本次改动是否触及被评审的代码。
     * 选中模式（无 marker）：任何改动都算（无法定位范围，保守处理）。
     */
    private boolean isInsideTargetRange(DocumentEvent event) {
        if (methodRange == null) {
            return true;
        }
        if (!methodRange.isValid()) {
            return true; // marker 意外失效：宁可误报不可漏报
        }
        int start = methodRange.getStartOffset();
        int end = methodRange.getEndOffset();
        int offset = event.getOffset();
        if (event.getOldLength() == 0) {
            // 纯插入：被插入的旧坐标是一个点，落在 [start, end] 内（含边界）即算触及
            return offset >= start && offset <= end;
        }
        // 删除或替换：被删区间 [offset, offset+oldLength) 与方法区间相交
        return offset < end && offset + event.getOldLength() > start;
    }

    // DocumentListener 的其他方法（documentChanged / bulkUpdate*）都是 default 方法，
    // 本场景用不到，无需重写。
}
