package com.codementor.history;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 一条追加式学习事件。只保存问题摘要和学习结果，不保存完整源代码。
 */
public final class LearningEvent {

    private static final int MAX_ID_LENGTH = 256;
    private static final int MAX_NAME_LENGTH = 512;
    private static final int MAX_PATH_LENGTH = 1_024;
    private static final int MAX_DETAIL_LENGTH = 2_048;
    private static final Pattern WINDOWS_ABSOLUTE_PATH =
            Pattern.compile("^[A-Za-z]:/.*");
    private static final Pattern URI_LIKE_PATH =
            Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.*");

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
        this.eventId = normalize(eventId, MAX_ID_LENGTH);
        this.reviewId = normalize(reviewId, MAX_ID_LENGTH);
        this.occurredAt = normalize(occurredAt, MAX_ID_LENGTH);
        this.type = type;
        this.projectName = normalize(projectName, MAX_NAME_LENGTH);
        this.filePath = privacySafeFilePath(filePath);
        this.targetName = normalize(targetName, MAX_NAME_LENGTH);
        this.suggestionId = normalize(suggestionId, MAX_ID_LENGTH);
        this.knowledgePoint = normalize(knowledgePoint, MAX_NAME_LENGTH);
        this.title = normalize(title, MAX_NAME_LENGTH);
        this.detail = normalize(detail, MAX_DETAIL_LENGTH);
        this.outcome = normalize(outcome, MAX_NAME_LENGTH);
    }

    private static String normalize(String value, int maxLength) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(Math.min(value.length(), maxLength));
        for (int index = 0; index < value.length() && normalized.length() < maxLength; index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character)
                    || Character.getType(character) == Character.FORMAT) {
                normalized.append(' ');
            } else {
                normalized.append(character);
            }
        }
        return normalized.toString().trim();
    }

    /**
     * 历史文件只允许项目相对路径。旧记录或异常调用传入绝对路径时，
     * 最多保留文件名，避免在 JSONL 和导出内容中暴露本机目录。
     */
    private static String privacySafeFilePath(String value) {
        String path = normalize(value, MAX_PATH_LENGTH).replace('\\', '/');
        if (path.isBlank()) {
            return "";
        }
        boolean absolute = path.startsWith("/")
                || path.startsWith("~/")
                || WINDOWS_ABSOLUTE_PATH.matcher(path).matches()
                || URI_LIKE_PATH.matcher(path).matches()
                || path.equals("..")
                || path.startsWith("../")
                || path.contains("/../");
        if (!absolute) {
            while (path.startsWith("./")) {
                path = path.substring(2);
            }
            return path;
        }
        int lastSeparator = path.lastIndexOf('/');
        return lastSeparator >= 0 && lastSeparator + 1 < path.length()
                ? path.substring(lastSeparator + 1)
                : "未知文件";
    }

    public int getSchemaVersion() { return schemaVersion; }
    public String getEventId() { return normalize(eventId, MAX_ID_LENGTH); }
    public String getReviewId() { return normalize(reviewId, MAX_ID_LENGTH); }
    public String getOccurredAt() { return normalize(occurredAt, MAX_ID_LENGTH); }
    public Type getType() { return type; }
    public String getProjectName() { return normalize(projectName, MAX_NAME_LENGTH); }
    public String getFilePath() { return privacySafeFilePath(filePath); }
    public String getTargetName() { return normalize(targetName, MAX_NAME_LENGTH); }
    public String getSuggestionId() { return normalize(suggestionId, MAX_ID_LENGTH); }
    public String getKnowledgePoint() { return normalize(knowledgePoint, MAX_NAME_LENGTH); }
    public String getTitle() { return normalize(title, MAX_NAME_LENGTH); }
    public String getDetail() { return normalize(detail, MAX_DETAIL_LENGTH); }
    public String getOutcome() { return normalize(outcome, MAX_NAME_LENGTH); }

    LearningEvent sanitizedCopy() {
        return new LearningEvent(
                schemaVersion,
                getEventId(),
                getOccurredAt(),
                type,
                getReviewId(),
                getProjectName(),
                getFilePath(),
                getTargetName(),
                getSuggestionId(),
                getKnowledgePoint(),
                getTitle(),
                getDetail(),
                getOutcome());
    }

    String reviewKey() {
        if (!getReviewId().isBlank()) {
            return "review|" + getReviewId() + "|" + getSuggestionId();
        }
        String legacyKey = String.join("|", getProjectName(), getFilePath(),
                getTargetName(), getSuggestionId());
        if (!legacyKey.replace("|", "").isBlank()) {
            return "legacy|" + legacyKey;
        }
        return getEventId().isBlank() ? "" : "event|" + getEventId();
    }
}
