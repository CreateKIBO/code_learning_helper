package com.codementor.privacy;

import com.codementor.review.ReviewContext;

/** 生成与真实发送路径一致的脱敏预览文本。 */
public final class SendPreviewFormatter {

    private SendPreviewFormatter() {}

    public static String format(ReviewContext context, int maxChars) {
        MaskedReviewPayload payload = MaskedReviewPayload.from(context);
        StringBuilder outbound = new StringBuilder();
        if (!payload.projectRules().isBlank()) {
            outbound.append("--- 项目学习约定（.codementor/rules.md）---\n")
                    .append(payload.projectRules())
                    .append("\n\n");
        }
        outbound.append("--- 代码上下文 ---\n")
                .append(payload.modelContext());
        String preview = outbound.toString();
        if (maxChars < 1 || preview.length() <= maxChars) {
            return preview;
        }
        return preview.substring(0, maxChars) + "\n...（发送预览已截断）";
    }
}
