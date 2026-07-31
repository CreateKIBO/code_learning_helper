package com.codementor.history;

import java.time.Instant;
import java.util.UUID;

/**
 * 一条追加式学习事件。只保存问题摘要和学习结果，不保存完整源代码。
 */
public final class LearningEvent {

    public enum Type {
        REVIEWED,
        EXPLANATION_VIEWED,
        FEEDBACK,
        VERIFICATION_COMPLETED
    }

    private final int schemaVersion;
    private final String eventId;
    /** 同一次模型评审的稳定关联 ID；旧事件为空时使用兼容键。 */
    private final String reviewId;
    private final String occurredAt;
    private final Type type;
    private final String projectName;
    private final String filePath;
    private final String targetName;
    private final String suggestionId;
    private final String knowledgePoint;
    private final String title;
    private final String detail;
    private final String outcome;

    public LearningEvent(Type type,
                         String projectName,
                         String filePath,
                         String targetName,
                         String suggestionId,
                         String knowledgePoint,
                         String title,
                         String detail,
                         String outcome) {
        this(1, UUID.randomUUID().toString(), Instant.now().toString(), type,
                "",
                projectName, filePath, targetName, suggestionId, knowledgePoint,
                title, detail, outcome);
    }

    public LearningEvent(String reviewId,
                         Type type,
                         String projectName,
                         String filePath,
                         String targetName,
                         String suggestionId,
                         String knowledgePoint,
                         String title,
                         String detail,
                         String outcome) {
        this(2, UUID.randomUUID().toString(), Instant.now().toString(), type,
                reviewId,
                projectName, filePath, targetName, suggestionId, knowledgePoint,
                title, detail, outcome);
    }

    LearningEvent(int schemaVersion,
                  String eventId,
                  String occurredAt,
                  Type type,
                  String projectName,
                  String filePath,
                  String targetName,
                  String suggestionId,
                  String knowledgePoint,
                  String title,
                  String detail,
                  String outcome) {
        this(schemaVersion, eventId, occurredAt, type, "",
                projectName, filePath, targetName, suggestionId,
                knowledgePoint, title, detail, outcome);
    }

    LearningEvent(int schemaVersion,
                  String eventId,
                  String occurredAt,
                  Type type,
                  String reviewId,
                  String projectName,
                  String filePath,
                  String targetName,
                  String suggestionId,
                  String knowledgePoint,
                  String title,
                  String detail,
                  String outcome) {
        this.schemaVersion = schemaVersion;
        this.eventId = safe(eventId);
        this.reviewId = safe(reviewId);
        this.occurredAt = safe(occurredAt);
        this.type = type;
        this.projectName = safe(projectName);
        this.filePath = safe(filePath);
        this.targetName = safe(targetName);
        this.suggestionId = safe(suggestionId);
        this.knowledgePoint = safe(knowledgePoint);
        this.title = safe(title);
        this.detail = safe(detail);
        this.outcome = safe(outcome);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public int getSchemaVersion() { return schemaVersion; }
    public String getEventId() { return eventId; }
    public String getReviewId() { return safe(reviewId); }
    public String getOccurredAt() { return occurredAt; }
    public Type getType() { return type; }
    public String getProjectName() { return projectName; }
    public String getFilePath() { return filePath; }
    public String getTargetName() { return targetName; }
    public String getSuggestionId() { return suggestionId; }
    public String getKnowledgePoint() { return knowledgePoint; }
    public String getTitle() { return title; }
    public String getDetail() { return detail; }
    public String getOutcome() { return outcome; }

    String reviewKey() {
        if (reviewId != null && !reviewId.isBlank()) {
            return reviewId + "|" + suggestionId;
        }
        return String.join("|", projectName, filePath, targetName, suggestionId);
    }
}
