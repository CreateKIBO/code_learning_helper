package com.codementor.review;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
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
 * <p>实现：实现 {@link DocumentListener}，评审成功时挂载到目标文档。
 * 在 {@link #beforeDocumentChange} 里判断改动是否落在目标方法范围内：
 * <ul>
 *   <li>落在范围内 → 调用 {@link ReviewSession#expire()} 标记过期，并自动卸载自己（一次性）。</li>
 *   <li>落在范围外（用户改了别的方法）→ 忽略，不打扰。</li>
 * </ul>
 *
 * <p>线程：DocumentListener 回调在 EDT。expire() 内部 fireChanged 也在 EDT。
 * 但为稳妥，用 invokeLater 保证状态变更串行化。
 *
 * <p>生命周期：由 ReviewAction 负责 attach（评审成功后）和 detach（新评审开始前）。
 * 本类持有目标 context 和被监听的 document，detach 时移除自己。
 */
public class ExpiryWatcher implements DocumentListener {

    private final Document document;
    private final ReviewContext context;
    private final ReviewSession session;
    private boolean armed = true; // 触发一次过期后自动 disarm，避免重复 fire

    public ExpiryWatcher(Document document, ReviewContext context, ReviewSession session) {
        this.document = document;
        this.context = context;
        this.session = session;
    }

    /** 挂载到文档，开始监听。 */
    public void attach() {
        document.addDocumentListener(this);
    }

    /** 卸载，停止监听（评审重置或新评审开始时调用）。 */
    public void detach() {
        armed = false;
        document.removeDocumentListener(this);
    }

    @Override
    public void beforeDocumentChange(@NotNull DocumentEvent event) {
        if (!armed) return;
        // 只有改到目标方法范围内的代码，才认为建议过期
        if (context.affectsMethodRange(event.getOffset(), event.getNewLength())) {
            armed = false; // 一次性：触发后不再响应，直到下次评审重新挂载
            ApplicationManager.getApplication().invokeLater(() -> {
                session.expire();
                detach(); // 标记过期后自动卸载，避免后续每次输入都 fire
            });
        }
    }

    // DocumentListener 的其他方法（documentChanged / bulkUpdate*）都是 default 方法，
    // 本场景用不到，无需重写。
}
