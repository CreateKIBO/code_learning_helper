package com.codementor.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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

        assertEquals(1, store.readAll().size());
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
        assertEquals("集合", summary.getWeakKnowledgePoints().get(0).getKnowledgePoint());
    }

    @Test
    void returnsOnlyRepeatedKnowledgePointsInDescendingOrder() throws Exception {
        LearningEventStore store =
                new LearningEventStore(tempDir.resolve("learning-events.jsonl"));
        store.append(event(LearningEvent.Type.REVIEWED, "资源管理", "A", "", ""));
        store.append(event(LearningEvent.Type.REVIEWED, "资源管理", "B", "", ""));
        store.append(event(LearningEvent.Type.REVIEWED, "资源管理", "C", "", ""));
        store.append(event(LearningEvent.Type.REVIEWED, "集合", "D", "", ""));
        store.append(event(LearningEvent.Type.REVIEWED, "集合", "E", "", ""));
        store.append(event(LearningEvent.Type.REVIEWED, "异常", "F", "", ""));

        List<KnowledgeReminder> reminders =
                store.findRepeatedKnowledgePoints(2, 3);

        assertEquals(2, reminders.size());
        assertEquals("资源管理", reminders.get(0).getKnowledgePoint());
        assertEquals(3, reminders.get(0).getOccurrences());
        assertEquals("集合", reminders.get(1).getKnowledgePoint());
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
}
