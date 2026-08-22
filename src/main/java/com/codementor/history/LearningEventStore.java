package com.codementor.history;

import com.codementor.learning.KnowledgePointNormalizer;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** JSONL 学习事件存储以及 Markdown 复习清单生成。 */
public final class LearningEventStore {

    static final long DEFAULT_MAX_FILE_BYTES = 4L * 1024L * 1024L;
    static final int DEFAULT_MAX_LINE_CHARS = 32 * 1024;
    static final int DEFAULT_MAX_EVENTS = 20_000;

    private static final String UNCATEGORIZED = "未分类";
    private static final int COPY_BUFFER_BYTES = 8 * 1024;

    private final Path eventFile;
    private final Path rotatedFile;
    private final Path rotationTempFile;
    private final Gson gson;
    private final long maxFileBytes;
    private final int maxLineChars;
    private final int maxEvents;

    public LearningEventStore(Path eventFile) {
        this(eventFile, new Gson());
    }

    LearningEventStore(Path eventFile, Gson gson) {
        this(eventFile, gson, DEFAULT_MAX_FILE_BYTES,
                DEFAULT_MAX_LINE_CHARS, DEFAULT_MAX_EVENTS);
    }

    LearningEventStore(Path eventFile,
                       Gson gson,
                       long maxFileBytes,
                       int maxLineChars,
                       int maxEvents) {
        this.eventFile = Objects.requireNonNull(eventFile, "eventFile");
        this.gson = Objects.requireNonNull(gson, "gson");
        if (maxFileBytes <= 0 || maxLineChars <= 0 || maxEvents <= 0) {
            throw new IllegalArgumentException("学习历史容量限制必须为正数");
        }
        this.maxFileBytes = maxFileBytes;
        this.maxLineChars = maxLineChars;
        this.maxEvents = maxEvents;
        this.rotatedFile = Path.of(eventFile.toString() + ".1");
        this.rotationTempFile = Path.of(eventFile.toString() + ".rotation.tmp");
    }

    public synchronized void append(LearningEvent event) throws IOException {
        LearningEvent safeEvent = Objects.requireNonNull(event, "event").sanitizedCopy();
        String json = gson.toJson(safeEvent);
        byte[] line = (json + "\n").getBytes(StandardCharsets.UTF_8);
        if (json.length() > maxLineChars || line.length > maxFileBytes) {
            throw new IOException("单条学习记录超过容量限制");
        }

        Path parent = eventFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        rotateBeforeAppend(line.length);
        Files.write(
                eventFile,
                line,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    /** 返回按时间顺序排列的最近事件，数量和读取字节数均有上限。 */
    public synchronized List<LearningEvent> readAll() throws IOException {
        Deque<LearningEvent> recentEvents = new ArrayDeque<>();
        visitEvents(event -> {
            if (recentEvents.size() == maxEvents) {
                recentEvents.removeFirst();
            }
            recentEvents.addLast(event);
        });
        return List.copyOf(recentEvents);
    }

    public synchronized void clear() throws IOException {
        Files.deleteIfExists(eventFile);
        Files.deleteIfExists(rotatedFile);
        Files.deleteIfExists(rotationTempFile);
    }

    public synchronized void exportMarkdown(Path target) throws IOException {
        Map<String, ReviewItem> items = aggregate(readAll());
        Map<String, List<ReviewItem>> byKnowledgePoint = new LinkedHashMap<>();
        for (ReviewItem item : items.values()) {
            byKnowledgePoint.computeIfAbsent(item.knowledgePoint(), ignored -> new ArrayList<>())
                    .add(item);
        }

        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter markdown = Files.newBufferedWriter(
                target,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            markdown.write("# CodeMentor 复习清单\n\n");
            markdown.write("> 导出时间：" + Instant.now() + "\n");
            markdown.write("> 仅包含问题摘要和学习结果，不包含完整源代码或模型证据。\n\n");

            if (items.isEmpty()) {
                markdown.write("暂时没有学习记录。\n");
                return;
            }
            for (Map.Entry<String, List<ReviewItem>> group : byKnowledgePoint.entrySet()) {
                markdown.write("## " + markdownText(group.getKey()) + "\n\n");
                for (ReviewItem item : group.getValue()) {
                    appendItem(markdown, item);
                }
            }
        }
    }

    public synchronized List<KnowledgeReminder> findRepeatedKnowledgePoints(
            int minimumOccurrences, int limit) throws IOException {
        if (minimumOccurrences < 1 || limit <= 0) {
            return List.of();
        }
        return weakKnowledgePoints(
                aggregate(readAll()), minimumOccurrences, limit);
    }

    public synchronized LearningSummary summarize() throws IOException {
        Map<String, ReviewItem> items = aggregate(readAll());
        int explanationViews = 0;
        int completedVerifications = 0;
        int resolvedVerifications = 0;
        int helpfulFeedback = 0;
        int notHelpfulFeedback = 0;
        int falsePositiveFeedback = 0;

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
        }

        List<KnowledgeReminder> weakPoints = weakKnowledgePoints(items, 1, 5);
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

    private static List<KnowledgeReminder> weakKnowledgePoints(
            Map<String, ReviewItem> items, int minimumOccurrences, int limit) {
        Map<String, KnowledgeAccumulator> counts = new LinkedHashMap<>();
        for (ReviewItem item : items.values()) {
            if (!"RESOLVED".equals(item.verification)
                    && !"FALSE_POSITIVE".equals(item.feedback)
                    && !"LOW".equals(item.certainty)
                    && item.reliableReviewScope) {
                String key = KnowledgePointNormalizer.key(item.knowledgePoint);
                if (key.isBlank()) key = KnowledgePointNormalizer.key(UNCATEGORIZED);
                counts.computeIfAbsent(
                                key,
                                ignored -> new KnowledgeAccumulator(item.knowledgePoint))
                        .reviewScopes.add(item.reviewScope);
            }
        }
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue().occurrences() >= minimumOccurrences)
                .sorted(Comparator
                        .<Map.Entry<String, KnowledgeAccumulator>>comparingInt(
                                entry -> entry.getValue().occurrences())
                        .reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(entry -> new KnowledgeReminder(
                        entry.getValue().label,
                        entry.getValue().occurrences()))
                .toList();
    }

    private void rotateBeforeAppend(int newEventBytes) throws IOException {
        if (!Files.exists(eventFile)) {
            return;
        }
        long currentBytes = Files.size(eventFile);
        if (currentBytes == 0
                || currentBytes <= maxFileBytes - newEventBytes) {
            return;
        }

        if (currentBytes <= maxFileBytes) {
            replaceFile(eventFile, rotatedFile);
            return;
        }

        // 兼容升级前或外部损坏造成的超大文件：只保留有界尾部，再删除原文件。
        copyBoundedTail(eventFile, rotationTempFile);
        try {
            replaceFile(rotationTempFile, rotatedFile);
            Files.delete(eventFile);
        } finally {
            Files.deleteIfExists(rotationTempFile);
        }
    }

    private void copyBoundedTail(Path source, Path target) throws IOException {
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel output = FileChannel.open(
                     target,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE)) {
            long remainingBytes = Math.min(input.size(), maxFileBytes);
            input.position(input.size() - remainingBytes);
            ByteBuffer buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES);
            while (remainingBytes > 0) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), remainingBytes));
                int bytesRead = input.read(buffer);
                if (bytesRead < 0) {
                    break;
                }
                if (bytesRead == 0) {
                    continue;
                }
                remainingBytes -= bytesRead;
                buffer.flip();
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
            }
            output.force(true);
        }
    }

    private static void replaceFile(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            if (!Files.exists(source)) {
                if (Files.exists(target)) {
                    return;
                }
                throw atomicFailure;
            }
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException replacementFailure) {
                replacementFailure.addSuppressed(atomicFailure);
                throw replacementFailure;
            }
        }
    }

    private void visitEvents(EventVisitor visitor) throws IOException {
        visitFile(rotatedFile, visitor);
        visitFile(eventFile, visitor);
    }

    private void visitFile(Path source, EventVisitor visitor) throws IOException {
        if (!Files.exists(source)) {
            return;
        }
        long sourceBytes = Files.size(source);
        long startOffset = Math.max(0L, sourceBytes - maxFileBytes);
        try (SeekableByteChannel channel = Files.newByteChannel(
                source, StandardOpenOption.READ)) {
            channel.position(startOffset);
            try (BufferedReader reader = new BufferedReader(
                    Channels.newReader(
                            channel,
                            StandardCharsets.UTF_8.newDecoder()
                                    .onMalformedInput(CodingErrorAction.REPLACE)
                                    .onUnmappableCharacter(CodingErrorAction.REPLACE),
                            -1))) {
                if (startOffset > 0) {
                    reader.readLine(); // 丢弃从文件中部开始的不完整 JSONL 行。
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || line.length() > maxLineChars) {
                        continue;
                    }
                    try {
                        LearningEvent event = gson.fromJson(line, LearningEvent.class);
                        if (event != null && event.getType() != null) {
                            visitor.accept(event);
                        }
                    } catch (JsonParseException | IllegalStateException ignored) {
                        // 单行损坏或字段类型错误不应影响其余学习记录。
                    }
                }
            }
        }
    }

    private static Map<String, ReviewItem> aggregate(List<LearningEvent> events) {
        Map<String, ReviewItem> items = new LinkedHashMap<>();
        int unlinkedEvent = 0;
        for (LearningEvent event : events) {
            String key = event.reviewKey();
            if (event.getType() == LearningEvent.Type.REVIEWED) {
                if (key.isBlank()) {
                    key = "unlinked|" + unlinkedEvent++;
                }
                items.put(key, ReviewItem.from(event, key));
                continue;
            }
            if (key.isBlank()) {
                continue;
            }
            ReviewItem item = items.get(key);
            if (item == null) continue;
            switch (event.getType()) {
                case EXPLANATION_VIEWED -> item.explanationViewed = true;
                case FEEDBACK -> item.feedback = event.getOutcome();
                case VERIFICATION_COMPLETED -> item.verification = event.getOutcome();
                default -> { }
            }
        }
        return items;
    }

    private static void appendItem(BufferedWriter markdown, ReviewItem item) throws IOException {
        boolean resolved = "RESOLVED".equals(item.verification);
        markdown.write("- [" + (resolved ? "x" : " ") + "] ");
        markdown.write(markdownText(item.title));
        markdown.write("（" + markdownText(item.targetName) + "）\n");
        markdown.write("  - 项目/文件：" + markdownText(item.projectName));
        markdown.write(" / " + markdownText(item.filePath) + "\n");
        markdown.write("  - 首次记录：" + markdownText(item.occurredAt) + "\n");
        if (item.explanationViewed) {
            markdown.write("  - 已查看讲解\n");
        }
        if (!item.feedback.isBlank()) {
            markdown.write("  - 反馈：" + markdownText(item.feedback) + "\n");
        }
        if (!item.verification.isBlank()) {
            markdown.write("  - 修改验证：" + markdownText(item.verification) + "\n");
        }
        markdown.write("\n");
    }

    /** 对外部文本同时做 HTML、CommonMark 链接语法和控制字符转义。 */
    private static String markdownText(String value) {
        if (value == null || value.isBlank()) return UNCATEGORIZED;
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character)
                    || Character.getType(character) == Character.FORMAT) {
                escaped.append(' ');
                continue;
            }
            switch (character) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                case '\\', '`', '*', '_', '{', '}', '[', ']', '(', ')',
                        '#', '+', '-', '.', '!', '|', '~', '^', '=' ->
                        escaped.append('\\').append(character);
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private static String valueOrUncategorized(String value) {
        return value == null || value.isBlank() ? UNCATEGORIZED : value;
    }

    @FunctionalInterface
    private interface EventVisitor {
        void accept(LearningEvent event);
    }

    private static final class ReviewItem {
        private final String reviewScope;
        private final String occurredAt;
        private final String projectName;
        private final String filePath;
        private final String targetName;
        private final String knowledgePoint;
        private final String title;
        private final String certainty;
        private final boolean reliableReviewScope;
        private boolean explanationViewed;
        private String feedback = "";
        private String verification = "";

        private ReviewItem(String reviewScope,
                           String occurredAt,
                           String projectName,
                           String filePath,
                           String targetName,
                           String knowledgePoint,
                           String title,
                           String certainty,
                           boolean reliableReviewScope) {
            this.reviewScope = reviewScope;
            this.occurredAt = occurredAt;
            this.projectName = projectName;
            this.filePath = filePath;
            this.targetName = targetName;
            this.knowledgePoint = knowledgePoint;
            this.title = title;
            this.certainty = certainty;
            this.reliableReviewScope = reliableReviewScope;
        }

        static ReviewItem from(LearningEvent event, String aggregateKey) {
            String reviewScope = event.getReviewId().isBlank()
                    ? aggregateKey
                    : event.getReviewId();
            return new ReviewItem(
                    reviewScope,
                    event.getOccurredAt(),
                    event.getProjectName(),
                    event.getFilePath(),
                    event.getTargetName(),
                    valueOrUncategorized(event.getKnowledgePoint()),
                    event.getTitle(),
                    event.getOutcome(),
                    !event.getReviewId().isBlank());
        }

        String knowledgePoint() {
            return knowledgePoint;
        }
    }

    private static final class KnowledgeAccumulator {
        private final String label;
        private final Set<String> reviewScopes = new LinkedHashSet<>();

        private KnowledgeAccumulator(String label) {
            this.label = valueOrUncategorized(label);
        }

        private int occurrences() {
            return reviewScopes.size();
        }
    }
}
