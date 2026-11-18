package com.codementor.review;

import com.codementor.context.ContextTarget;
import com.codementor.project.ProjectCalls;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;

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
 *   <li>同一文件发生任意编辑都会让旧模型上下文过期，因为 imports、字段、注解和诊断
 *       也可能参与本次请求；</li>
 *   <li>首次过期后继续轻量监听：保留 marker 供修改验证复定位，并在后续编辑时
 *       清除已经不再对应当前代码的验证结论；</li>
 *   <li>开始新评审或重置项目时才最终释放 marker。</li>
 * </ul>
 * 这比只检查目标方法更保守，但不会接受基于旧字段、旧 imports 或旧类注解的迟到响应。
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
    private boolean armed = true; // 首次编辑只触发一次评审过期，后续编辑只失效验证结论
    private boolean listening;
    private ProjectCalls projectCalls = ProjectCalls.EMPTY;
    private Disposable projectCallListeners;

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
            // 使用默认 surviveOnExternalChange=false：如果文件被磁盘重载或整篇替换，
            // marker 必须失效，不能仅凭新文件相同偏移处的方法误认成原评审目标。
            // 普通编辑仍会让 marker 随偏移移动；整段删除则由零宽范围检查拒绝。
            // 不需要内部 RangeMarkerEx 的 setGreedy*：同文档任意编辑都会立即
            // 使结果过期，marker 此后只负责把原完整方法复定位到新的偏移。
            methodRange = document.createRangeMarker(
                    context.getMethodStart(), context.getMethodEnd());
        }
        document.addDocumentListener(this);
        listening = true;
    }

    /** 卸载，停止监听（评审重置或新评审开始时调用）。可重复调用。 */
    public void detach() {
        armed = false;
        stopWatchingProjectCalls();
        stopListening();
        if (methodRange != null) {
            methodRange.dispose();
            methodRange = null;
        }
    }

    @Override
    public void beforeDocumentChange(@NotNull DocumentEvent event) {
        if (!armed) {
            // 验证结论只对应生成它的那个文档版本。保留 VERIFYING，由验证自己的
            // listener 取消并给出错误；这里只清除已完成/失败的旧结论。
            session.invalidateCompletedVerifications();
            return;
        }
        expire();
    }

    void watchProjectCalls(ProjectCalls calls) {
        if (calls.sources().isEmpty()) return;
        projectCalls = calls;
        projectCallListeners = Disposer.newDisposable("CodeMentor project call listeners");
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override
            public void beforeDocumentChange(@NotNull DocumentEvent event) {
                if (calls.contains(FileDocumentManager.getInstance().getFile(event.getDocument()))) {
                    expire();
                }
            }
        }, projectCallListeners);
        ApplicationManager.getApplication().getMessageBus().connect(projectCallListeners)
                .subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
                    @Override
                    public void after(@NotNull List<? extends VFileEvent> events) {
                        if (!calls.isCurrent()) {
                            ApplicationManager.getApplication().invokeLater(() -> expire());
                        }
                    }
                });
    }

    boolean checkProjectCalls() {
        if (projectCalls.isCurrent()) return true;
        expire();
        return false;
    }

    private void expire() {
        if (!armed) return;
        armed = false;
        onExpired.run();
        ApplicationManager.getApplication().invokeLater(() -> {
            stopWatchingProjectCalls();
            session.expire(requestId);
            // listener 保留到新评审/reset：marker 继续复定位原方法，后续编辑会
            // 失效基于上一文档版本的验证结论。
        });
    }

    private void stopWatchingProjectCalls() {
        if (projectCallListeners != null) {
            Disposer.dispose(projectCallListeners);
            projectCallListeners = null;
        }
    }

    /** Captures the current location of the exact full-method target on the EDT. */
    ContextTarget captureCurrentTarget(Editor editor, ReviewContext expectedContext) {
        if (editor == null
                || editor.isDisposed()
                || editor.getDocument() != document
                || expectedContext != context
                || methodRange == null
                || !methodRange.isValid()) {
            return null;
        }
        int start = methodRange.getStartOffset();
        int end = methodRange.getEndOffset();
        if (end <= start || document.getTextLength() == 0) return null;
        // The declaration start belongs to the outer target method. A midpoint can land in an
        // anonymous/local-class method nested inside the target and silently switch targets.
        int offset = Math.max(0, Math.min(start, document.getTextLength() - 1));
        return ContextTarget.capture(editor, offset);
    }

    boolean matchesCurrentTarget(
            ReviewContext expectedContext, ReviewContext currentContext) {
        if (expectedContext != context
                || currentContext == null
                || methodRange == null
                || !methodRange.isValid()
                || methodRange.getEndOffset() <= methodRange.getStartOffset()
                || !context.getFilePath().equals(currentContext.getFilePath())) {
            return false;
        }
        return currentContext.getMethodStart() <= methodRange.getStartOffset()
                && currentContext.getMethodEnd() >= methodRange.getEndOffset();
    }

    private void stopListening() {
        if (!listening) return;
        document.removeDocumentListener(this);
        listening = false;
    }

    // DocumentListener 的其他方法（documentChanged / bulkUpdate*）都是 default 方法，
    // 本场景用不到，无需重写。
}
