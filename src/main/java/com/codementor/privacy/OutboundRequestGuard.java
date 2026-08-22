package com.codementor.privacy;

import com.codementor.model.ModelRequestConfig;
import com.codementor.context.ProjectFilePath;
import com.codementor.review.ReviewContext;
import com.codementor.settings.CodeMentorProjectConsent;
import com.codementor.settings.CodeMentorSettings;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;

/** Applies the same enablement, consent and file checks before every model operation. */
@Service(Service.Level.PROJECT)
public final class OutboundRequestGuard {

    private final Project project;
    private String cachedPatterns;
    private FileExclusionPolicy cachedPolicy;

    public OutboundRequestGuard(Project project) {
        this.project = project;
    }

    public static OutboundRequestGuard getInstance(Project project) {
        return project.getService(OutboundRequestGuard.class);
    }

    public Decision evaluate(ModelRequestConfig config, ReviewContext context) {
        if (project.isDisposed()) {
            return Decision.deny("项目已经关闭");
        }
        CodeMentorSettings settings = CodeMentorSettings.getInstance();
        if (!settings.isReviewEnabled()) {
            return Decision.deny("CodeMentor 评审已在设置中关闭");
        }
        final ModelRequestConfig currentConfig;
        try {
            currentConfig = ModelRequestConfig.capture(settings);
        } catch (IllegalArgumentException error) {
            return Decision.deny("当前模型配置无效：" + error.getMessage());
        }
        if (!currentConfig.equals(config)) {
            return Decision.deny("模型配置已变化，请重新发起本次操作");
        }
        if (!CodeMentorProjectConsent.getInstance(project).hasConsentFor(config)) {
            return Decision.deny("当前项目尚未同意向此模型地址发送学习上下文");
        }
        if (context == null) {
            return Decision.permit();
        }
        if (!context.isInsideProject()) {
            return Decision.deny("项目目录外的文件不会发送给模型");
        }
        FileExclusionPolicy.Decision exclusion = policy(settings).evaluate(context.getFilePath());
        if (exclusion.excluded()) {
            return Decision.deny(
                    "当前文件命中排除规则「" + exclusion.matchedPattern() + "」");
        }
        return Decision.permit();
    }

    /** Cheap pre-collection check used before PSI and project rules are read. */
    public Decision evaluateFile(ProjectFilePath.Result file) {
        if (!file.insideProject()) {
            return Decision.deny("项目目录外的文件不会采集或发送");
        }
        CodeMentorSettings settings = CodeMentorSettings.getInstance();
        FileExclusionPolicy.Decision exclusion =
                policy(settings).evaluate(file.relativePath());
        if (exclusion.excluded()) {
            return Decision.deny(
                    "当前文件命中排除规则「" + exclusion.matchedPattern() + "」");
        }
        return Decision.permit();
    }

    private synchronized FileExclusionPolicy policy(CodeMentorSettings settings) {
        String patterns = settings.getExcludedFilePatterns();
        if (cachedPolicy == null || !patterns.equals(cachedPatterns)) {
            cachedPatterns = patterns;
            cachedPolicy = new FileExclusionPolicy(patterns);
        }
        return cachedPolicy;
    }

    public record Decision(boolean allowed, String reason) {
        private static Decision permit() {
            return new Decision(true, "");
        }

        private static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }
}
