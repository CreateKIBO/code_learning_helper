package com.codementor.ui;

import com.codementor.history.LearningHistoryService;
import com.codementor.history.KnowledgeReminder;
import com.codementor.history.LearningSummary;
import com.codementor.practice.Exercise;
import com.codementor.practice.ExerciseCoordinator;
import com.codementor.practice.ExerciseResult;
import com.codementor.model.ModelRequestConfig;
import com.codementor.review.ReviewSession;
import com.codementor.review.ReviewCoordinator;
import com.codementor.review.ReviewMode;
import com.codementor.review.ReviewStatus;
import com.codementor.review.Suggestion;
import com.codementor.settings.CodeMentorProjectConsent;
import com.codementor.settings.CodeMentorConfigurable;
import com.codementor.settings.CodeMentorSettings;
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
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

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
    private final JButton exerciseButton = new JButton("生成小练习");
    private final JButton moreButton = new JButton("更多…");
    private final JMenuItem previewItem = new JMenuItem("查看发送预览");
    private final JMenuItem summaryItem = new JMenuItem("查看学习统计");
    private final JMenuItem exportItem = new JMenuItem("导出复习清单…");
    private final JMenuItem privacyItem = new JMenuItem("隐私与数据…");
    private final JMenuItem settingsItem = new JMenuItem("打开 CodeMentor 设置…");
    private final JPanel cardsPanel = new JPanel(new BorderLayout());
    private final JPanel contentPanel = new JPanel(new BorderLayout());
    private JBScrollPane scrollPane;
    private String renderedReviewId = "";
    /** 会话监听器，存成字段以便 dispose 时能退订同一个实例。 */
    private final ReviewSession.Listener sessionListener = this::onSessionChanged;
    private CompletableFuture<?> exportFuture;
    private CompletableFuture<?> summaryFuture;
    private CompletableFuture<?> clearHistoryFuture;
    private volatile boolean disposed;

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
        disposed = true;
        cancel(exportFuture);
        cancel(summaryFuture);
        cancel(clearHistoryFuture);
        ReviewSession.getInstance(project).removeListener(sessionListener);
    }

    private void setupUi() {
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD, 13f));
        headerLabel.setBorder(JBUI.Borders.empty(8, 10, 2, 10));

        statusLabel.setForeground(JBColor.GRAY);

        // 重新评审按钮：复用已注册的 ReviewAction，不重复造逻辑
        reReviewButton.setToolTipText("重新评审当前方法（相当于 Alt+M）");
        reReviewButton.addActionListener(e -> triggerCurrentModeAction());
        exportItem.setToolTipText("把本地学习记录导出为 Markdown 复习清单");
        exportItem.addActionListener(e -> exportLearningHistory());
        exerciseButton.setToolTipText(
                "优先复习反复出现的知识点；没有历史薄弱点时练习当前有效建议");
        exerciseButton.addActionListener(e ->
                ExerciseCoordinator.getInstance(project).generate());
        previewItem.setToolTipText("查看当前会话发送给模型的脱敏上下文");
        previewItem.addActionListener(e -> showSendPreview());
        summaryItem.setToolTipText("从本地追加式学习事件计算学习成效");
        summaryItem.addActionListener(e -> showLearningSummary());
        privacyItem.setToolTipText("撤销发送授权或删除本机学习记录");
        privacyItem.addActionListener(e -> showPrivacyAndData());
        settingsItem.addActionListener(e -> openSettings());
        moreButton.setToolTipText("发送预览、统计、导出、隐私和设置");
        moreButton.addActionListener(e -> showMoreMenu());

        // 头部依次显示目标、状态和分层动作，适配窄侧栏。
        JPanel headerLine = new JPanel(new BorderLayout(8, 2));
        headerLine.setBorder(JBUI.Borders.empty(2, 10, 6, 10));
        headerLine.add(headerLabel, BorderLayout.NORTH);
        JPanel statusLine = new JPanel(new BorderLayout(8, 0));
        statusLine.setOpaque(false);
        statusLine.add(statusLabel, BorderLayout.CENTER);
        headerLine.add(statusLine, BorderLayout.CENTER);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(headerLine, BorderLayout.CENTER);
        JPanel topActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        topActions.add(exerciseButton);
        topActions.add(reReviewButton);
        topActions.add(moreButton);
        topPanel.add(topActions, BorderLayout.SOUTH);
        // 按钮初始不显示，等评审成功/过期/失败时才出现
        reReviewButton.setVisible(false);

        contentPanel.add(topPanel, BorderLayout.NORTH);

        scrollPane = new JBScrollPane(cardsPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(JBUI.Borders.empty());
        contentPanel.add(scrollPane, BorderLayout.CENTER);

        setContent(contentPanel);
    }

    /** 会话变化回调（约定在 EDT；防御性判断，防止未来调用方换线程后在错误线程更新 UI）。 */
    private void onSessionChanged(ReviewSession session) {
        if (disposed || project.isDisposed()) return;
        if (SwingUtilities.isEventDispatchThread()) {
            render();
        } else {
            SwingUtilities.invokeLater(this::render);
        }
    }

    /** 根据当前会话状态重绘整个面板。 */
    private void render() {
        if (disposed || project.isDisposed()) return;
        ReviewSession session = ReviewSession.getInstance(project);
        ReviewStatus status = session.getStatus();
        String currentReviewId = session.getLearningReviewId();
        boolean preserveScroll = !currentReviewId.isBlank()
                && currentReviewId.equals(renderedReviewId);
        int previousScroll = scrollPane == null
                ? 0
                : scrollPane.getVerticalScrollBar().getValue();

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
        headerLabel.setText("<html><b>" + escape(target) + "</b><br>"
                + escape(mode.getLabel()) + " · " + escape(role)
                + ("—".equals(tags) ? "" : " · " + escape(tags))
                + "</html>");

        // 状态行
        statusLabel.setText(
                "<html><b>状态：</b>" + statusText(status, mode) + "</html>");
        statusLabel.setForeground(statusColor(status));

        // 重新评审按钮：成功 / 过期 / 失败时可见。
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
        previewItem.setEnabled(session.getContext() != null);

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
        renderedReviewId = currentReviewId;
        if (scrollPane != null) {
            int targetScroll = preserveScroll ? previousScroll : 0;
            SwingUtilities.invokeLater(() -> {
                if (disposed || project.isDisposed()) return;
                JScrollBar bar = scrollPane.getVerticalScrollBar();
                bar.setValue(Math.min(targetScroll, bar.getMaximum()));
            });
        }
    }

    // ---------------- 各状态的渲染组件 ----------------

    /** Empty state with direct first-use actions instead of a dead-end hint. */
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

        JBLabel hint1 = new JBLabel("把光标放在方法内，选择你现在的学习目标");
        hint1.setForeground(JBColor.GRAY);
        hint1.setAlignmentX(Component.CENTER_ALIGNMENT);

        JBLabel hint2 = new JBLabel("模型只给证据、提示和自测，不会自动修改源码");
        hint2.setForeground(JBColor.GRAY);
        hint2.setFont(hint2.getFont().deriveFont(11f));
        hint2.setAlignmentX(Component.CENTER_ALIGNMENT);
        hint2.setBorder(new EmptyBorder(4, 0, 0, 0));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 4));
        actions.setOpaque(false);
        JButton review = new JButton("评审当前代码");
        review.addActionListener(event -> triggerAction(REVIEW_ACTION_ID));
        JButton explain = new JButton("解释当前代码");
        explain.addActionListener(event -> triggerAction(EXPLAIN_ACTION_ID));
        JButton settings = new JButton("配置模型…");
        settings.addActionListener(event -> openSettings());
        actions.add(review);
        actions.add(explain);
        actions.add(settings);

        JBLabel shortcuts = new JBLabel("快捷键：Alt+M 评审 · Alt+Shift+M 解释");
        shortcuts.setForeground(JBColor.GRAY);
        shortcuts.setFont(shortcuts.getFont().deriveFont(11f));
        shortcuts.setAlignmentX(Component.CENTER_ALIGNMENT);
        shortcuts.setBorder(new EmptyBorder(6, 0, 0, 0));

        box.add(icon);
        box.add(title);
        box.add(hint1);
        box.add(hint2);
        box.add(Box.createVerticalStrut(10));
        box.add(actions);
        box.add(shortcuts);
        return box;
    }

    /** Requesting state with local progress context and explicit cancellation. */
    private JComponent buildRequestingHint() {
        ReviewMode mode = currentMode(ReviewSession.getInstance(project));
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(18, 14, 18, 14));
        JBLabel title = new JBLabel(
                mode == ReviewMode.EXPLAIN ? "导师正在组织解释…" : "导师正在分析代码…",
                AllIcons.General.Information,
                SwingConstants.LEFT);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setForeground(JBColor.BLUE);
        JBLabel detail = new JBLabel("正在等待模型并校验结构化结果，通常需要几秒钟。");
        detail.setForeground(JBColor.GRAY);
        JButton cancel = new JButton("取消本次请求");
        cancel.setToolTipText("尽力停止本地请求；无法撤回服务端已经接收的数据");
        cancel.addActionListener(event -> ReviewCoordinator.getInstance(project).cancelAll());
        panel.add(title, BorderLayout.NORTH);
        panel.add(detail, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actions.add(cancel);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    /** 成功：建议列表。 */
    private JComponent buildSuccessList(List<Suggestion> suggestions) {
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(JBUI.Borders.empty(4, 10, 10, 10));
        ReviewSession session = ReviewSession.getInstance(project);
        boolean explainMode = currentMode(session) == ReviewMode.EXPLAIN;
        list.add(buildSummaryBanner(session.getSummary(), suggestions.isEmpty(), explainMode));
        list.add(Box.createVerticalStrut(8));

        if (suggestions.isEmpty()) {
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            actions.setOpaque(false);
            JButton explain = new JButton("改为解释这段代码");
            explain.setVisible(!explainMode);
            explain.addActionListener(event -> triggerAction(EXPLAIN_ACTION_ID));
            JButton exercise = new JButton("练习一个薄弱知识点");
            exercise.addActionListener(event ->
                    ExerciseCoordinator.getInstance(project).generate());
            actions.add(explain);
            actions.add(exercise);
            list.add(actions);
            return list;
        }

        List<KnowledgeReminder> reminders = session.getKnowledgeReminders();
        if (!reminders.isEmpty()) {
            list.add(buildReminderBanner(reminders));
            list.add(Box.createVerticalStrut(8));
        }
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

    private JComponent buildSummaryBanner(
            String summary, boolean noSuggestions, boolean explainMode) {
        JPanel panel = new JPanel(new BorderLayout(8, 5));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                JBUI.Borders.empty(10)));
        String title = noSuggestions && !explainMode
                ? "未发现需要优先处理的问题"
                : explainMode ? "整体理解" : "本次学习重点";
        JBLabel heading = new JBLabel(
                title,
                noSuggestions ? AllIcons.General.InspectionsOK : AllIcons.General.Information,
                SwingConstants.LEFT);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        panel.add(heading, BorderLayout.NORTH);

        JBTextArea text = new JBTextArea(summary == null || summary.isBlank()
                ? (noSuggestions
                        ? "当前可见上下文中没有高优先级建议。"
                        : "导师已完成当前可见上下文的分析。")
                : summary);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setBorder(JBUI.Borders.empty());
        text.setFocusable(false);
        panel.add(text, BorderLayout.CENTER);

        JBLabel limitation = new JBLabel("基于静态可见上下文；未编译、运行代码或测试。");
        limitation.setForeground(JBColor.GRAY);
        limitation.setFont(limitation.getFont().deriveFont(11f));
        panel.add(limitation, BorderLayout.SOUTH);
        return panel;
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
            panel.add(new JBLabel("正在选择合适的知识点并生成小练习…"),
                    BorderLayout.NORTH);
            JButton cancel = new JButton("取消生成");
            cancel.addActionListener(event ->
                    ExerciseCoordinator.getInstance(project).cancelCurrent());
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
            actions.add(cancel);
            panel.add(actions, BorderLayout.SOUTH);
            return wrapExercisePanel(panel);
        }
        if (result.getStatus() == ExerciseResult.Status.FAILED) {
            JBLabel failure = new JBLabel("<html><b>小练习生成失败：</b>"
                    + escape(result.getErrorMessage()) + "</html>");
            failure.setForeground(JBColor.RED);
            panel.add(failure, BorderLayout.NORTH);
            JButton retry = new JButton("重试小练习");
            retry.addActionListener(event ->
                    ExerciseCoordinator.getInstance(project).generate());
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
            actions.add(retry);
            panel.add(actions, BorderLayout.SOUTH);
            return wrapExercisePanel(panel);
        }

        Exercise exercise = result.getExercise();
        if (exercise == null) {
            return wrapExercisePanel(panel);
        }
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        JBLabel title = new JBLabel("小练习 · " + exercise.getTitle());
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        content.add(title);
        content.add(readOnlySection("知识点", exercise.getKnowledgePoint(), false));
        content.add(readOnlySection("任务", exercise.getTask(), false));
        if (!exercise.getConstraints().isEmpty()) {
            content.add(readOnlySection(
                    "限制",
                    bulletText(exercise.getConstraints()),
                    false));
        }
        if (!exercise.getStarterCode().isBlank()) {
            content.add(readOnlySection("起始代码", exercise.getStarterCode(), true));
        }

        ReviewSession session = ReviewSession.getInstance(project);
        int revealedHints = Math.min(
                session.getRevealedExerciseHints(), exercise.getHints().size());
        if (revealedHints > 0) {
            content.add(readOnlySection(
                    "已查看提示",
                    bulletText(exercise.getHints().subList(0, revealedHints)),
                    false));
        }
        if (!exercise.getAcceptanceCriteria().isEmpty()) {
            content.add(readOnlySection(
                    "完成标准",
                    bulletText(exercise.getAcceptanceCriteria()),
                    false));
        }
        panel.add(content, BorderLayout.NORTH);

        if (revealedHints < exercise.getHints().size()) {
            JButton nextHint = new JButton(
                    "查看下一条提示（" + (revealedHints + 1)
                            + "/" + exercise.getHints().size() + "）");
            nextHint.setToolTipText("先独立尝试，需要时再逐条查看提示");
            nextHint.addActionListener(event -> session.revealNextExerciseHint());
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 6));
            actions.setOpaque(false);
            actions.add(nextHint);
            panel.add(actions, BorderLayout.SOUTH);
        }
        return wrapExercisePanel(panel);
    }

    private static JComponent readOnlySection(
            String title, String value, boolean monospace) {
        JPanel section = new JPanel(new BorderLayout(0, 2));
        section.setOpaque(false);
        section.setBorder(JBUI.Borders.emptyTop(6));
        JBLabel label = new JBLabel(title + "：");
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        section.add(label, BorderLayout.NORTH);

        JBTextArea text = new JBTextArea(value == null ? "" : value);
        text.setEditable(false);
        text.setLineWrap(!monospace);
        text.setWrapStyleWord(!monospace);
        text.setFocusable(false);
        if (monospace) {
            text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, text.getFont().getSize()));
            text.setRows(Math.max(2, Math.min(8, text.getLineCount())));
            text.setColumns(32);
            JBScrollPane scroll = new JBScrollPane(
                    text,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            scroll.setBorder(BorderFactory.createLineBorder(JBColor.border()));
            section.add(scroll, BorderLayout.CENTER);
        } else {
            text.setOpaque(false);
            text.setBorder(JBUI.Borders.empty());
            section.add(text, BorderLayout.CENTER);
        }
        return section;
    }

    private static String bulletText(List<String> items) {
        return items.stream()
                .map(item -> "• " + item)
                .collect(Collectors.joining("\n"));
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

        JBLabel title = new JBLabel(
                "代码已修改，当前结果可能过期",
                AllIcons.General.Warning,
                SwingConstants.LEFT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setForeground(COLOR_EXPIRY_TITLE);

        JBLabel desc = new JBLabel(
                "<html>分析过的代码已经变化，旧内容可能对不上当前位置。"
                        + "<br>点击上方按钮重新获取最新结果。</html>");
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

        JBLabel title = new JBLabel(
                "导师请求失败",
                AllIcons.General.Error,
                SwingConstants.LEFT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setForeground(JBColor.RED);

        JBLabel msg = new JBLabel("<html>" + escape(message != null ? message : "未知错误") + "</html>");
        msg.setForeground(JBColor.GRAY);

        JBLabel hint = new JBLabel("可以直接重试；若是 Key、端点或模型问题，请先打开设置检查连接。");
        hint.setForeground(JBColor.GRAY);
        hint.setFont(hint.getFont().deriveFont(11f));

        JPanel text = new JPanel(new BorderLayout(0, 2));
        text.setOpaque(false);
        text.add(title, BorderLayout.NORTH);
        text.add(msg, BorderLayout.CENTER);
        text.add(hint, BorderLayout.SOUTH);
        box.add(text, BorderLayout.NORTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 8));
        JButton retry = new JButton("重试当前操作");
        retry.addActionListener(event -> triggerCurrentModeAction());
        JButton settings = new JButton("打开设置…");
        settings.addActionListener(event -> openSettings());
        actions.add(retry);
        actions.add(settings);
        box.add(actions, BorderLayout.SOUTH);
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
        triggerAction(mode == ReviewMode.EXPLAIN
                ? EXPLAIN_ACTION_ID
                : REVIEW_ACTION_ID);
    }

    private void triggerAction(String actionId) {
        AnAction action = ActionManager.getInstance().getAction(actionId);
        if (action == null) {
            Messages.showErrorDialog(project, "找不到对应的 CodeMentor 操作。", "CodeMentor");
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

    private void showMoreMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.add(previewItem);
        menu.add(summaryItem);
        menu.add(exportItem);
        menu.addSeparator();
        menu.add(privacyItem);
        menu.add(settingsItem);
        menu.show(moreButton, 0, moreButton.getHeight());
    }

    private void openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(
                project, CodeMentorConfigurable.class);
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
        exportItem.setEnabled(false);
        Path path = target.getFile().toPath();
        exportFuture = LearningHistoryService.getInstance().exportMarkdown(path)
                .whenComplete((savedPath, error) -> SwingUtilities.invokeLater(() -> {
                    if (disposed || project.isDisposed()) return;
                    exportItem.setEnabled(true);
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
        try {
            ModelRequestConfig config =
                    ModelRequestConfig.capture(CodeMentorSettings.getInstance());
            SendPreviewDialog.showInfo(project, config, session.getContext());
        } catch (IllegalArgumentException error) {
            Messages.showErrorDialog(project, error.getMessage(), "CodeMentor 配置无效");
        }
    }

    private void showLearningSummary() {
        summaryItem.setEnabled(false);
        summaryFuture = LearningHistoryService.getInstance().loadSummary()
                .whenComplete((summary, error) -> SwingUtilities.invokeLater(() -> {
                    if (disposed || project.isDisposed()) return;
                    summaryItem.setEnabled(true);
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

    private void showPrivacyAndData() {
        int choice = Messages.showDialog(
                project,
                "发送授权按项目和模型地址保存；学习记录仅保存在本机 IDE 系统目录。",
                "CodeMentor 隐私与数据",
                new String[] {"撤销本项目发送授权", "清除全部本地学习记录", "取消"},
                2,
                Messages.getQuestionIcon());
        if (choice == 0) {
            CodeMentorProjectConsent.getInstance(project).revoke();
            ReviewCoordinator.getInstance(project).cancelAll();
            Messages.showInfoMessage(
                    project,
                    "已撤销本项目授权。下次手动评审或解释前会重新确认；"
                            + "验证、追问和练习会先被拒绝。",
                    "CodeMentor");
            return;
        }
        if (choice != 1) return;
        int confirmation = Messages.showYesNoDialog(
                project,
                "这会永久删除 CodeMentor 在本机保存的全部学习事件和轮转文件，且无法撤销。是否继续？",
                "清除 CodeMentor 学习记录",
                "清除",
                "取消",
                Messages.getWarningIcon());
        if (confirmation != Messages.YES) return;

        // Prevent in-flight callbacks or cached review identities from recreating
        // orphan learning events after the durable history has been deleted.
        for (Project openProject : ProjectManager.getInstance().getOpenProjects()) {
            if (!openProject.isDisposed()) {
                ReviewCoordinator.getInstance(openProject).clearLearningState();
            }
        }
        privacyItem.setEnabled(false);
        clearHistoryFuture = LearningHistoryService.getInstance().clearHistory()
                .whenComplete((unused, error) -> SwingUtilities.invokeLater(() -> {
                    if (disposed || project.isDisposed()) return;
                    privacyItem.setEnabled(true);
                    if (error == null) {
                        Messages.showInfoMessage(
                                project, "本地学习记录已清除。", "CodeMentor");
                    } else {
                        Throwable cause = error.getCause() == null ? error : error.getCause();
                        Messages.showErrorDialog(
                                project, cause.getMessage(), "CodeMentor");
                    }
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

    private static void cancel(CompletableFuture<?> future) {
        if (future != null) future.cancel(true);
    }
}
