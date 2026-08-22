package com.codementor.history;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningEventStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void appendsEventsAndExportsResolvedReviewWithoutSourceCode() throws Exception {
        Path events = tempDir.resolve("learning-events.jsonl");
        LearningEventStore store = new LearningEventStore(events);
        store.append(event(LearningEvent.Type.REVIEWED, "资源管理", "关闭资源",
                "new FileInputStream", ""));
        store.append(event(LearningEvent.Type.EXPLANATION_VIEWED, "资源管理", "关闭资源",
                "", ""));
        store.append(event(LearningEvent.Type.FEEDBACK, "资源管理", "关闭资源",
                "", "HELPFUL"));
        store.append(event(LearningEvent.Type.VERIFICATION_COMPLETED, "资源管理", "关闭资源",
                "已经使用 try-with-resources", "RESOLVED"));

        List<LearningEvent> loaded = store.readAll();
        assertEquals(4, loaded.size());

        Path markdown = tempDir.resolve("review.md");
        store.exportMarkdown(markdown);
        String text = Files.readString(markdown);

        assertTrue(text.contains("- [x] 关闭资源"));
        assertTrue(text.contains("修改验证：RESOLVED"));
        assertTrue(text.contains("已查看讲解"));
        assertFalse(text.contains("class SecretSource"));
    }

    @Test
    void skipsCorruptJsonLineAndKeepsValidEvents() throws Exception {
        Path events = tempDir.resolve("learning-events.jsonl");
        LearningEventStore store = new LearningEventStore(events);
        store.append(event(LearningEvent.Type.REVIEWED, "集合", "使用泛型", "List list", ""));
        Files.writeString(events, "{not-json}\n", java.nio.file.StandardOpenOption.APPEND);
        Files.write(
                events,
                new byte[]{(byte) 0xC3, (byte) 0x28, (byte) '\n'},
                StandardOpenOption.APPEND);

        assertEquals(1, store.readAll().size());
    }

    @Test
    void acceptsSyntacticallyValidEventsWithEmptyFields() throws Exception {
        Path events = tempDir.resolve("learning-events.jsonl");
        Files.writeString(events, "{\"type\":\"REVIEWED\"}\n", StandardCharsets.UTF_8);
        LearningEventStore store = new LearningEventStore(events);

        LearningSummary summary = store.summarize();

        assertEquals(1, summary.getReviewedSuggestions());
        assertTrue(summary.getWeakKnowledgePoints().isEmpty());
    }

    @Test
    void readsOnlyBoundedTailAndRetainsNewestEvents() throws Exception {
        Path events = tempDir.resolve("learning-events.jsonl");
        LearningEvent newest = event("newest", LearningEvent.Type.REVIEWED,
                "Streams", "newest", "", "");
        String oversizedPrefix = "x".repeat(2_000) + "\n";
        Files.writeString(
                events,
                oversizedPrefix + new Gson().toJson(newest) + "\n",
                StandardCharsets.UTF_8);
        LearningEventStore store = new LearningEventStore(
                events, new Gson(), 700, 4_096, 3);

        List<LearningEvent> loaded = store.readAll();

        assertEquals(1, loaded.size());
        assertEquals("newest", loaded.get(0).getTitle());
    }

    @Test
    void rotatesWithinSizeLimitAndSafelyTrimsOversizedLegacyFile() throws Exception {
        Path events = tempDir.resolve("learning-events.jsonl");
        long fileLimit = 700;
        Files.writeString(events, "damaged".repeat(500), StandardCharsets.UTF_8);
        LearningEventStore store = new LearningEventStore(
                events, new Gson(), fileLimit, 4_096, 100);

        store.append(event("latest", LearningEvent.Type.REVIEWED,
                "Files", "latest", "", ""));

        Path rotated = Path.of(events + ".1");
        assertTrue(Files.exists(rotated));
        assertTrue(Files.size(events) <= fileLimit);
        assertTrue(Files.size(rotated) <= fileLimit);
        assertEquals("latest", store.readAll().get(0).getTitle());

        for (int index = 0; index < 12; index++) {
            store.append(event("review-" + index, LearningEvent.Type.REVIEWED,
                    "Files", "item-" + index, "", ""));
        }
        assertTrue(Files.size(events) <= fileLimit);
        assertTrue(Files.size(rotated) <= fileLimit);
        assertTrue(store.readAll().stream()
                .anyMatch(event -> "item-11".equals(event.getTitle())));
    }

    @Test
    void clearRemovesActiveRotatedAndTemporaryFiles() throws Exception {
        Path events = tempDir.resolve("learning-events.jsonl");
        LearningEventStore store = new LearningEventStore(
                events, new Gson(), 700, 4_096, 100);
        for (int index = 0; index < 6; index++) {
            store.append(event("review-" + index, LearningEvent.Type.REVIEWED,
                    "Files", "item-" + index, "", ""));
        }
        Path rotated = Path.of(events + ".1");
        Path temporary = Path.of(events + ".rotation.tmp");
        Files.writeString(temporary, "stale", StandardOpenOption.CREATE);

        store.clear();

        assertFalse(Files.exists(events));
        assertFalse(Files.exists(rotated));
        assertFalse(Files.exists(temporary));
        assertTrue(store.readAll().isEmpty());
    }

    @Test
    void exportEscapesHtmlMarkdownLinksAndControlCharacters() throws Exception {
        Path events = tempDir.resolve("learning-events.jsonl");
        LearningEventStore store = new LearningEventStore(events);
        store.append(new LearningEvent(
                "review-safe",
                LearningEvent.Type.REVIEWED,
                "<script>alert(1)</script>",
                "C:\\Users\\alice\\private\\Demo.java",
                "Demo#run\u0000",
                "S1",
                "[click](javascript:alert(1))",
                "![image](javascript:alert(1)) <img src=x>",
                "class SecretSource {}",
                ""));

        Path markdown = tempDir.resolve("safe.md");
        store.exportMarkdown(markdown);
        String text = Files.readString(markdown, StandardCharsets.UTF_8);
        String rawEvents = Files.readString(events, StandardCharsets.UTF_8);

        assertFalse(text.contains("<script>"));
        assertFalse(text.contains("<img"));
        assertFalse(text.contains("](javascript:"));
        assertFalse(text.contains("\u0000"));
        assertFalse(text.contains("class SecretSource"));
        assertTrue(text.contains("&lt;script&gt;"));
        assertTrue(text.contains("\\[click\\]\\(javascript:alert\\(1\\)\\)"));
        assertTrue(text.contains("Demo\\.java"));
        assertFalse(rawEvents.contains("Users"));
        assertFalse(rawEvents.contains("alice"));
    }

    @Test
    void readsLegacyEventsWithoutReviewId() throws Exception {
        Path events = tempDir.resolve("learning-events.jsonl");
        String legacyEvent = """
                {"schemaVersion":1,"eventId":"legacy","occurredAt":"2026-07-01T00:00:00Z",
                "type":"REVIEWED","projectName":"demo","filePath":"src/Demo.java",
                "targetName":"Demo#run","suggestionId":"S1","knowledgePoint":"集合",
                "title":"使用泛型","detail":"List list","outcome":""}
                """.replaceAll("\\R", "");
        Files.writeString(events, legacyEvent + System.lineSeparator());
        LearningEventStore store = new LearningEventStore(events);

        LearningSummary summary = store.summarize();

        assertEquals(1, summary.getReviewedSuggestions());
        assertTrue(summary.getWeakKnowledgePoints().isEmpty());
    }

    @Test
    void returnsOnlyRepeatedKnowledgePointsInDescendingOrder() throws Exception {
        LearningEventStore store =
                new LearningEventStore(tempDir.resolve("learning-events.jsonl"));
        store.append(event("resources-a", LearningEvent.Type.REVIEWED,
                "资源管理", "A", "", ""));
        store.append(event("resources-b", LearningEvent.Type.REVIEWED,
                "资源管理", "B", "", ""));
        store.append(event("resources-c", LearningEvent.Type.REVIEWED,
                "资源管理", "C", "", ""));
        store.append(event("collections-a", LearningEvent.Type.REVIEWED,
                "集合", "D", "", ""));
        store.append(event("collections-b", LearningEvent.Type.REVIEWED,
                "集合", "E", "", ""));
        store.append(event("exceptions-a", LearningEvent.Type.REVIEWED,
                "异常", "F", "", ""));

        List<KnowledgeReminder> reminders =
                store.findRepeatedKnowledgePoints(2, 3);

        assertEquals(2, reminders.size());
        assertEquals("资源管理", reminders.get(0).getKnowledgePoint());
        assertEquals(3, reminders.get(0).getOccurrences());
        assertEquals("集合", reminders.get(1).getKnowledgePoint());
    }

    @Test
    void weakKnowledgePointsExcludeResolvedAndFalsePositiveReviews() throws Exception {
        LearningEventStore store =
                new LearningEventStore(tempDir.resolve("learning-events.jsonl"));
        store.append(event("resolved", LearningEvent.Type.REVIEWED,
                "资源管理", "关闭资源", "", ""));
        store.append(event("resolved", LearningEvent.Type.VERIFICATION_COMPLETED,
                "资源管理", "关闭资源", "", "RESOLVED"));
        store.append(event("false-positive", LearningEvent.Type.REVIEWED,
                "事务边界", "检查事务", "", ""));
        store.append(event("false-positive", LearningEvent.Type.FEEDBACK,
                "事务边界", "检查事务", "", "FALSE_POSITIVE"));
        store.append(event("open-a", LearningEvent.Type.REVIEWED,
                "异常处理", "不要吞异常", "", ""));
        store.append(event("open-b", LearningEvent.Type.REVIEWED,
                "异常处理", "保留异常原因", "", ""));
        store.append(event("low", LearningEvent.Type.REVIEWED,
                "设计偏好", "可选的命名风格", "", "LOW"));

        List<KnowledgeReminder> reminders =
                store.findRepeatedKnowledgePoints(1, 10);
        LearningSummary summary = store.summarize();

        assertEquals(1, reminders.size());
        assertEquals("异常处理", reminders.get(0).getKnowledgePoint());
        assertEquals(2, reminders.get(0).getOccurrences());
        assertEquals(1, summary.getWeakKnowledgePoints().size());
        assertEquals("异常处理",
                summary.getWeakKnowledgePoints().get(0).getKnowledgePoint());
    }

    @Test
    void weakKnowledgePointsCountDistinctReviewsAndNormalizeLabels() throws Exception {
        LearningEventStore store =
                new LearningEventStore(tempDir.resolve("learning-events.jsonl"));
        store.append(event("same-review", "S1", LearningEvent.Type.REVIEWED,
                "Spring 事务", "事务边界 A", "", "MEDIUM"));
        store.append(event("same-review", "S2", LearningEvent.Type.REVIEWED,
                "spring-事务", "事务边界 B", "", "HIGH"));
        store.append(event("other-review", "S1", LearningEvent.Type.REVIEWED,
                "ＳＰＲＩＮＧ事务", "事务边界 C", "", "MEDIUM"));

        List<KnowledgeReminder> reminders =
                store.findRepeatedKnowledgePoints(2, 10);

        assertEquals(1, reminders.size());
        assertEquals("Spring 事务", reminders.get(0).getKnowledgePoint());
        assertEquals(2, reminders.get(0).getOccurrences());
    }

    @Test
    void keepsRepeatedSuggestionIdsFromDifferentReviewsSeparate() throws Exception {
        LearningEventStore store =
                new LearningEventStore(tempDir.resolve("learning-events.jsonl"));
        store.append(event("review-a", LearningEvent.Type.REVIEWED,
                "资源管理", "关闭输入流", "", ""));
        store.append(event("review-a", LearningEvent.Type.VERIFICATION_COMPLETED,
                "资源管理", "关闭输入流", "", "RESOLVED"));
        store.append(event("review-b", LearningEvent.Type.REVIEWED,
                "资源管理", "关闭输出流", "", ""));
        store.append(event("review-b", LearningEvent.Type.FEEDBACK,
                "资源管理", "关闭输出流", "", "HELPFUL"));

        LearningSummary summary = store.summarize();

        assertEquals(2, summary.getReviewedSuggestions());
        assertEquals(1, summary.getCompletedVerifications());
        assertEquals(1, summary.getResolvedVerifications());
        assertEquals(1, summary.getHelpfulFeedback());
        assertEquals(1, summary.getWeakKnowledgePoints().get(0).getOccurrences());
        assertEquals(100, summary.getResolutionRatePercent());
    }

    private static LearningEvent event(LearningEvent.Type type,
                                       String knowledgePoint,
                                       String title,
                                       String detail,
                                       String outcome) {
        return new LearningEvent(
                1,
                "fixed-event-id",
                "2026-07-31T00:00:00Z",
                type,
                "demo",
                "src/Demo.java",
                "Demo#run",
                "S1",
                knowledgePoint,
                title,
                detail,
                outcome);
    }

    private static LearningEvent event(String reviewId,
                                       LearningEvent.Type type,
                                       String knowledgePoint,
                                       String title,
                                       String detail,
                                       String outcome) {
        return new LearningEvent(
                2,
                "event-" + reviewId + "-" + type,
                "2026-07-31T00:00:00Z",
                type,
                reviewId,
                "demo",
                "src/Demo.java",
                "Demo#run",
                "S1",
                knowledgePoint,
                title,
                detail,
                outcome);
    }

    private static LearningEvent event(String reviewId,
                                       String suggestionId,
                                       LearningEvent.Type type,
                                       String knowledgePoint,
                                       String title,
                                       String detail,
                                       String outcome) {
        return new LearningEvent(
                2,
                "event-" + reviewId + "-" + suggestionId + "-" + type,
                "2026-07-31T00:00:00Z",
                type,
                reviewId,
                "demo",
                "src/Demo.java",
                "Demo#run",
                suggestionId,
                knowledgePoint,
                title,
                detail,
                outcome);
    }
}
