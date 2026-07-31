package com.codementor.regression;

import com.codementor.context.ModelContextBuilder;
import com.codementor.context.TechnologyTagDetector;
import com.codementor.javaweb.JavaWebContext;
import com.codementor.model.PrivacyFilter;
import com.codementor.model.PromptBuilder;
import com.codementor.privacy.FileExclusionPolicy;
import com.codementor.review.ReviewMode;
import com.codementor.settings.CodeMentorSettings;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固定的20份 Java / JavaWeb 本地回归语料。
 *
 * <p>只验证排除、脱敏、技术标签、上下文预算和提示协议，不调用真实模型。
 */
class JavaLearningCorpusTest {

    @Test
    void validatesTwentyOfflineLearningScenarios() {
        List<CorpusCase> corpus = loadCorpus();
        assertEquals(20, corpus.size());
        Set<String> ids = new HashSet<>();
        FileExclusionPolicy exclusionPolicy =
                new FileExclusionPolicy("*Credential.java");

        for (int index = 0; index < corpus.size(); index++) {
            CorpusCase item = corpus.get(index);
            assertTrue(ids.add(item.id), "重复语料 ID：" + item.id);
            assertEquals(
                    item.shouldExclude,
                    exclusionPolicy.evaluate(item.filePath).excluded(),
                    item.id);

            List<String> tags =
                    TechnologyTagDetector.detect(item.declarationEvidence);
            if (!item.expectedTechnologyTag.isBlank()) {
                assertTrue(tags.contains(item.expectedTechnologyTag), item.id);
            }

            String maskedCode = PrivacyFilter.mask(item.code);
            if (!item.secretLiteral.isBlank()) {
                assertFalse(maskedCode.contains(item.secretLiteral), item.id);
            }

            ModelContextBuilder.Result context = ModelContextBuilder.build(
                    new ModelContextBuilder.Input(
                            "",
                            item.declarationEvidence,
                            "class RegressionSample",
                            List.of(),
                            item.id,
                            maskedCode,
                            List.of(),
                            tags));
            assertTrue(
                    context.text().length() <= ModelContextBuilder.MAX_CONTEXT_CHARS,
                    item.id);

            ReviewMode mode = index % 2 == 0
                    ? ReviewMode.REVIEW
                    : ReviewMode.EXPLAIN;
            String prompt = PromptBuilder.buildUserPrompt(
                    context.text(),
                    CodeMentorSettings.TeachingLevel.JAVAWEB,
                    JavaWebContext.unknown(),
                    "",
                    mode);
            assertTrue(prompt.contains(mode.getLabel()), item.id);
            if (!item.secretLiteral.isBlank()) {
                assertFalse(prompt.contains(item.secretLiteral), item.id);
            }
        }
    }

    private static List<CorpusCase> loadCorpus() {
        var stream = JavaLearningCorpusTest.class.getResourceAsStream(
                "/regression/java-learning-corpus.json");
        assertNotNull(stream);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return new Gson().fromJson(
                    reader,
                    new TypeToken<List<CorpusCase>>() {}.getType());
        } catch (Exception e) {
            throw new IllegalStateException("读取回归语料失败", e);
        }
    }

    private static final class CorpusCase {
        private String id;
        private String filePath;
        private String declarationEvidence;
        private String code;
        private String expectedTechnologyTag;
        private boolean shouldExclude;
        private String secretLiteral;
    }
}
