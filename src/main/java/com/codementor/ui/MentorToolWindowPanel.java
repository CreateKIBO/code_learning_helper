package com.codementor.ui;

import com.codementor.review.ReviewSession;
import com.codementor.review.ReviewStatus;
import com.codementor.review.Suggestion;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/**
 * 教练侧栏 Tool Window —— 评审结果展示区（方案第 5.2 节）。
 *
 * <p>架构约束（方案第 7 节）：UI 不自存状态，全部从 {@link ReviewSession} 读取。
 * 本类订阅会话变化，状态一变就 {@link #render()} 重新绘制。
 *
 * <p>阶段 0 展示内容（优化后）：
 * <ul>
 *   <li>顶部：评审目标 + 状态 + 「重新评审」按钮（改动2）</li>
 *   <li>空状态：居中图标+引导文案（改动3）</li>
 *   <li>已过期：橙色醒目横幅（改动4）</li>
 *   <li>失败：红色错误信息</li>
 *   <li>成功：建议卡片列表</li>
 * </ul>
 */
public class MentorToolWindowPanel extends SimpleToolWindowPanel {

    /** 重新评审按钮触发的 Action ID（与 plugin.xml 一致）。 */
    private static final String REVIEW_ACTION_ID = "CodeMentor.ReviewCurrent";

    private final Project project;
    private final JBLabel headerLabel = new JBLabel();
    private final JBLabel statusLabel = new JBLabel();
    private final JButton reReviewButton = new JButton("重新评审");
    private final JPanel cardsPanel = new JPanel(new BorderLayout());
    private final JPanel contentPanel = new JPanel(new BorderLayout());

    public MentorToolWindowPanel(@NotNull Project project) {
        // SimpleToolWindowPanel(boolean vertical)：true = 纵向布局（侧栏常用）
        super(true);
        this.project = project;
        setupUi();

        // 订阅会话变化（回调约定在 EDT，安全更新 UI）
        ReviewSession.getInstance().addListener(this::onSessionChanged);
        render();
    }

    private void setupUi() {
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD, 13f));
        headerLabel.setBorder(JBUI.Borders.empty(8, 10, 2, 10));

        statusLabel.setForeground(JBColor.GRAY);

        // 重新评审按钮（改动2）：复用已注册的 ReviewAction，不重复造逻辑
        reReviewButton.setToolTipText("重新评审当前方法（相当于 Alt+M）");
        reReviewButton.addActionListener(e -> triggerReviewAction());

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
        topPanel.add(reReviewButton, BorderLayout.EAST);
        // 按钮初始不显示，等评审成功/过期时才出现
        reReviewButton.setVisible(false);

        contentPanel.add(topPanel, BorderLayout.NORTH);

        JBScrollPane scrollPane = new JBScrollPane(cardsPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(JBUI.Borders.empty());
        contentPanel.add(scrollPane, BorderLayout.CENTER);

        setContent(contentPanel);
    }

    /** 会话变化回调（在 EDT）。 */
    private void onSessionChanged(ReviewSession session) {
        SwingUtilities.invokeLater(this::render);
    }

    /** 根据当前会话状态重绘整个面板。 */
    private void render() {
        ReviewSession session = ReviewSession.getInstance();
        ReviewStatus status = session.getStatus();

        // 头部：评审目标
        String target = session.getContext() != null ? session.getContext().getTargetName() : "—";
        headerLabel.setText("<html><b>评审目标：</b>" + escape(target) + "</html>");

        // 状态行
        statusLabel.setText("<html><b>状态：</b>" + statusText(status) + "</html>");
        statusLabel.setForeground(statusColor(status));

        // 重新评审按钮：成功 / 过期 / 失败 时可见（这些状态用户最可能想重评）
        boolean showButton = status == ReviewStatus.SUCCESS
                || status == ReviewStatus.EXPIRED
                || status == ReviewStatus.FAILED;
        reReviewButton.setVisible(showButton);

        // 卡片区：按状态分别渲染
        cardsPanel.removeAll();

        if (status == ReviewStatus.SUCCESS && session.getSuggestions() != null) {
            cardsPanel.add(buildSuccessList(session.getSuggestions()), BorderLayout.NORTH);
        } else if (status == ReviewStatus.EXPIRED) {
            // 改动4：过期醒目横幅
            cardsPanel.add(buildExpiryBanner(), BorderLayout.NORTH);
        } else if (status == ReviewStatus.FAILED) {
            cardsPanel.add(buildFailureHint(session.getErrorMessage()), BorderLayout.NORTH);
        } else if (status == ReviewStatus.REQUESTING) {
            cardsPanel.add(buildRequestingHint(), BorderLayout.NORTH);
        } else {
            // IDLE：改动3 空状态美化
            cardsPanel.add(buildEmptyState(), BorderLayout.CENTER);
        }

        cardsPanel.revalidate();
        cardsPanel.repaint();
    }

    // ---------------- 各状态的渲染组件 ----------------

    /** 改动3：空状态——居中图标 + 主副标题 + 快捷键提示。 */
    private JComponent buildEmptyState() {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(new EmptyBorder(40, 20, 40, 20));

        JBLabel icon = new JBLabel(com.intellij.icons.AllIcons.General.Balloon, SwingConstants.CENTER);
        icon.setAlignmentX(Component.CENTER_ALIGNMENT);
        icon.setIconTextGap(0);

        JBLabel title = new JBLabel("CodeMentor 教练");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        title.setBorder(new EmptyBorder(14, 0, 6, 0));

        JBLabel hint1 = new JBLabel("按 Alt+M 评审当前方法");
        hint1.setForeground(JBColor.GRAY);
        hint1.setAlignmentX(Component.CENTER_ALIGNMENT);

        JBLabel hint2 = new JBLabel("或选中代码后右键 → 请导师评审");
        hint2.setForeground(JBColor.GRAY);
        hint2.setFont(hint2.getFont().deriveFont(11f));
        hint2.setAlignmentX(Component.CENTER_ALIGNMENT);
        hint2.setBorder(new EmptyBorder(4, 0, 0, 0));

        box.add(icon);
        box.add(title);
        box.add(hint1);
        box.add(hint2);
        return box;
    }

    /** 请求中：简单提示。 */
    private JComponent buildRequestingHint() {
        JBLabel l = new JBLabel("导师评审中…");
        l.setForeground(JBColor.BLUE);
        l.setBorder(new EmptyBorder(20, 14, 20, 14));
        return l;
    }

    /** 成功：建议列表。 */
    private JComponent buildSuccessList(List<Suggestion> suggestions) {
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(JBUI.Borders.empty(4, 10, 10, 10));
        JBLabel countLabel = new JBLabel("共 " + suggestions.size() + " 条建议：");
        countLabel.setBorder(JBUI.Borders.empty(0, 0, 6, 0));
        list.add(countLabel);
        for (Suggestion s : suggestions) {
            list.add(new SuggestionCard(s));
            list.add(Box.createVerticalStrut(6));
        }
        return list;
    }

    /** 改动4：已过期橙色横幅。 */
    private JComponent buildExpiryBanner() {
        JPanel banner = new JPanel(new BorderLayout(8, 4));
        banner.setBackground(new JBColor(new Color(0xFF, 0xF3, 0xE0), new Color(0x4E, 0x3A, 0x1A)));
        banner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new JBColor(new Color(0xFF, 0xB7, 0x4D), new Color(0x8A, 0x5A, 0x10)), 1),
                JBUI.Borders.empty(10, 12)));

        JBLabel title = new JBLabel("⚠ 代码已修改，建议可能过期");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setForeground(new JBColor(new Color(0xE6, 0x51, 0x00), new Color(0xFF, 0xB7, 0x4D)));

        JBLabel desc = new JBLabel("<html>被评审的代码已经变化，旧建议可能对不上当前位置。<br>点击右上角「重新评审」获取最新建议。</html>");
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

        JBLabel title = new JBLabel("✗ 评审失败");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setForeground(JBColor.RED);

        JBLabel msg = new JBLabel("<html>" + escape(message != null ? message : "未知错误") + "</html>");
        msg.setForeground(JBColor.GRAY);

        JBLabel hint = new JBLabel("点击右上角「重新评审」重试。");
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
     * 改动2：编程式触发已注册的 ReviewAction，复用其完整逻辑。
     *
     * <p>不使用 ActionManager.tryToExecute（它要求 InputEvent，适合鼠标触发场景），
     * 而是实时取当前活跃编辑器和 PsiFile，构造一个完整 DataContext，
     * 再调用 action.actionPerformed——和用户按 Alt+M 走完全一样的代码路径。
     *
     * <p>dataId 必须用 CommonDataKeys 各键的 getName()，不能猜字符串常量。
     */
    private void triggerReviewAction() {
        AnAction action = ActionManager.getInstance().getAction(REVIEW_ACTION_ID);
        if (action == null) {
            return;
        }

        var fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
        var psiDocumentManager = com.intellij.psi.PsiDocumentManager.getInstance(project);

        DataContext dataContext = dataId -> {
            if (dataId.equals(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.getName())) {
                return project;
            }
            if (dataId.equals(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR.getName())) {
                return fileEditorManager.getSelectedTextEditor();
            }
            if (dataId.equals(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE.getName())) {
                var editor = fileEditorManager.getSelectedTextEditor();
                return editor != null ? psiDocumentManager.getPsiFile(editor.getDocument()) : null;
            }
            return null;
        };

        AnActionEvent event = AnActionEvent.createFromDataContext("CodeMentor.Toolbar", null, dataContext);
        action.actionPerformed(event);
    }

    /** 单条建议卡片：标题可点击展开讲解/示例（方案第 9 节三层渐进展示）。 */
    private static final class SuggestionCard extends JBPanel<SuggestionCard> {
        private boolean expanded = false;
        private final JBLabel detailLabel;

        SuggestionCard(Suggestion s) {
            setLayout(new BorderLayout());
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor.border(), 1),
                    JBUI.Borders.empty(8)));
            setOpaque(true);
            setBackground(JBColor.background());

            // 标题行：[类别/置信] 标题
            JBLabel title = new JBLabel("<html>"
                    + "<b>[" + s.getCategory().getLabel() + "/" + s.getCertainty() + "]</b> "
                    + escape(s.getTitle())
                    + "</html>");
            title.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            title.setIcon(allIcons(s));

            // 第一层：提示（默认显示）
            JBLabel hint = new JBLabel("<html><b>提示：</b>" + escape(s.getHint()) + "</html>");
            hint.setForeground(JBColor.GRAY);

            // 第二、三层：讲解 + 示例（默认折叠）
            detailLabel = new JBLabel();
            detailLabel.setOpaque(false);

            title.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent e) { toggle(s); }
            });

            JPanel head = new JPanel(new BorderLayout());
            head.setOpaque(false);
            head.add(title, BorderLayout.NORTH);
            head.add(hint, BorderLayout.CENTER);

            add(head, BorderLayout.NORTH);
            add(detailLabel, BorderLayout.CENTER);
        }

        private void toggle(Suggestion s) {
            expanded = !expanded;
            if (expanded) {
                String html = "<html>"
                        + "<hr size=1 color=#dddddd>"
                        + "<b>为什么：</b>" + escape(s.getExplanation()) + "<br><br>"
                        + "<b>参考示例</b>（不是唯一答案）：<br>"
                        + "<pre style='background:#f5f5f5;padding:6px;'>" + escape(s.getExample()) + "</pre><br>"
                        + "<b>知识点：</b>" + escape(s.getKnowledgePoint())
                        + "</html>";
                detailLabel.setText(html);
            } else {
                detailLabel.setText("");
            }
            revalidate();
            repaint();
        }
    }

    // ---------- 小工具 ----------

    private static String statusText(ReviewStatus s) {
        switch (s) {
            case IDLE: return "空闲";
            case REQUESTING: return "导师评审中…";
            case SUCCESS: return "评审完成";
            case FAILED: return "失败";
            case EXPIRED: return "已过期（代码已修改）";
            default: return s.toString();
        }
    }

    private static Color statusColor(ReviewStatus s) {
        switch (s) {
            case REQUESTING: return JBColor.BLUE;
            case SUCCESS: return new JBColor(new Color(0x2E, 0x7D, 0x32), new Color(0x66, 0xBB, 0x6A));
            case FAILED: return JBColor.RED;
            case EXPIRED: return JBColor.ORANGE;
            default: return JBColor.GRAY;
        }
    }

    private static javax.swing.Icon allIcons(Suggestion s) {
        // 重要：这里只用"上次编译已被验证存在"的图标，避免再踩图标名漂移的坑。
        // 已验证存在：General.Warning / General.Information / General.Balloon / FileTypes.Java
        switch (s.getCategory()) {
            case CORRECTNESS: return com.intellij.icons.AllIcons.General.Warning;
            case DESIGN: return com.intellij.icons.AllIcons.General.Balloon;
            case JAVAWEB: return com.intellij.icons.AllIcons.General.Information;
            case READABILITY: return com.intellij.icons.AllIcons.General.Information;
            case JAVA_USAGE: return com.intellij.icons.AllIcons.FileTypes.Java;
            default: return com.intellij.icons.AllIcons.General.Balloon;
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
