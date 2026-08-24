package com.codementor.ui;

import com.codementor.history.KnowledgeReminder;
import com.codementor.history.LearningSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryHtmlFormatterTest {

    @Test
    void rendersCountsRatesAndFooterWithoutWeakPoints() {
        LearningSummary summary = new LearningSummary(
                10, 4, 5, 2, 1, 1, 0, List.of());

        String html = SummaryHtmlFormatter.html(summary);

        assertTrue(html.startsWith("<html>"));
        assertTrue(html.contains("累计建议：10"));
        assertTrue(html.contains("完成修改验证：5，解决率：40%"));
        assertTrue(html.contains("查看讲解：4，查看率：40%"));
        assertTrue(html.contains("有帮助 1 / 没帮助 1 / 误报 0"));
        assertFalse(html.contains("当前薄弱知识点"));
        assertTrue(html.contains("统计仅来自本机事件，不包含完整源代码"));
    }

    @Test
    void rendersWeakKnowledgePointsWithEscaping() {
        LearningSummary summary = new LearningSummary(
                3, 0, 0, 0, 0, 0, 0,
                List.of(new KnowledgeReminder("<Map>&套用", 7)));

        String html = SummaryHtmlFormatter.html(summary);

        assertTrue(html.contains("当前薄弱知识点"));
        assertTrue(html.contains("&lt;Map&gt;&amp;套用"));
        assertFalse(html.contains("<Map>"));
        assertTrue(html.contains("（待巩固 7 次）"));
    }

    @Test
    void zeroTotalsRenderAsZeroPercent() {
        LearningSummary summary = new LearningSummary(
                0, 0, 0, 0, 0, 0, 0, List.of());

        String html = SummaryHtmlFormatter.html(summary);

        assertTrue(html.contains("解决率：0%"));
        assertTrue(html.contains("查看率：0%"));
    }

    @Test
    void escapeHandlesNullAndBasicEntities() {
        assertEquals("", SummaryHtmlFormatter.escape(null));
        assertEquals("a&amp;b&lt;c&gt;d", SummaryHtmlFormatter.escape("a&b<c>d"));
    }
}
