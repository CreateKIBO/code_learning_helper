package com.codementor.review;

/**
 * 结果级的“一步”：
 * <ul>
 *   <li>解释模式：逐段讲解中的一段——{@link #code} 是目标代码里的原文片段，
 *       {@link #what} 说它做了什么，{@link #why} 说为什么这样写。</li>
 *   <li>下一步指导模式：整体思路中的一步——{@link #code} 为空，{@link #what} 是这一步要做的事，
 *       {@link #why} 是先后顺序的理由。</li>
 * </ul>
 */
public record GuideStep(String code, String what, String why) {

    public GuideStep {
        code = code == null ? "" : code.trim();
        what = what == null ? "" : what.trim();
        why = why == null ? "" : why.trim();
    }

    public boolean hasCode() {
        return !code.isEmpty();
    }
}
