package com.codementor.ui;

import com.codementor.history.LearningHistoryService;
import com.codementor.review.FeedbackType;
import com.codementor.review.FollowUpCoordinator;
import com.codementor.review.FollowUpResult;
import com.codementor.review.ReviewMode;
import com.codementor.review.ReviewStatus;
import com.codementor.review.ReviewSession;
import com.codementor.review.Suggestion;
import com.codementor.review.VerificationCoordinator;
import com.codementor.review.VerificationResult;
import com.codementor.review.VerificationStatus;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;

/** A native, progressive learning card for one model-grounded suggestion. */
final class SuggestionCardPanel extends JBPanel<SuggestionCardPanel> {

    private static final Color COLOR_SUCCESS = new JBColor(
            new Color(0x2E, 0x7D, 0x32),
            new Color(0x66, 0xBB, 0x6A));

    private static final String SIMPLE_EXPLANATION_QUESTION =
            "请换一种更简单的说法解释这个知识点，并继续结合当前代码证据。";
    private static final String SMALL_EXAMPLE_QUESTION =
            "请给一个比当前代码更小的对比例子，只说明这个知识点，不要替我完成项目代码。";
    private static final String VERIFY_UNDERSTANDING_QUESTION =
            "请告诉我如何自己验证是否真正理解了这个知识点，最后问我一道自测题。";

    private final Project project;
    private final Suggestion suggestion;

    SuggestionCardPanel(Project project, Suggestion suggestion) {
        this.project = project;
        this.suggestion = suggestion;
        setLayout(new BorderLayout(0, 6));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1),
                JBUI.Borders.empty(10)));
        setOpaque(true);
        setBackground(JBColor.background());
        getAccessibleContext().setAccessibleName("CodeMentor 建议：" + suggestion.getTitle());

        add(buildLearningPath(), BorderLayout.NORTH);
        add(buildProgressiveDetails(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
    }

    private JComponent buildLearningPath() {
        JPanel content = verticalPanel();
        content.add(buildTitle());
        content.add(textSection("代码依据", suggestion.getEvidence(), null));
        content.add(textSection("先想一想", suggestion.getHint(), JBColor.GRAY));
        // 解释模式先讲清楚，再给阅读路径；评审和编码指导先给要做的事，讲解按需展开。
        if (currentMode() != ReviewMode.EXPLAIN) {
            addPracticeSections(content);
        }
        return content;
    }

    private void addPracticeSections(JPanel content) {
        if (!suggestion.getNextStep().isBlank()) {
            content.add(textSection(
                    currentMode() == ReviewMode.EXPLAIN ? "理解路径" : "先做这一步",
                    suggestion.getNextStep(),
                    null));
        }
        if (!suggestion.getVerificationCriteria().isBlank()) {
            content.add(textSection(
                    currentMode() == ReviewMode.EXPLAIN ? "学会的标准" : "完成标准",
                    suggestion.getVerificationCriteria(),
                    null));
        }
    }

    private JBLabel buildTitle() {
        String prefix = currentMode() == ReviewMode.NEXT_STEP
                ? "编码任务 · " + suggestion.getCategory().getLabel()
                : currentMode() == ReviewMode.EXPLAIN
                ? "理解要点 · " + suggestion.getCategory().getLabel()
                : suggestion.getCategory().getLabel() + " · "
                        + certaintyLabel(suggestion.getCertainty());
        JBLabel title = new JBLabel(
                prefix + " — " + suggestion.getTitle(),
                categoryIcon(suggestion.getCategory()),
                JBLabel.LEFT);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setBorder(JBUI.Borders.emptyBottom(4));
        return title;
    }

    private JComponent buildProgressiveDetails() {
        ReviewSession session = session();
        ReviewMode mode = currentMode();
        boolean expanded = session.isExplanationExpanded(suggestion.getId());
        JPanel details = verticalPanel();
        if (expanded) {
            details.add(textSection(explanationTitle(mode), suggestion.getExplanation(), null));
            addOptionalSection(details, rationaleTitle(mode), suggestion.getRationale());
            addOptionalSection(details, "换一种写法会怎样", suggestion.getAlternatives());
            addOptionalSection(details, "常见误区", suggestion.getPitfall());
            details.add(textSection("知识点", suggestion.getKnowledgePoint(), JBColor.GRAY));
        }
        if (mode == ReviewMode.EXPLAIN) {
            addPracticeSections(details);
        }
        if (expanded && !suggestion.getSelfCheckQuestion().isBlank()) {
            details.add(textSection(
                    "自测一下（先在心里回答）",
                    suggestion.getSelfCheckQuestion(),
                    null));
        }
        if (session.isExampleExpanded(suggestion.getId())
                && !suggestion.getExample().isBlank()) {
            details.add(new JBLabel("参考示例（不是唯一答案）"));
            details.add(codeBlock(suggestion.getExample()));
        }
        return details;
    }

    private static String explanationTitle(ReviewMode mode) {
        return switch (mode) {
            case EXPLAIN -> "它在做什么";
            case NEXT_STEP -> "为什么先做这一步";
            case REVIEW -> "为什么";
        };
    }

    private static String rationaleTitle(ReviewMode mode) {
        return mode == ReviewMode.NEXT_STEP ? "为什么按项目的写法来写" : "为什么这样写";
    }

    private static void addOptionalSection(JPanel target, String title, String text) {
        if (text != null && !text.isBlank()) {
            target.add(textSection(title, text, null));
        }
    }

    private JComponent buildFooter() {
        JPanel footer = verticalPanel();
        footer.add(buildVerificationResult());
        footer.add(buildFollowUpResult());
        footer.add(buildActions());
        return footer;
    }

    private JPanel buildActions() {
        JPanel actions = verticalPanel();
        JPanel learningActions = actionRow();
        ReviewSession session = session();

        boolean explanationExpanded = session.isExplanationExpanded(suggestion.getId());
        JButton whyButton = new JButton(
                explanationExpanded ? "收起讲解"
                        : currentMode() == ReviewMode.EXPLAIN ? "展开讲解" : "理解原因");
        whyButton.setToolTipText("先自己思考，再按需查看完整讲解");
        whyButton.addActionListener(event -> toggleExplanation());
        learningActions.add(whyButton);

        if (!suggestion.getEvidence().isBlank()) {
            JButton locateButton = new JButton("定位代码");
            locateButton.setToolTipText("把编辑器光标移到这条依据对应的代码上");
            locateButton.addActionListener(event ->
                    EditorLocator.reveal(project, suggestion.getEvidence()));
            learningActions.add(locateButton);
        }

        if (explanationExpanded && !suggestion.getExample().isBlank()) {
            JButton exampleButton = new JButton(
                    session.isExampleExpanded(suggestion.getId())
                            ? "收起参考" : "查看参考");
            exampleButton.addActionListener(event -> session.toggleExample(suggestion.getId()));
            learningActions.add(exampleButton);
        }

        if (currentMode().supportsModificationVerification()
                && session.getContext() != null
                && session.getContext().getMethodStart() >= 0) {
            learningActions.add(buildVerificationButton(session));
        }

        JButton followUpButton = new JButton("继续追问…");
        followUpButton.setEnabled(session.getStatus() != ReviewStatus.EXPIRED);
        FollowUpResult followUp = session.getFollowUpResult(suggestion.getId());
        if (followUp.getStatus() == FollowUpResult.Status.REQUESTING) {
            followUpButton.setText("取消追问");
            followUpButton.addActionListener(event ->
                    FollowUpCoordinator.getInstance(project).cancel(suggestion.getId()));
        } else {
            followUpButton.addActionListener(event -> showFollowUpMenu(followUpButton));
        }
        learningActions.add(followUpButton);
        actions.add(learningActions);

        JPanel feedbackActions = actionRow();
        JBLabel feedbackLabel = new JBLabel("这条建议：");
        feedbackLabel.setForeground(JBColor.GRAY);
        feedbackActions.add(feedbackLabel);
        FeedbackType selected = session.getFeedback(suggestion.getId());
        for (FeedbackType feedbackType : FeedbackType.values()) {
            JButton button = new JButton(feedbackType.getLabel());
            button.setEnabled(selected != feedbackType);
            button.addActionListener(event -> recordFeedback(feedbackType));
            feedbackActions.add(button);
        }
        actions.add(feedbackActions);
        return actions;
    }

    private JButton buildVerificationButton(ReviewSession session) {
        JButton button = new JButton("我改好了，验证");
        VerificationResult result = session.getVerificationResult(suggestion.getId());
        button.setEnabled(result.getStatus() != VerificationStatus.VERIFYING);
        if (result.getStatus() == VerificationStatus.VERIFYING) {
            button.setText("验证中…");
        }
        button.setToolTipText("只验证当前建议是否解决，不重新评审整段代码");
        button.addActionListener(event ->
                VerificationCoordinator.getInstance(project).verify(suggestion));
        return button;
    }

    private JComponent buildVerificationResult() {
        VerificationResult result = session().getVerificationResult(suggestion.getId());
        if (result.getStatus() == VerificationStatus.IDLE) return emptyPanel();

        JPanel panel = statusPanel("修改验证 · " + result.getStatus().getLabel(),
                verificationColor(result.getStatus()));
        if (!result.getEvidence().isBlank()) {
            panel.add(textSection("判断依据", result.getEvidence(), null));
        }
        if (!result.getNextHint().isBlank()) {
            panel.add(textSection("下一步提示", result.getNextHint(), null));
        }
        return panel;
    }

    private JComponent buildFollowUpResult() {
        FollowUpResult result = session().getFollowUpResult(suggestion.getId());
        if (result.getStatus() == FollowUpResult.Status.IDLE) return emptyPanel();

        Color color = result.getStatus() == FollowUpResult.Status.FAILED
                ? JBColor.RED : JBColor.GRAY;
        String title = switch (result.getStatus()) {
            case REQUESTING -> "继续追问 · 导师回答中…";
            case FAILED -> "继续追问 · 失败";
            case SUCCESS -> "继续追问 · 回答";
            default -> "继续追问";
        };
        JPanel panel = statusPanel(title, color);
        if (!result.getQuestion().isBlank()) {
            panel.add(textSection("你的问题", result.getQuestion(), null));
        }
        if (!result.getAnswer().isBlank()) {
            panel.add(textSection(
                    result.getStatus() == FollowUpResult.Status.FAILED ? "原因" : "导师回答",
                    result.getAnswer(),
                    color));
        }
        return panel;
    }

    private void toggleExplanation() {
        ReviewSession session = session();
        boolean firstView = !session.isExplanationExpanded(suggestion.getId())
                && session.markExplanationViewed(suggestion.getId());
        if (firstView
                && session.getContext() != null
                && session.getContext().getReviewMode() == ReviewMode.REVIEW) {
            LearningHistoryService.getInstance().recordExplanationViewed(
                    project,
                    session.getContext(),
                    suggestion,
                    session.getLearningReviewId());
        }
        session.toggleExplanation(suggestion.getId());
    }

    private void showFollowUpMenu(JButton source) {
        JPopupMenu menu = new JPopupMenu();
        addFollowUpItem(menu, "换一种更简单的说法", SIMPLE_EXPLANATION_QUESTION);
        addFollowUpItem(menu, "给一个更小的例子", SMALL_EXAMPLE_QUESTION);
        addFollowUpItem(menu, "怎样验证我真的理解了", VERIFY_UNDERSTANDING_QUESTION);
        menu.addSeparator();
        JMenuItem custom = new JMenuItem("自定义问题…");
        custom.addActionListener(event -> askCustomFollowUp());
        menu.add(custom);
        menu.show(source, 0, source.getHeight());
    }

    private void addFollowUpItem(JPopupMenu menu, String label, String question) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(event -> askFollowUp(question));
        menu.add(item);
    }

    private void askCustomFollowUp() {
        String question = Messages.showInputDialog(
                project,
                "针对《" + suggestion.getTitle() + "》继续追问（最多 "
                        + FollowUpCoordinator.MAX_QUESTION_CHARS + " 个字符）：",
                "CodeMentor 追问",
                Messages.getQuestionIcon());
        if (question != null && !question.isBlank()) askFollowUp(question);
    }

    private void askFollowUp(String question) {
        FollowUpCoordinator.getInstance(project).ask(suggestion, question);
    }

    private void recordFeedback(FeedbackType feedbackType) {
        ReviewSession session = session();
        if (session.setFeedback(suggestion.getId(), feedbackType)
                && session.getContext() != null
                && session.getContext().getReviewMode() == ReviewMode.REVIEW) {
            LearningHistoryService.getInstance().recordFeedback(
                    project,
                    session.getContext(),
                    suggestion,
                    feedbackType,
                    session.getLearningReviewId());
        }
    }

    private ReviewSession session() {
        return ReviewSession.getInstance(project);
    }

    private ReviewMode currentMode() {
        ReviewSession session = session();
        return session.getContext() == null
                ? ReviewMode.REVIEW
                : session.getContext().getReviewMode();
    }

    private static JPanel verticalPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        return panel;
    }

    private static JPanel actionRow() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
        panel.setOpaque(false);
        return panel;
    }

    private static JPanel emptyPanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        return panel;
    }

    private static JPanel statusPanel(String title, Color color) {
        JPanel panel = verticalPanel();
        panel.setBorder(JBUI.Borders.empty(6, 0, 2, 0));
        JBLabel label = new JBLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setForeground(color);
        panel.add(label);
        return panel;
    }

    private static JComponent textSection(String title, String text, Color color) {
        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.setOpaque(false);
        panel.setBorder(JBUI.Borders.emptyTop(5));

        JBLabel label = new JBLabel(title + "：");
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        panel.add(label, BorderLayout.NORTH);

        JBTextArea value = new JBTextArea(text == null ? "" : text);
        value.setEditable(false);
        value.setLineWrap(true);
        value.setWrapStyleWord(true);
        value.setOpaque(false);
        value.setBorder(JBUI.Borders.empty());
        value.setFocusable(false);
        if (color != null) value.setForeground(color);
        panel.add(value, BorderLayout.CENTER);
        return panel;
    }

    private static JComponent codeBlock(String code) {
        JBTextArea area = new JBTextArea(code == null ? "" : code);
        area.setEditable(false);
        area.setLineWrap(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
        area.setRows(Math.max(2, Math.min(10, area.getLineCount())));
        area.setColumns(32);
        area.setBorder(JBUI.Borders.empty(6));
        area.getAccessibleContext().setAccessibleName("参考代码");
        JBScrollPane scroll = new JBScrollPane(
                area,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(BorderFactory.createLineBorder(JBColor.border()));
        return scroll;
    }

    private static String certaintyLabel(Suggestion.Certainty certainty) {
        return switch (certainty) {
            case HIGH -> "明确问题";
            case MEDIUM -> "可能风险";
            case LOW -> "设计取舍";
        };
    }

    private static Color verificationColor(VerificationStatus status) {
        return switch (status) {
            case RESOLVED -> COLOR_SUCCESS;
            case PARTIALLY_RESOLVED -> JBColor.ORANGE;
            case UNRESOLVED, FAILED -> JBColor.RED;
            default -> JBColor.GRAY;
        };
    }

    private static javax.swing.Icon categoryIcon(Suggestion.Category category) {
        return switch (category) {
            case CORRECTNESS -> AllIcons.General.Warning;
            case DESIGN, READABILITY, JAVAWEB -> AllIcons.General.Information;
            case JAVA_USAGE -> AllIcons.FileTypes.Java;
        };
    }
}
