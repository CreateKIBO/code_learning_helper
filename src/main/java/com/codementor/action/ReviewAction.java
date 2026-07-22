package com.codementor.action;

import com.codementor.context.PsiContextExtractor;
import com.codementor.review.ExpiryWatcher;
import com.codementor.review.FakeReviewService;
import com.codementor.review.ReviewContext;
import com.codementor.review.ReviewService;
import com.codementor.review.ReviewSession;
import com.codementor.ui.MentorToolWindowFactory;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 评审动作 —— 按 Alt+M 或右键菜单"请导师评审"触发。
 *
 * <p>核心职责（对应方案第 5.1 节 + 第 16 节第 3 步）：
 * <ol>
 *   <li>在读锁内采集上下文（PSI 访问必须读锁，方案第 12 节线程铁律）。</li>
 *   <li>启动评审会话（ReviewSession 进入 REQUESTING）。</li>
 *   <li>异步调用 ReviewService（阶段 0 是假服务，真实模型在阶段 1 接入）。</li>
 *   <li>结果回到 EDT 更新会话（UI 线程更新，方案第 12 节）。</li>
 * </ol>
 *
 * <p>实现 {@link DumbAware}：允许在"建立索引中"（dumb mode）也能触发。
 * 否则用户打开大项目时索引要建几分钟，期间按 Alt+M 会被平台拦截，
 * 弹出 "is not available during indexing"。阶段 0 只取方法文本（PsiMethod.getText），
 * 属于轻量 PSI 读取，不依赖索引，在 dumb mode 下安全。
 */
public class ReviewAction extends AnAction implements DumbAware {

    /** 阶段 0 用假服务；阶段 1 换成真实供应商适配器即可，这里改一行。 */
    private final ReviewService reviewService = new FakeReviewService();

    /**
     * 当前的过期检测器。评审成功后挂载，监听文档变化；
     * 新评审开始前卸载，避免多个 watcher 叠加。
     */
    private ExpiryWatcher currentWatcher;

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        if (project == null || editor == null || psiFile == null) {
            return;
        }

        // 1) 打开教练侧栏（如果没打开）
        ensureToolWindowVisible(project);

        // 2) 卸载上一次的过期检测器（避免新旧 watcher 叠加）
        detachCurrentWatcher();

        // 3) 重置当前会话（若旧请求还在进行，这里简化为直接覆盖；阶段 1 会加取消逻辑）
        ReviewSession session = ReviewSession.getInstance();

        // 4) 在读锁内采集上下文。ReadAction.nonBlocking() 保证不阻塞 EDT。
        ReviewContext context = ReadAction.compute(() ->
                PsiContextExtractor.extract(project, editor, psiFile));

        if (context == null) {
            // 没选中且光标不在方法内，或不是 Java 文件
            session.reset();
            Messages.showInfoMessage(project,
                    "请选中一段代码，或把光标放在某个方法内部后再评审。",
                    "CodeMentor");
            return;
        }

        // 5) 进入请求中状态（此时 UI 侧栏会显示"导师评审中..."）
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        session.startRequest(context, requestId);

        // 记住文档，评审成功后要挂载过期检测器
        Document document = editor.getDocument();

        // 6) 异步评审，结果回到 EDT 更新会话
        reviewService.review(context, requestId, new ReviewService.Callback() {
            @Override
            public void onSuccess(java.util.List<com.codementor.review.Suggestion> suggestions) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    session.succeed(suggestions);
                    // 评审成功 → 挂载过期检测器，监听用户后续编辑
                    attachWatcher(document, context, session);
                });
            }

            @Override
            public void onFailure(String errorMessage) {
                ApplicationManager.getApplication().invokeLater(() -> session.fail(errorMessage));
            }
        });
    }

    /** 挂载过期检测器（评审成功后调用）。 */
    private void attachWatcher(Document document, ReviewContext context, ReviewSession session) {
        currentWatcher = new ExpiryWatcher(document, context, session);
        currentWatcher.attach();
    }

    /** 卸载当前的过期检测器（新评审开始或会话重置时调用）。 */
    private void detachCurrentWatcher() {
        if (currentWatcher != null) {
            currentWatcher.detach();
            currentWatcher = null;
        }
    }

    /**
     * Action 启用判断：只在有打开编辑器、且当前文件以 .java 结尾时启用。
     *
     * <p>注意：这里故意"不"调用 PSI（如 PsiContextExtractor.isReviewable），
     * 因为 PSI 访问在 dumb mode 下可能触发索引读取，导致 ActionUpdater 卡 EDT
     * （日志里出现过 "ActionUpdater ... grab EDT 67997ms"）。
     * 改成只看文件名后缀，零索引依赖，BGT 线程上跑绝对安全。
     *
     * <p>真正的 Java 文件判断放到 actionPerformed 里做（那里已经在读锁内）。
     */
    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        String name = psiFile != null ? psiFile.getName() : "";
        boolean enabled = editor != null && name.endsWith(".java");
        e.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /** 确保教练侧栏可见。 */
    private void ensureToolWindowVisible(Project project) {
        ToolWindowManager twm = ToolWindowManager.getInstance(project);
        ToolWindow toolWindow = twm.getToolWindow(MentorToolWindowFactory.TOOL_WINDOW_ID);
        if (toolWindow != null) {
            // ToolWindow.activate 必须在 EDT；本方法在 actionPerformed（EDT）中调用
            ApplicationManager.getApplication().invokeLater(() -> {
                if (toolWindow.isAvailable()) {
                    toolWindow.activate(null);
                }
            });
        }
    }
}
