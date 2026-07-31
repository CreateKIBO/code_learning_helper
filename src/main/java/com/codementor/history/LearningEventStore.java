package com.codementor.history;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;

/** JSONL 学习事件存储以及 Markdown 复习清单生成。 */
public final class LearningEventStore {

    private final Path eventFile;
    private final Gson gson;

    public LearningEventStore(Path eventFile) {
        this(eventFile, new Gson());
    }

    LearningEventStore(Path eventFile, Gson gson) {
        this.eventFile = eventFile;
        this.gson = gson;
    }

    public synchronized void append(LearningEvent event) throws IOException {
        Path parent = eventFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                eventFile,
                gson.toJson(event) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    public synchronized List<LearningEvent> readAll() throws IOException {
        if (!Files.exists(eventFile)) {
            return List.of();
        }
        List<LearningEvent> events = new ArrayList<>();
        for (String line : Files.readAllLines(eventFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            try {
                LearningEvent event = gson.fromJson(line, LearningEvent.class);
                if (event != null && event.getType() != null) {
                    events.add(event);
                }
            } catch (JsonParseException ignored) {
                // 单行损坏不应导致其余学习记录无法导出。
            }
        }
        return List.copyOf(events);
    }

    public synchronized void exportMarkdown(Path target) throws IOException {
        List<LearningEvent> events = readAll();
        Map<String, ReviewItem> items = aggregate(events);

        StringBuilder markdown = new StringBuilder()
                .append("# CodeMentor 复习清单\n\n")
                .append("> 导出时间：").append(Instant.now()).append("\n")
                .append("> 仅包含问题摘要和学习结果，不包含完整源代码。\n\n");

        if (items.isEmpty()) {
            markdown.append("暂时没有学习记录。\n");
        } else {
            Map<String, List<ReviewItem>> byKnowledgePoint = new LinkedHashMap<>();
            for (ReviewItem item : items.values()) {
                byKnowledgePoint.computeIfAbsent(item.knowledgePoint(), ignored -> new ArrayList<>())
                        .add(item);
            }
            for (Map.Entry<String, List<ReviewItem>> group : byKnowledgePoint.entrySet()) {
                markdown.append("## ").append(markdownText(group.getKey())).append("\n\n");
                for (ReviewItem item : group.getValue()) {
                    appendItem(markdown, item);
                }
            }
        }

        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                target,
                markdown.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    public synchronized List<KnowledgeReminder> findRepeatedKnowledgePoints(
            int minimumOccurrences, int limit) throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (LearningEvent event : readAll()) {
            if (event.getType() != LearningEvent.Type.REVIEWED) continue;
            String knowledgePoint = event.getKnowledgePoint().isBlank()
                    ? "未分类"
                    : event.getKnowledgePoint();
            counts.merge(knowledgePoint, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() >= minimumOccurrences)
                .sorted(Map.Entry.<String, Integer>comparingByValue(
                        Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(entry -> new KnowledgeReminder(entry.getKey(), entry.getValue()))
                .toList();
    }

    public synchronized LearningSummary summarize() throws IOException {
        Map<String, ReviewItem> items = aggregate(readAll());
        int explanationViews = 0;
        int completedVerifications = 0;
        int resolvedVerifications = 0;
        int helpfulFeedback = 0;
        int notHelpfulFeedback = 0;
        int falsePositiveFeedback = 0;
        Map<String, Integer> weakKnowledgePoints = new LinkedHashMap<>();

        for (ReviewItem item : items.values()) {
            if (item.explanationViewed) explanationViews++;
            if (!item.verification.isBlank()) {
                completedVerifications++;
                if ("RESOLVED".equals(item.verification)) {
                    resolvedVerifications++;
                }
            }
            switch (item.feedback) {
                case "HELPFUL" -> helpfulFeedback++;
                case "NOT_HELPFUL" -> notHelpfulFeedback++;
                case "FALSE_POSITIVE" -> falsePositiveFeedback++;
                default -> { }
            }
            if (!"RESOLVED".equals(item.verification)
                    && !"FALSE_POSITIVE".equals(item.feedback)) {
                weakKnowledgePoints.merge(item.knowledgePoint, 1, Integer::sum);
            }
        }

        List<KnowledgeReminder> weakPoints = weakKnowledgePoints.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(
                        Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(5)
                .map(entry -> new KnowledgeReminder(entry.getKey(), entry.getValue()))
                .toList();
        return new LearningSummary(
                items.size(),
                explanationViews,
                completedVerifications,
                resolvedVerifications,
                helpfulFeedback,
                notHelpfulFeedback,
                falsePositiveFeedback,
                weakPoints);
    }

    private static Map<String, ReviewItem> aggregate(List<LearningEvent> events) {
        Map<String, ReviewItem> items = new LinkedHashMap<>();
        for (LearningEvent event : events) {
            String key = event.reviewKey();
            if (event.getType() == LearningEvent.Type.REVIEWED) {
                items.put(key, ReviewItem.from(event));
                continue;
            }
            ReviewItem item = items.get(key);
            if (item == null) continue;
            switch (event.getType()) {
                case EXPLANATION_VIEWED:
                    item.explanationViewed = true;
                    break;
                case FEEDBACK:
                    item.feedback = event.getOutcome();
                    break;
                case VERIFICATION_COMPLETED:
                    item.verification = event.getOutcome();
                    item.verificationEvidence = event.getDetail();
                    break;
                default:
                    break;
            }
        }
        return items;
    }

    private static void appendItem(StringBuilder markdown, ReviewItem item) {
        boolean resolved = "RESOLVED".equals(item.verification);
        markdown.append("- [").append(resolved ? "x" : " ").append("] ")
                .append(markdownText(item.title))
                .append("（").append(markdownText(item.targetName)).append("）\n")
                .append("  - 项目/文件：").append(markdownText(item.projectName))
                .append(" / ").append(markdownText(item.filePath)).append("\n")
                .append("  - 首次记录：").append(markdownText(item.occurredAt)).append("\n");
        if (!item.detail.isBlank()) {
            markdown.append("  - 问题摘要：").append(markdownText(item.detail)).append("\n");
        }
        if (item.explanationViewed) {
            markdown.append("  - 已查看讲解\n");
        }
        if (!item.feedback.isBlank()) {
            markdown.append("  - 反馈：").append(markdownText(item.feedback)).append("\n");
        }
        if (!item.verification.isBlank()) {
            markdown.append("  - 修改验证：").append(markdownText(item.verification));
            if (!item.verificationEvidence.isBlank()) {
                markdown.append(" — ").append(markdownText(item.verificationEvidence));
            }
            markdown.append("\n");
        }
        markdown.append("\n");
    }

    private static String markdownText(String value) {
        if (value == null || value.isBlank()) return "未分类";
        return value.replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    private static final class ReviewItem {
        private final String occurredAt;
        private final String projectName;
        private final String filePath;
        private final String targetName;
        private final String knowledgePoint;
        private final String title;
        private final String detail;
        private boolean explanationViewed;
        private String feedback = "";
        private String verification = "";
        private String verificationEvidence = "";

        private ReviewItem(String occurredAt, String projectName, String filePath,
                           String targetName, String knowledgePoint, String title,
                           String detail) {
            this.occurredAt = occurredAt;
            this.projectName = projectName;
            this.filePath = filePath;
            this.targetName = targetName;
            this.knowledgePoint = knowledgePoint;
            this.title = title;
            this.detail = detail;
        }

        static ReviewItem from(LearningEvent event) {
            return new ReviewItem(
                    event.getOccurredAt(),
                    event.getProjectName(),
                    event.getFilePath(),
                    event.getTargetName(),
                    event.getKnowledgePoint().isBlank() ? "未分类" : event.getKnowledgePoint(),
                    event.getTitle(),
                    event.getDetail());
        }

        String knowledgePoint() {
            return knowledgePoint;
        }
    }
}
