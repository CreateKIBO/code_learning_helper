package com.codementor.ui;

import com.codementor.model.ModelRequestConfig;
import com.codementor.privacy.SendPreviewFormatter;
import com.codementor.review.ReviewContext;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;

/** Scrollable, plain-text preview of the exact masked context sent to the endpoint. */
public final class SendPreviewDialog extends DialogWrapper {

    private static final int MAX_PREVIEW_CHARS = 32_000;

    private final ModelRequestConfig config;
    private final ReviewContext context;

    private SendPreviewDialog(
            Project project,
            ModelRequestConfig config,
            ReviewContext context,
            boolean confirmation,
            boolean firstConsent) {
        super(project, true);
        this.config = config;
        this.context = context;
        setTitle(firstConsent ? "首次发送确认 · CodeMentor" : "发送内容预览 · CodeMentor");
        if (confirmation) {
            setOKButtonText(firstConsent ? "发送并记住此地址" : "本次发送");
            setCancelButtonText("取消");
        } else {
            setOKButtonText("关闭");
        }
        init();
    }

    public static boolean confirm(
            Project project,
            ModelRequestConfig config,
            ReviewContext context,
            boolean firstConsent) {
        return new SendPreviewDialog(project, config, context, true, firstConsent)
                .showAndGet();
    }

    public static void showInfo(
            Project project, ModelRequestConfig config, ReviewContext context) {
        SendPreviewDialog dialog =
                new SendPreviewDialog(project, config, context, false, false);
        dialog.show();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(JBUI.Borders.empty(8));
        panel.add(new JBLabel(
                "<html>下列脱敏学习内容将作为请求的一部分发送到 <b>"
                        + escapeHtml(config.endpoint())
                        + "</b>。<br>本机文件：" + escapeHtml(context.getFilePath())
                        + "；目标：" + escapeHtml(context.getTargetName())
                        + "；上下文截断：" + (context.isTruncated() ? "是" : "否")
                        + "。请在发送前自行确认。</html>"),
                BorderLayout.NORTH);

        JBTextArea preview = new JBTextArea(
                SendPreviewFormatter.format(context, MAX_PREVIEW_CHARS));
        preview.setEditable(false);
        preview.setLineWrap(false);
        preview.setCaretPosition(0);
        JBScrollPane scrollPane = new JBScrollPane(preview);
        scrollPane.setPreferredSize(new Dimension(760, 520));
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
