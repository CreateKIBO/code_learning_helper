package com.codementor.review;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 按类别、知识点和标题去除模型返回的语义重复建议，并重新分配稳定 ID。 */
public final class SuggestionDeduplicator {

    private static final int MAX_SUGGESTIONS = 3;

    private SuggestionDeduplicator() {}

    public static List<Suggestion> deduplicate(List<Suggestion> suggestions) {
        List<Suggestion> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Suggestion suggestion : suggestions) {
            String key = suggestion.getCategory().name()
                    + "|" + normalize(suggestion.getKnowledgePoint())
                    + "|" + normalize(suggestion.getTitle());
            if (!seen.add(key)) continue;
            result.add(copyWithId(suggestion, "S" + (result.size() + 1)));
            if (result.size() == MAX_SUGGESTIONS) break;
        }
        return List.copyOf(result);
    }

    private static Suggestion copyWithId(Suggestion source, String id) {
        return new Suggestion.Builder()
                .id(id)
                .category(source.getCategory())
                .certainty(source.getCertainty())
                .title(source.getTitle())
                .evidence(source.getEvidence())
                .hint(source.getHint())
                .explanation(source.getExplanation())
                .example(source.getExample())
                .knowledgePoint(source.getKnowledgePoint())
                .verificationCriteria(source.getVerificationCriteria())
                .nextStep(source.getNextStep())
                .selfCheckQuestion(source.getSelfCheckQuestion())
                .build();
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}]+", "");
    }
}
