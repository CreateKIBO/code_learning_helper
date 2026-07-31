package com.codementor.ui;

import com.codementor.history.LearningHistoryService;
import com.codementor.history.KnowledgeReminder;
import com.codementor.history.LearningSummary;
import com.codementor.practice.Exercise;
import com.codementor.practice.ExerciseCoordinator;
import com.codementor.practice.ExerciseResult;
import com.codementor.privacy.SendPreviewFormatter;
import com.codementor.review.ReviewSession;
import com.codementor.review.ReviewMode;
import com.codementor.review.ReviewStatus;
import com.codementor.review.Suggestion;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;

/**
 * 教练侧栏 Tool Window —— 评审结果展示区（方案第 5.2 节）。
 *
 * <p>架构约束（方案第 7 节）：UI 不自存状态，全部从 {@link ReviewSession} 读取。
 * 本类订阅会话变化，状态一变就 {@link #render()} 重新绘制。
 *
 * <p>按状态分别渲染：
 * <ul>
 *   <li>IDLE：居中图标 + 引导文案（空状态）</li>
 *   <li>REQUESTING：评审中提示</li>
 *   <li>SUCCESS：建议卡片列表 + 顶部「重新评审」按钮</li>
 *   <li>EXPIRED：橙色醒目横幅 + 「重新评审」按钮</li>
 *   <li>FAILED：红色错误信息 + 「重新评审」按钮</li>
 * </ul>
 */
public class MentorToolWindowPanel extends SimpleToolWindowPanel implements Disposable {

    /** 重新评审按钮触发的 Action ID（与 plugin.xml 一致）。 */
    private static final String REVIEW_ACTION_ID = "CodeMentor.ReviewCurrent";
    private static final String EXPLAIN_ACTION_ID = "CodeMentor.ExplainCurrent";

    // ---- 配色常量（亮色/暗色双值，跟随 IDE 主题）----
    /** 成功状态文字色（绿）。 */
    private static final Color COLOR_SUCCESS = new JBColor(new Color(0x2E, 0x7D, 0x32), new Color(0x66, 0xBB, 0x6A));
    /** 过期横幅背景（浅橙）。 */
    private static final Color COLOR_EXPIRY_BG = new JBColor(new Color(0xFF, 0xF3, 0xE0), new Color(0x4E, 0x3A, 0x1A));
    /** 过期横幅边框（橙）。 */
    private static final Color COLOR_EXPIRY_BORDER = new JBColor(new Color(0xFF, 0xB7, 0x4D), new Color(0x8A, 0x5A, 0x10));
    /** 过期横幅标题（深橙）。 */
    private static final Color COLOR_EXPIRY_TITLE = new JBColor(new Color(0xE6, 0x51, 0x00), new Color(0xFF, 0xB7, 0x4D));

    private final Project project;
    private final JBLabel headerLabel = new JBLabel();
    private final JBLabel statusLabel = new JBLabel();
    private final JButton reReviewButton = new JButton("重新评审");
    private final JButton exportButton = new JButton("导出复习");
    private final JButton exerciseButton = new JButton("生成小练习");
    private final JButton previewButton = new JButton("发送预览");
    private final JButton summaryButton = new JButton("学习统计");
    private final JPanel cardsPanel = new JPanel(new BorderLayout());
    private final JPanel contentPanel = new JPanel(new BorderLayout());
    /** 会话监听器，存成字段以便 dispose 时能退订同一个实例。 */
    private final ReviewSession.Listener sessionListener = this::onSessionChanged;

    public MentorToolWindowPanel(@NotNull Project project) {
        // SimpleToolWindowPanel(boolean vertical)：true = 纵向布局（侧栏常用）
        super(true);
        this.project = project;
        setupUi();

        // 订阅会话变化（回调约定在 EDT，安全更新 UI）
        ReviewSession.getInstance(project).addListener(sessionListener);
        render();
    }

    /**
     * 面板销毁时退订会话，避免项目服务继续持有已经关闭的面板。
     * 由 MentorToolWindowFactory 通过 Content.setDisposer 挂到内容生命周期上。
     */
    @Override
    public void dispose() {
        ReviewSession.getInstance(project).removeListener(sessionListener);
    }

    private void setupUi() {
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD, 13f));
        headerLabel.setBorder(JBUI.Borders.empty(8, 10, 2, 10));

        statusLabel.setForeground(JBColor.GRAY);

        // 重新评审按钮：复用已注册的 ReviewAction，不重复造逻辑
        reReviewButton.setToolTipText("重新评审当前方法（相当于 Alt+M）");
        reReviewButton.addActionListener(e -> triggerCurrentModeAction());
        exportButton.setToolTipText("把本地学习记录导出为 Markdown 复习清单");
        exportButton.addActionListener(e -> exportLearningHistory());
        exerciseButton.setToolTipText("根据最近反复出现的知识点生成一道 10~20 分钟小练习");
        exerciseButton.addActionListener(e ->
                ExerciseCoordinator.getInstance(project).generate());
        previewButton.setToolTipText("查看当前会话发送给模型的脱敏上下文");
        previewButton.addActionListener(e -> showSendPreview());
        summaryButton.setToolTipText("从本地追加式学习事件计算学习成效");
        summaryButton.addActionListener(e -> showLearningSummary());

        // 头部三件套：目标 | 状态 | 按钮，纵向排列
        JPanel headerLine = new JPanel(new BorderLayout(8, 2));
        headerLine.setBorder(JBUI.Borders.empty(2, 10, 6, 10));
        headerLine.add(headerLabel, BorderLayout.NORTH);
        JPanel statusLine = new JPanel(new BorderLayout(8, 0));
        statusLine.setOpaque(false);
        statusLine.add(statusLabel, BorderLayout.CENTER);
        headerLine.add(statusLine, BorderLayout.CENTER);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(headerLine, BorderLayout.CENTER);
        JPanel topActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        topActions.add(previewButton);
        topActions.add(summaryButton);
        topActions.add(exerciseButton);
        topActions.add(exportButton);
        topActions.add(reReviewButton);
        topPanel.add(topActions, BorderLayout.EAST);
        // 按钮初始不显示，等评审成功/过期/失败时才出现
        reReviewButton.setVisible(false);

        contentPanel.add(topPanel, BorderLayout.NORTH);

        JBScrollPane scrollPane = new JBScrollPane(cardsPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(JBUI.Borders.empty());
        contentPanel.add(scrollPane, BorderLayout.CENTER);

        setContent(contentPanel);
    }

    /** 会话变化回调（约定在 EDT；防御性判断，防止未来调用方换线程后在错误线程更新 UI）。 */
    private void onSessionChanged(ReviewSession session) {
        if (SwingUtilities.isEventDispatchThread()) {
            render();
        } else {
            SwingUtilities.invokeLater(this::render);
        }
    }

    /** 根据当前会话状态重绘整个面板。 */
    private void render() {
        ReviewSession session = ReviewSession.getInstance(project);
        ReviewStatus status = session.getStatus();

        // 头部：评审目标
        String target = session.getContext() != null ? session.getContext().getTargetName() : "—";
        String role = session.getContext() != null
                ? session.getContext().getJavaWebContext().getRole().getLabel()
                : "—";
        ReviewMode mode = currentMode(session);
        String tags = session.getContext() == null
                || session.getContext().getTechnologyTags().isEmpty()
                ? "—"
                : String.join("、", session.getContext().getTechnologyTags());
        headerLabel.setText("<html><b>评审目标：</b>" + escape(target)
                + "<br><b>学习模式：</b>" + escape(mode.getLabel())
                + "<br><b>JavaWeb 角色：</b>" + escape(role)
                + "<br><b>技术标签：</b>" + escape(tags) + "</html>");

        // 状态行
        statusLabel.setText(
                "<html><b>状态：</b>" + statusText(status, mode) + "</html>");
        statusLabel.setForeground(statusColor(status));

        // 重新评审按钮：成功 / 过期 / 失败 时可见（这些状态用户最可能想重评）
        boolean showButton = status == ReviewStatus.SUCCESS
                || status == ReviewStatus.EXPIRED
                || status == ReviewStatus.FAILED;
        reReviewButton.setVisible(showButton);
        reReviewButton.setText(
                mode == ReviewMode.EXPLAIN ? "重新解释" : "重新评审");
        reReviewButton.setToolTipText(
                mode == ReviewMode.EXPLAIN
                        ? "重新解释当前方法（相当于 Alt+Shift+M）"
                        : "重新评审当前方法（相当于 Alt+M）");
        exerciseButton.setEnabled(
                session.getExerciseResult().getStatus()
                        != ExerciseResult.Status.GENERATING);
        previewButton.setEnabled(session.getContext() != null);

        // 卡片区：按状态分别渲染
        cardsPanel.removeAll();
        JComponent reviewContent;
        String position = BorderLayout.NORTH;
        if (status == ReviewStatus.SUCCESS && session.getSuggestions() != null) {
            reviewContent = buildSuccessList(session.getSuggestions());
        } else if (status == ReviewStatus.EXPIRED) {
            JPanel expiredContent = new JPanel();
            expiredContent.setLayout(new BoxLayout(expiredContent, BoxLayout.Y_AXIS));
            expiredContent.add(buildExpiryBanner());
            if (session.getSuggestions() != null) {
                expiredContent.add(buildSuccessList(session.getSuggestions()));
            }
            reviewContent = expiredContent;
        } else if (status == ReviewStatus.FAILED) {
            reviewContent = buildFailureHint(session.getErrorMessage());
        } else if (status == ReviewStatus.REQUESTING) {
            reviewContent = buildRequestingHint();
        } else {
            reviewContent = buildEmptyState();
            position = BorderLayout.CENTER;
        }
        ExerciseResult exerciseResult = session.getExerciseResult();
        if (exerciseResult.getStatus() == ExerciseResult.Status.IDLE) {
            cardsPanel.add(reviewContent, position);
        } else {
            JPanel stack = new JPanel();
            stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
            stack.add(buildExercisePanel(exerciseResult));
            stack.add(Box.createVerticalStrut(8));
            stack.add(reviewContent);
            cardsPanel.add(stack, BorderLayout.NORTH);
        }

        cardsPanel.revalidate();
        cardsPanel.repaint();
    }

    // ---------------- 各状态的渲染组件 ----------------

    /** 空状态——居中图标 + 主副标题 + 快捷键提示。 */
    private JComponent buildEmptyState() {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(new EmptyBorder(40, 20, 40, 20));

        JBLabel icon = new JBLabel(AllIcons.General.Balloon, SwingConstants.CENTER);
        icon.setAlignmentX(Component.CENTER_ALIGNMENT);
        icon.setIconTextGap(0);

        JBLabel title = new JBLabel("CodeMentor 教练");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        title.setBorder(new EmptyBorder(14, 0, 6, 0));

        JBLabel hint1 = new JBLabel("Alt+M 评审写法 · Alt+Shift+M 解释代码");
        hint1.setForeground(JBColor.GRAY);
        hint1.setAlignmentX(Component.CENTER_ALIGNMENT);

        JBLabel hint2 = new JBLabel("也可以选中代码后从右键菜单选择学习模式");
        hint2.setForeground(JBColor.GRAY);
        hint2.setFont(hint2.getFont().deriveFont(11f));
        hint2.setAlignmentX(Component.CENTER_ALIGNMENT);
        hint2.setBorder(new EmptyBorder(4, 0, 0, 0));

        JBLabel hint3 = new JBLabel("首次使用请先在 Settings → Tools → CodeMentor 配置 API Key");
        hint3.setForeground(new JBColor(new Color(0xE6, 0x51, 0x00), new Color(0xFF, 0xB7, 0x4D)));
        hint3.setFont(hint3.getFont().deriveFont(11f));
        hint3.setAlignmentX(Component.CENTER_ALIGNMENT);
        hint3.setBorder(new EmptyBorder(8, 0, 0, 0));

        box.add(icon);
        box.add(title);
        box.add(hint1);
        box.add(hint2);
        box.add(hint3);
        return box;
    }

    /** 请求中：简单提示。 */
    private JComponent buildRequestingHint() {
        ReviewMode mode = currentMode(ReviewSession.getInstance(project));
        JBLabel l = new JBLabel(
                mode == ReviewMode.EXPLAIN ? "导师解释中…" : "导师评审中…");
        l.setForeground(JBColor.BLUE);
        l.setBorder(new EmptyBorder(20, 14, 20, 14));
        return l;
    }

    /** 成功：建议列表。 */
    private JComponent buildSuccessList(List<Suggestion> suggestions) {
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(JBUI.Borders.empty(4, 10, 10, 10));
        List<KnowledgeReminder> reminders =
                ReviewSession.getInstance(project).getKnowledgeReminders();
        if (!reminders.isEmpty()) {
            list.add(buildReminderBanner(reminders));
            list.add(Box.createVerticalStrut(8));
        }
        boolean explainMode = currentMode(ReviewSession.getInstance(project))
                == ReviewMode.EXPLAIN;
        JBLabel countLabel = new JBLabel("共 " + suggestions.size()
                + (explainMode ? " 个理解要点：" : " 条建议："));
        countLabel.setBorder(JBUI.Borders.empty(0, 0, 6, 0));
        list.add(countLabel);
        for (Suggestion s : suggestions) {
            list.add(new SuggestionCardPanel(project, s));
            list.add(Box.createVerticalStrut(6));
        }
        return list;
    }

    private JComponent buildReminderBanner(List<KnowledgeReminder> reminders) {
        StringBuilder text = new StringBuilder("<html><b>复习提醒：</b>");
        for (int i = 0; i < reminders.size(); i++) {
            KnowledgeReminder reminder = reminders.get(i);
            if (i > 0) text.append("；");
            text.append(escape(reminder.getKnowledgePoint()))
                    .append("已出现 ")
                    .append(reminder.getOccurrences())
                    .append(" 次");
        }
        text.append("</html>");
        JBLabel label = new JBLabel(text.toString(), AllIcons.General.Information, SwingConstants.LEFT);
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                JBUI.Borders.empty(8)));
        return label;
    }

    private JComponent buildExercisePanel(ExerciseResult result) {
        JPanel panel = new JPanel(new BorderLayout(8, 4));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                JBUI.Borders.empty(10)));
        if (result.getStatus() == ExerciseResult.Status.GENERATING) {
            panel.add(new JBLabel("正在根据最近的薄弱知识点生成小练习…"),
                    BorderLayout.NORTH);
            return wrapExercisePanel(panel);
        }
        if (result.getStatus() == ExerciseResult.Status.FAILED) {
            JBLabel failure = new JBLabel("<html><b>小练习生成失败：</b>"
                    + escape(result.getErrorMessage()) + "</html>");
            failure.setForeground(JBColor.RED);
            panel.add(failure, BorderLayout.NORTH);
            return wrapExercisePanel(panel);
        }

        Exercise exercise = result.getExercise();
        if (exercise == null) {
            return wrapExercisePanel(panel);
        }
        StringBuilder html = new StringBuilder("<html><b>小练习：")
                .append(escape(exercise.getTitle()))
                .append("</b><br><b>知识点：</b>")
                .append(escape(exercise.getKnowledgePoint()))
                .append("<br><br>")
                .append(escape(exercise.getTask()).replace("\n", "<br>"));
        appendHtmlItems(html, "限制", exercise.getConstraints());
        appendHtmlItems(html, "提示", exercise.getHints());
        appendHtmlItems(html, "完成标准", exercise.getAcceptanceCriteria());
        if (!exercise.getStarterCode().isBlank()) {
            html.append("<br><b>起始代码：</b><pre>")
                    .append(escape(exercise.getStarterCode()))
                    .append("</pre>");
        }
        html.append("</html>");
        panel.add(new JBLabel(html.toString()), BorderLayout.NORTH);
        return wrapExercisePanel(panel);
    }

    private static void appendHtmlItems(
            StringBuilder html, String title, List<String> items) {
        if (items.isEmpty()) return;
        html.append("<br><b>").append(title).append("：</b><ul>");
        for (String item : items) {
            html.append("<li>").append(escape(item)).append("</li>");
        }
        html.append("</ul>");
    }

    private static JComponent wrapExercisePanel(JComponent panel) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(JBUI.Borders.empty(8, 10, 0, 10));
        wrapper.add(panel, BorderLayout.NORTH);
        return wrapper;
    }

    /** 已过期橙色横幅：提醒当前学习结果已不再对应最新代码。 */
    private JComponent buildExpiryBanner() {
        JPanel banner = new JPanel(new BorderLayout(8, 4));
        banner.setBackground(COLOR_EXPIRY_BG);
        banner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(COLOR_EXPIRY_BORDER, 1),
                JBUI.Borders.empty(10, 12)));

        JBLabel title = new JBLabel("⚠ 代码已修改，当前结果可能过期");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setForeground(COLOR_EXPIRY_TITLE);

        JBLabel desc = new JBLabel(
                "<html>分析过的代码已经变化，旧内容可能对不上当前位置。"
                        + "<br>点击右上角重新获取最新结果。</html>");
        desc.setForeground(JBColor.GRAY);
        desc.setFont(desc.getFont().deriveFont(11f));

        banner.add(title, BorderLayout.NORTH);
        banner.add(desc, BorderLayout.CENTER);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBorder(JBUI.Borders.empty(8, 10));
        wrap.add(banner, BorderLayout.NORTH);
        return wrap;
    }

    /** 失败：红色错误信息。 */
    private JComponent buildFailureHint(String message) {
        JPanel box = new JPanel(new BorderLayout(8, 4));
        box.setBorder(JBUI.Borders.empty(12, 12));

        JBLabel title = new JBLabel("✗ 导师请求失败");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setForeground(JBColor.RED);

        JBLabel msg = new JBLabel("<html>" + escape(message != null ? message : "未知错误") + "</html>");
        msg.setForeground(JBColor.GRAY);

        JBLabel hint = new JBLabel("点击右上角按钮重试。");
        hint.setForeground(JBColor.GRAY);
        hint.setFont(hint.getFont().deriveFont(11f));

        JPanel text = new JPanel(new BorderLayout(0, 2));
        text.setOpaque(false);
        text.add(title, BorderLayout.NORTH);
        text.add(msg, BorderLayout.CENTER);
        text.add(hint, BorderLayout.SOUTH);
        box.add(text, BorderLayout.NORTH);
        return box;
    }

    /**
     * 编程式触发已注册的 ReviewAction，复用其完整逻辑。
     *
     * <p>不使用 ActionManager.tryToExecute（它要求 InputEvent，适合鼠标触发场景），
     * 而是实时取当前活跃编辑器和 PsiFile，构造一个完整 DataContext，
     * 再调用 action.actionPerformed——和用户按 Alt+M 走完全一样的代码路径。
     *
     * <p>dataId 必须用 CommonDataKeys 各键的 getName()，不能猜字符串常量。
     */
    private void triggerCurrentModeAction() {
        ReviewMode mode = currentMode(ReviewSession.getInstance(project));
        String actionId = mode == ReviewMode.EXPLAIN
                ? EXPLAIN_ACTION_ID
                : REVIEW_ACTION_ID;
        AnAction action = ActionManager.getInstance().getAction(actionId);
        if (action == null) {
            return;
        }

        FileEditorManager fileEditorManager = FileEditorManager.getInstance(project);
        PsiDocumentManager psiDocumentManager = PsiDocumentManager.getInstance(project);

        DataContext dataContext = dataId -> {
            if (dataId.equals(CommonDataKeys.PROJECT.getName())) {
                return project;
            }
            if (dataId.equals(CommonDataKeys.EDITOR.getName())) {
                return fileEditorManager.getSelectedTextEditor();
            }
            if (dataId.equals(CommonDataKeys.PSI_FILE.getName())) {
                var editor = fileEditorManager.getSelectedTextEditor();
                return editor != null ? psiDocumentManager.getPsiFile(editor.getDocument()) : null;
            }
            return null;
        };

        AnActionEvent event = AnActionEvent.createFromDataContext("CodeMentor.Toolbar", null, dataContext);
        action.actionPerformed(event);
    }

    private void exportLearningHistory() {
        FileSaverDescriptor descriptor = new FileSaverDescriptor(
                "导出 CodeMentor 复习清单",
                "选择 Markdown 文件的保存位置",
                "md");
        VirtualFileWrapper target = FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, project)
                .save("CodeMentor-复习清单.md");
        if (target == null) {
            return;
        }
        exportButton.setEnabled(false);
        Path path = target.getFile().toPath();
        LearningHistoryService.getInstance().exportMarkdown(path)
                .whenComplete((savedPath, error) -> SwingUtilities.invokeLater(() -> {
                    exportButton.setEnabled(true);
                    if (project.isDisposed()) return;
                    if (error == null) {
                        Messages.showInfoMessage(
                                project,
                                "复习清单已导出到：\n" + savedPath,
                                "CodeMentor");
                    } else {
                        Messages.showErrorDialog(
                                project,
                                error.getCause() != null
                                        ? error.getCause().getMessage()
                                        : error.getMessage(),
                                "CodeMentor");
                    }
                }));
    }

    private void showSendPreview() {
        ReviewSession session = ReviewSession.getInstance(project);
        if (session.getContext() == null) return;
        String preview = SendPreviewFormatter.format(session.getContext(), 6_000);
        Messages.showInfoMessage(
                project,
                "<html><body style='width:560px'>"
                        + "以下是当前会话的脱敏发送内容；若代码已修改，它代表上次请求版本。"
                        + "<br><br><pre>"
                        + escape(preview)
                        + "</pre></body></html>",
                "CodeMentor 发送预览");
    }

    private void showLearningSummary() {
        summaryButton.setEnabled(false);
        LearningHistoryService.getInstance().loadSummary()
                .whenComplete((summary, error) -> SwingUtilities.invokeLater(() -> {
                    summaryButton.setEnabled(true);
                    if (project.isDisposed()) return;
                    if (error != null) {
                        Messages.showErrorDialog(
                                project,
                                error.getCause() == null
                                        ? error.getMessage()
                                        : error.getCause().getMessage(),
                                "CodeMentor 学习统计");
                        return;
                    }
                    Messages.showInfoMessage(
                            project,
                            learningSummaryHtml(summary),
                            "CodeMentor 学习统计");
                }));
    }

    private static String learningSummaryHtml(LearningSummary summary) {
        StringBuilder html = new StringBuilder("<html><body style='width:440px'>")
                .append("<b>本地学习摘要</b><br><br>")
                .append("累计建议：").append(summary.getReviewedSuggestions()).append("<br>")
                .append("完成修改验证：").append(summary.getCompletedVerifications())
                .append("，解决率：").append(summary.getResolutionRatePercent()).append("%<br>")
                .append("查看讲解：").append(summary.getExplanationViews())
                .append("，查看率：").append(summary.getExplanationViewRatePercent()).append("%<br>")
                .append("反馈：有帮助 ").append(summary.getHelpfulFeedback())
                .append(" / 没帮助 ").append(summary.getNotHelpfulFeedback())
                .append(" / 误报 ").append(summary.getFalsePositiveFeedback());
        if (!summary.getWeakKnowledgePoints().isEmpty()) {
            html.append("<br><br><b>当前薄弱知识点：</b><ul>");
            for (KnowledgeReminder reminder : summary.getWeakKnowledgePoints()) {
                html.append("<li>")
                        .append(escape(reminder.getKnowledgePoint()))
                        .append("（待巩固 ")
                        .append(reminder.getOccurrences())
                        .append(" 次）</li>");
            }
            html.append("</ul>");
        }
        return html.append(
                "<br><small>统计仅来自本机事件，不包含完整源代码。</small></body></html>")
                .toString();
    }

    // ---------- 小工具 ----------

    private static String statusText(ReviewStatus s, ReviewMode mode) {
        switch (s) {
            case IDLE: return "空闲";
            case REQUESTING:
                return mode == ReviewMode.EXPLAIN ? "导师解释中…" : "导师评审中…";
            case SUCCESS:
                return mode == ReviewMode.EXPLAIN ? "解释完成" : "评审完成";
            case FAILED: return "失败";
            case EXPIRED: return "已过期（代码已修改）";
            default: return s.toString();
        }
    }

    private static ReviewMode currentMode(ReviewSession session) {
        return session.getContext() == null
                ? ReviewMode.REVIEW
                : session.getContext().getReviewMode();
    }

    private static Color statusColor(ReviewStatus s) {
        switch (s) {
            case REQUESTING: return JBColor.BLUE;
            case SUCCESS: return COLOR_SUCCESS;
            case FAILED: return JBColor.RED;
            case EXPIRED: return JBColor.ORANGE;
            default: return JBColor.GRAY;
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
