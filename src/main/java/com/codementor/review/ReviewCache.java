package com.codementor.review;

import com.codementor.settings.CodeMentorSettings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 小型内存 LRU 缓存；键包含代码指纹和模型配置，不会跨配置复用结果。 */
public final class ReviewCache {

    private final int capacity;
    private final Map<CacheKey, List<Suggestion>> entries;

    public ReviewCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity 必须大于 0");
        }
        this.capacity = capacity;
        this.entries = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(
                    Map.Entry<CacheKey, List<Suggestion>> eldest) {
                return size() > ReviewCache.this.capacity;
            }
        };
    }

    public synchronized Optional<List<Suggestion>> get(
            ReviewContext context, CodeMentorSettings settings) {
        return get(context, settings.getModel(), settings.getTeachingLevel());
    }

    public synchronized Optional<List<Suggestion>> get(
            ReviewContext context,
            String model,
            CodeMentorSettings.TeachingLevel teachingLevel) {
        return Optional.ofNullable(entries.get(key(context, model, teachingLevel)));
    }

    public synchronized void put(
            ReviewContext context,
            CodeMentorSettings settings,
            List<Suggestion> suggestions) {
        put(context, settings.getModel(), settings.getTeachingLevel(), suggestions);
    }

    public synchronized void put(
            ReviewContext context,
            String model,
            CodeMentorSettings.TeachingLevel teachingLevel,
            List<Suggestion> suggestions) {
        entries.put(key(context, model, teachingLevel), List.copyOf(suggestions));
    }

    public synchronized boolean contains(
            ReviewContext context, CodeMentorSettings settings) {
        return get(context, settings).isPresent();
    }

    private static CacheKey key(
            ReviewContext context,
            String model,
            CodeMentorSettings.TeachingLevel teachingLevel) {
        return new CacheKey(
                context.getFilePath(),
                context.getTargetName(),
                context.getFingerprint(),
                context.getReviewMode().name(),
                context.getJavaWebContext().getRole().name(),
                context.getProjectRules(),
                model,
                teachingLevel.name());
    }

    private record CacheKey(
            String filePath,
            String targetName,
            String codeFingerprint,
            String reviewMode,
            String javaWebRole,
            String projectRules,
            String model,
            String teachingLevel) {}
}
