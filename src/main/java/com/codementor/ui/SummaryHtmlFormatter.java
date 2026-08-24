package com.codementor.ui;

import com.codementor.history.KnowledgeReminder;
import com.codementor.history.LearningSummary;

/** 纯函数：把本地学习摘要拼成可在 Swing 组件中显示的 HTML。无状态、可直接单测。 */
final class SummaryHtmlFormatter {

    private SummaryHtmlFormatter() {}

    static String html(LearningSummary summary) {
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

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
