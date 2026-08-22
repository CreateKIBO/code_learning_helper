package com.codementor.review;

import com.codementor.model.ModelRequestConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 小型内存 LRU 缓存；键包含代码指纹和模型配置，不会跨配置复用结果。 */
public final class ReviewCache {

    private final int capacity;
    private final Map<CacheKey, CachedReview> entries;

    public record CachedReview(ReviewResult result, String learningReviewId) {
        public CachedReview {
            if (result == null) throw new IllegalArgumentException("result 不能为空");
            learningReviewId = learningReviewId == null ? "" : learningReviewId;
        }
    }

    public ReviewCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity 必须大于 0");
        }
        this.capacity = capacity;
        this.entries = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(
                    Map.Entry<CacheKey, CachedReview> eldest) {
                return size() > ReviewCache.this.capacity;
            }
        };
    }

    public synchronized Optional<CachedReview> get(
            ReviewContext context, ModelRequestConfig config) {
        return Optional.ofNullable(entries.get(key(context, config)));
    }

    public synchronized void put(
            ReviewContext context,
            ModelRequestConfig config,
            ReviewResult result,
            String learningReviewId) {
        entries.put(
                key(context, config),
                new CachedReview(result, learningReviewId));
    }

    void put(
            ReviewContext context,
            ModelRequestConfig config,
            ReviewResult result) {
        put(context, config, result, "");
    }

    public synchronized void clear() {
        entries.clear();
    }

    private static CacheKey key(
            ReviewContext context, ModelRequestConfig config) {
        return new CacheKey(
                context.getFilePath(),
                context.getTargetName(),
                context.getTargetIdentity(),
                context.getFingerprint(),
                context.getReviewMode().name(),
                context.getJavaWebContext().getRole().name(),
                context.getJavaWebContext().getReviewFocus(),
                List.copyOf(context.getJavaWebContext().getConcerns()),
                context.getProjectRules(),
                context.getModelContext(),
                List.copyOf(context.getTechnologyTags()),
                List.copyOf(context.getDiagnostics()),
                config.endpoint(),
                config.model(),
                config.teachingLevel().name(),
                config.promptSchemaVersion());
    }

    private record CacheKey(
            String filePath,
            String targetName,
            String targetIdentity,
            String codeFingerprint,
            String reviewMode,
            String javaWebRole,
            String javaWebFocus,
            List<String> javaWebConcerns,
            String projectRules,
            String modelContext,
            List<String> technologyTags,
            List<String> diagnostics,
            String endpoint,
            String model,
            String teachingLevel,
            String promptSchemaVersion) {}
}
