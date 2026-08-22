package com.codementor.learning;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

/** Builds a stable comparison key without changing the learner-facing label. */
public final class KnowledgePointNormalizer {

    /** Small, conservative aliases for exact Java/JavaWeb concepts; avoids fuzzy over-merging. */
    private static final Map<String, String> CONSERVATIVE_ALIASES = Map.ofEntries(
            Map.entry("npe", "nullpointerexception"),
            Map.entry("空指针异常", "nullpointerexception"),
            Map.entry("trywithresources", "automaticresourcemanagement"),
            Map.entry("自动资源管理", "automaticresourcemanagement"),
            Map.entry("并发修改异常", "concurrentmodificationexception"),
            Map.entry("transactional", "springtransactionmanagement"),
            Map.entry("spring声明式事务", "springtransactionmanagement"));

    private KnowledgePointNormalizer() {}

    public static String key(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder key = new StringBuilder(normalized.length());
        normalized.codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(key::appendCodePoint);
        String compact = key.toString();
        if (compact.contains("nullpointerexception") || compact.contains("空指针异常")) {
            return "nullpointerexception";
        }
        if (compact.contains("trywithresources") || compact.contains("自动资源管理")) {
            return "automaticresourcemanagement";
        }
        if (compact.contains("concurrentmodificationexception")
                || compact.contains("并发修改异常")) {
            return "concurrentmodificationexception";
        }
        if (compact.contains("transactional") || compact.contains("spring声明式事务")) {
            return "springtransactionmanagement";
        }
        return CONSERVATIVE_ALIASES.getOrDefault(compact, compact);
    }
}
