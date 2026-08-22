package com.codementor.review;

import com.codementor.javaweb.JavaWebContext;
import com.codementor.model.ModelRequestConfig;
import com.codementor.settings.CodeMentorSettings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewCacheAndDeduplicationTest {

    @Test
    void removesDuplicateSuggestionsAndReassignsStableIds() {
        Suggestion first = suggestion("old-a", "资源管理", "关闭资源");
        Suggestion duplicate = suggestion("old-b", "资源管理", "关闭资源");
        Suggestion other = suggestion("old-c", "异常", "不要吞异常");

        List<Suggestion> result =
                SuggestionDeduplicator.deduplicate(List.of(first, duplicate, other));

        assertEquals(2, result.size());
        assertEquals("S1", result.get(0).getId());
        assertEquals("S2", result.get(1).getId());
    }

    @Test
    void keepsNewLearningFieldsWhenSuggestionsAreDeduplicated() {
        Suggestion first = new Suggestion.Builder()
                .id("old-a")
                .category(Suggestion.Category.JAVA_USAGE)
                .certainty(Suggestion.Certainty.MEDIUM)
                .title("关闭资源")
                .knowledgePoint("资源管理")
                .verificationCriteria("所有路径都会关闭资源")
                .nextStep("先把资源创建移动到 try 的括号中")
                .selfCheckQuestion("为什么异常路径也需要关闭资源？")
                .build();
        Suggestion duplicate = suggestion("old-b", "资源管理", "关闭资源");

        Suggestion retained = SuggestionDeduplicator
                .deduplicate(List.of(first, duplicate))
                .get(0);

        assertEquals("S1", retained.getId());
        assertEquals("先把资源创建移动到 try 的括号中", retained.getNextStep());
        assertEquals("为什么异常路径也需要关闭资源？", retained.getSelfCheckQuestion());
    }

    @Test
    void cacheSeparatesCodeFingerprintsAndModelConfigurations() {
        ReviewCache cache = new ReviewCache(2);
        ReviewContext first = context("fingerprint-a");
        ReviewContext second = context("fingerprint-b");
        ReviewResult result = reviewResult("集合类型需要更明确", "S1", "集合", "使用泛型");

        ModelRequestConfig modelA = config("https://a.example/chat");
        ModelRequestConfig modelB = config(
                "https://a.example/chat", "model-b",
                CodeMentorSettings.TeachingLevel.JAVAWEB);
        cache.put(first, modelA, result);

        assertTrue(cache.get(first, modelA).isPresent());
        assertTrue(cache.get(second, modelA).isEmpty());
        assertTrue(cache.get(first, modelB).isEmpty());
    }

    @Test
    void cacheInvalidatesWhenProjectLearningRulesChange() {
        ReviewCache cache = new ReviewCache(2);
        ReviewContext oldRules = context("same", "Controller 只做参数接收");
        ReviewContext newRules = context("same", "Controller 可以编排简单业务");
        ReviewResult result = reviewResult("检查当前分层职责", "S1", "分层", "保持轻薄");

        ModelRequestConfig config = config("https://a.example/chat");
        cache.put(oldRules, config, result);

        assertTrue(cache.get(newRules, config).isEmpty());
    }

    @Test
    void cacheDoesNotMixReviewAndExplanationModes() {
        ReviewCache cache = new ReviewCache(2);
        ReviewContext review = context("same", "");
        ReviewContext explanation = review.withReviewMode(ReviewMode.EXPLAIN);
        ReviewResult result = reviewResult("解释当前执行流程", "S1", "流程", "理解流程");

        ModelRequestConfig config = config(
                "https://a.example/chat", "model-a",
                CodeMentorSettings.TeachingLevel.BEGINNER);
        cache.put(review, config, result);

        assertTrue(cache.get(explanation, config).isEmpty());
    }

    @Test
    void cacheIncludesEndpointAndFullModelContext() {
        ReviewCache cache = new ReviewCache(3);
        ReviewContext original = contextWithModelContext("same", "imports A; class Demo {}");
        ReviewContext changedContext = contextWithModelContext(
                "same", "imports B; class Demo {}");
        ModelRequestConfig endpointA = config("https://a.example/chat");
        ModelRequestConfig endpointB = config("https://b.example/chat");
        ReviewResult result = reviewResult("导入会改变可见上下文", "S1", "上下文", "检查导入");

        cache.put(original, endpointA, result);

        assertTrue(cache.get(original, endpointA).isPresent());
        assertTrue(cache.get(changedContext, endpointA).isEmpty());
        assertTrue(cache.get(original, endpointB).isEmpty());
    }

    @Test
    void cacheSeparatesPromptSchemaVersions() {
        ReviewCache cache = new ReviewCache(2);
        ReviewContext context = context("same-fingerprint");
        ModelRequestConfig previousSchema = new ModelRequestConfig(
                "https://a.example/chat",
                "model-a",
                CodeMentorSettings.TeachingLevel.JAVAWEB,
                "2026-08-v2");
        ModelRequestConfig currentSchema = new ModelRequestConfig(
                "https://a.example/chat",
                "model-a",
                CodeMentorSettings.TeachingLevel.JAVAWEB,
                ModelRequestConfig.CURRENT_PROMPT_SCHEMA);
        cache.put(
                context,
                previousSchema,
                reviewResult("旧协议结果", "S1", "缓存", "旧协议"));

        assertTrue(cache.get(context, currentSchema).isEmpty());
    }

    @Test
    void cacheSeparatesPsiTargetIdentities() {
        ReviewCache cache = new ReviewCache(2);
        ReviewContext firstOverload = context("same").withTargetIdentity("Demo#method-index-0");
        ReviewContext secondOverload = context("same").withTargetIdentity("Demo#method-index-1");
        ModelRequestConfig config = config("https://a.example/chat");
        cache.put(
                firstOverload,
                config,
                reviewResult("第一个重载", "S1", "重载", "核对参数"));

        assertTrue(cache.get(secondOverload, config).isEmpty());
    }

    @Test
    void clearRemovesCachedLearningIdentityAndResult() {
        ReviewCache cache = new ReviewCache(2);
        ReviewContext context = context("clear-me");
        ModelRequestConfig config = config("https://a.example/chat");
        cache.put(
                context,
                config,
                reviewResult("待清理", "S1", "缓存", "清理"),
                "learning-review-id");

        cache.clear();

        assertTrue(cache.get(context, config).isEmpty());
    }

    @Test
    void cachePreservesSummaryAndImmutableSuggestions() {
        ReviewCache cache = new ReviewCache(2);
        ReviewContext context = context("summary-fingerprint");
        ModelRequestConfig config = config("https://a.example/chat");
        List<Suggestion> mutableSuggestions = new ArrayList<>(List.of(
                suggestion("S1", "集合", "使用泛型")));
        ReviewResult original = new ReviewResult("  集合声明缺少元素类型  ", mutableSuggestions);
        mutableSuggestions.clear();

        cache.put(context, config, original);
        ReviewResult cached = cache.get(context, config).orElseThrow().result();

        assertEquals("集合声明缺少元素类型", cached.summary());
        assertEquals(1, cached.suggestions().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> cached.suggestions().add(
                        suggestion("S2", "集合", "另一条建议")));
    }

    @Test
    void cacheRetainsOriginalLearningReviewIdForReplay() {
        ReviewCache cache = new ReviewCache(2);
        ReviewContext context = context("learning-id");
        ModelRequestConfig config = config("https://a.example/chat");

        cache.put(
                context,
                config,
                reviewResult("摘要", "S1", "集合", "使用泛型"),
                "original-review-id");

        assertEquals(
                "original-review-id",
                cache.get(context, config).orElseThrow().learningReviewId());
    }

    private static ReviewContext context(String fingerprint) {
        return context(fingerprint, "");
    }

    private static ReviewContext context(String fingerprint, String rules) {
        return new ReviewContext(
                "src/Demo.java",
                "Demo#run",
                "void run() {}",
                "class Demo { void run() {} }",
                false,
                1L,
                fingerprint,
                0,
                13,
                JavaWebContext.unknown(),
                rules);
    }

    private static ReviewContext contextWithModelContext(
            String fingerprint, String modelContext) {
        return new ReviewContext(
                "src/Demo.java",
                "Demo#run",
                "void run() {}",
                modelContext,
                false,
                1L,
                fingerprint,
                0,
                13,
                JavaWebContext.unknown(),
                "");
    }

    private static ModelRequestConfig config(String endpoint) {
        return config(endpoint, "model-a", CodeMentorSettings.TeachingLevel.JAVAWEB);
    }

    private static ModelRequestConfig config(
            String endpoint,
            String model,
            CodeMentorSettings.TeachingLevel teachingLevel) {
        return new ModelRequestConfig(
                endpoint,
                model,
                teachingLevel,
                ModelRequestConfig.CURRENT_PROMPT_SCHEMA);
    }

    private static ReviewResult reviewResult(
            String summary, String id, String knowledgePoint, String title) {
        return new ReviewResult(
                summary,
                List.of(suggestion(id, knowledgePoint, title)));
    }

    private static Suggestion suggestion(String id, String knowledgePoint, String title) {
        return new Suggestion.Builder()
                .id(id)
                .category(Suggestion.Category.JAVA_USAGE)
                .certainty(Suggestion.Certainty.MEDIUM)
                .title(title)
                .knowledgePoint(knowledgePoint)
                .verificationCriteria("问题不再存在")
                .build();
    }
}
