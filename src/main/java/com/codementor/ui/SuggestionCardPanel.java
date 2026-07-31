package com.codementor.ui;

import com.codementor.history.LearningHistoryService;
import com.codementor.review.FeedbackType;
import com.codementor.review.FollowUpCoordinator;
import com.codementor.review.FollowUpResult;
import com.codementor.review.ReviewMode;
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
import com.intellij.util.ui.JBUI;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** 单条导师建议/理解要点卡片，行为都落回项目级 ReviewSession。 */
final class SuggestionCardPanel extends JBPanel<SuggestionCardPanel> {

    private static final Color COLOR_SUCCESS = new JBColor(
            new Color(0x2E, 0x7D, 0x32),
            new Color(0x66, 0xBB, 0x6A));

    private final Project project;
    private final Suggestion suggestion;
    private final JBLabel detailLabel = new JBLabel();
    private boolean expanded;

    SuggestionCardPanel(Project project, Suggestion suggestion) {
        this.project = project;
        this.suggestion = suggestion;
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1),
                JBUI.Borders.empty(8)));
        setOpaque(true);
        setBackground(JBColor.background());

        JBLabel title = buildTitle();
        JBLabel hint = new JBLabel(
                "<html><b>提示：</b>" + escape(suggestion.getHint()) + "</html>");
        hint.setForeground(JBColor.GRAY);
        detailLabel.setOpaque(false);

        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(title, BorderLayout.NORTH);
        head.add(hint, BorderLayout.CENTER);
        add(head, BorderLayout.NORTH);
        add(detailLabel, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
    }

    private JBLabel buildTitle() {
        JBLabel title = new JBLabel("<html><b>["
                + suggestion.getCategory().getLabel()
                + "/"
                + suggestion.getCertainty()
                + "]</b> "
                + escape(suggestion.getTitle())
                + "</html>");
        title.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        title.setIcon(categoryIcon(suggestion.getCategory()));
        title.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                toggleDetails();
            }
        });
        return title;
    }

    private JComponent buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        JPanel results = new JPanel();
        results.setLayout(new javax.swing.BoxLayout(
                results, javax.swing.BoxLayout.Y_AXIS));
        results.setOpaque(false);
        results.add(buildVerificationResult());
        results.add(buildFollowUpResult());
        footer.add(results, BorderLayout.CENTER);
        footer.add(buildActions(), BorderLayout.SOUTH);
        return footer;
    }

    private void toggleDetails() {
        expanded = !expanded;
        if (!expanded) {
            detailLabel.setText("");
            revalidate();
            repaint();
            return;
        }

        ReviewSession session = session();
        if (session.markExplanationViewed(suggestion.getId())
                && session.getContext() != null
                && session.getContext().getReviewMode() == ReviewMode.REVIEW) {
            LearningHistoryService.getInstance().recordExplanationViewed(
                    project,
                    session.getContext(),
                    suggestion,
                    session.getLearningReviewId());
        }
        detailLabel.setText("<html><hr size=1 color=#dddddd>"
                + "<b>为什么：</b>" + escape(suggestion.getExplanation()) + "<br><br>"
                + "<b>参考示例</b>（不是唯一答案）：<br>"
                + "<pre style='padding:6px;'>"
                + escape(suggestion.getExample())
                + "</pre><br><b>知识点：</b>"
                + escape(suggestion.getKnowledgePoint())
                + "</html>");
        revalidate();
        repaint();
    }

    private JPanel buildActions() {
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        actions.setOpaque(false);
        ReviewSession session = session();
        if (currentMode(session).supportsModificationVerification()) {
            actions.add(buildVerificationButton(session));
        }

        JButton followUpButton = new JButton("追问");
        FollowUpResult followUp =
                session.getFollowUpResult(suggestion.getId());
        followUpButton.setEnabled(
                followUp.getStatus() != FollowUpResult.Status.REQUESTING);
        followUpButton.addActionListener(event -> askFollowUp());
        actions.add(followUpButton);

        FeedbackType selected = session.getFeedback(suggestion.getId());
        for (FeedbackType feedbackType : FeedbackType.values()) {
            JButton button = new JButton(feedbackType.getLabel());
            button.setEnabled(selected != feedbackType);
            button.addActionListener(event -> recordFeedback(feedbackType));
            actions.add(button);
        }
        return actions;
    }

    private JButton buildVerificationButton(ReviewSession session) {
        JButton button = new JButton("验证我的修改");
        VerificationResult result =
                session.getVerificationResult(suggestion.getId());
        button.setEnabled(result.getStatus() != VerificationStatus.VERIFYING);
        if (result.getStatus() == VerificationStatus.VERIFYING) {
            button.setText("验证中…");
        }
        button.addActionListener(event ->
                VerificationCoordinator.getInstance(project).verify(suggestion));
        return button;
    }

    private JComponent buildVerificationResult() {
        VerificationResult result =
                session().getVerificationResult(suggestion.getId());
        if (result.getStatus() == VerificationStatus.IDLE) {
            return new JBLabel();
        }
        StringBuilder html = new StringBuilder("<html><b>修改验证：")
                .append(escape(result.getStatus().getLabel()))
                .append("</b>");
        if (!result.getEvidence().isBlank()) {
            html.append("<br>").append(escape(result.getEvidence()));
        }
        if (!result.getNextHint().isBlank()) {
            html.append("<br><b>下一步提示：</b>")
                    .append(escape(result.getNextHint()));
        }
        html.append("</html>");
        JBLabel label = new JBLabel(html.toString());
        label.setBorder(JBUI.Borders.empty(6, 0, 2, 0));
        label.setForeground(verificationColor(result.getStatus()));
        return label;
    }

    private JComponent buildFollowUpResult() {
        FollowUpResult result =
                session().getFollowUpResult(suggestion.getId());
        if (result.getStatus() == FollowUpResult.Status.IDLE) {
            return new JBLabel();
        }
        String text;
        if (result.getStatus() == FollowUpResult.Status.REQUESTING) {
            text = "<html><b>追问：</b>导师回答中…</html>";
        } else {
            text = "<html><b>追问"
                    + (result.getStatus() == FollowUpResult.Status.FAILED
                    ? "失败" : "回答")
                    + "：</b><br>"
                    + escape(result.getAnswer()).replace("\n", "<br>")
                    + "</html>";
        }
        JBLabel label = new JBLabel(text);
        label.setBorder(JBUI.Borders.empty(6, 0, 2, 0));
        label.setForeground(result.getStatus() == FollowUpResult.Status.FAILED
                ? JBColor.RED
                : JBColor.GRAY);
        return label;
    }

    private void askFollowUp() {
        String question = Messages.showInputDialog(
                project,
                "针对《" + suggestion.getTitle() + "》继续追问：",
                "CodeMentor 追问",
                Messages.getQuestionIcon());
        if (question != null && !question.isBlank()) {
            FollowUpCoordinator.getInstance(project).ask(suggestion, question);
        }
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

    private static ReviewMode currentMode(ReviewSession session) {
        return session.getContext() == null
                ? ReviewMode.REVIEW
                : session.getContext().getReviewMode();
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

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
