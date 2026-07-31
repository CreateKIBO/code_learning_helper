package com.codementor.privacy;

import com.codementor.model.PrivacyFilter;
import com.codementor.review.ReviewContext;

/** 生成与真实发送路径一致的脱敏预览文本。 */
public final class SendPreviewFormatter {

    private SendPreviewFormatter() {}

    public static String format(ReviewContext context, int maxChars) {
        StringBuilder outbound = new StringBuilder()
                .append("文件：").append(context.getFilePath()).append('\n')
                .append("模式：").append(context.getReviewMode().getLabel()).append('\n')
                .append("目标：").append(context.getTargetName()).append('\n')
                .append("上下文是否截断：")
                .append(context.isTruncated() ? "是" : "否")
                .append("\n\n")
                .append("--- 代码上下文 ---\n")
                .append(context.getModelContext());
        if (!context.getProjectRules().isBlank()) {
            outbound.append("\n\n--- 项目学习约定（.codementor/rules.md）---\n")
                    .append(context.getProjectRules());
        }
        String masked = PrivacyFilter.mask(outbound.toString());
        if (maxChars < 1 || masked.length() <= maxChars) {
            return masked;
        }
        return masked.substring(0, maxChars) + "\n...（发送预览已截断）";
    }
}
