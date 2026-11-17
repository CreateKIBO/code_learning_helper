package com.codementor.context;

/**
 * 在文档里定位导师引用的代码片段。
 *
 * <p>导师的证据与逐段讲解片段是源码的“空白等价”原文，换行和缩进可能与文档不同，
 * 因此比较时忽略全部空白字符，再把命中位置映射回原文档偏移。
 */
public final class EvidenceLocator {

    private EvidenceLocator() {}

    /** 文档中的半开区间 [start, end)。 */
    public record Range(int start, int end) {}

    /**
     * @param preferredStart 优先从这个文档偏移之后查找（例如目标方法的起始位置），
     *                       找不到时再从文档开头查找；越界值按 0 处理。
     * @return 命中的原文档范围；片段为空或找不到时返回 null。
     */
    public static Range locate(CharSequence document, String snippet, int preferredStart) {
        if (document == null || snippet == null) return null;
        String needle = compact(snippet);
        if (needle.isEmpty()) return null;

        int length = document.length();
        StringBuilder haystack = new StringBuilder(length);
        int[] offsets = new int[length];
        int preferredCompactStart = 0;
        for (int index = 0; index < length; index++) {
            char ch = document.charAt(index);
            if (Character.isWhitespace(ch)) continue;
            if (index < preferredStart) preferredCompactStart = haystack.length() + 1;
            offsets[haystack.length()] = index;
            haystack.append(ch);
        }

        int found = preferredStart > 0 && preferredStart < length
                ? haystack.indexOf(needle, preferredCompactStart)
                : -1;
        if (found < 0) found = haystack.indexOf(needle);
        if (found < 0) return null;
        return new Range(offsets[found], offsets[found + needle.length() - 1] + 1);
    }

    private static String compact(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (!Character.isWhitespace(ch)) result.append(ch);
        }
        return result.toString();
    }
}
