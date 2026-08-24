package com.codementor.review;

import com.codementor.model.ModelRequestConfig;
import com.codementor.privacy.OutboundRequestGuard;
import com.codementor.settings.CodeMentorSettings;
import com.intellij.openapi.project.Project;

import java.util.function.Consumer;

/**
 * 各 Coordinator 共用的模型任务前置守卫：配置捕获与出站检查。
 * 守卫策略（失败文案、拒绝理由）集中在这里，避免四处复制后各自漂移。
 */
public final class ModelTaskGuards {

    private ModelTaskGuards() {}

    /** 捕获模型请求配置；无效时把错误信息交给 {@code failAction} 并返回 {@code null}。 */
    public static ModelRequestConfig captureConfig(Consumer<String> failAction) {
        try {
            return ModelRequestConfig.capture(CodeMentorSettings.getInstance());
        } catch (IllegalArgumentException e) {
            failAction.accept(e.getMessage());
            return null;
        }
    }

    /** 出站守卫；拒绝时把原因交给 {@code failAction} 并返回 {@code false}。 */
    public static boolean guardOutbound(
            Project project,
            ModelRequestConfig config,
            ReviewContext context,
            Consumer<String> failAction) {
        OutboundRequestGuard.Decision outbound =
                OutboundRequestGuard.getInstance(project).evaluate(config, context);
        if (outbound.allowed()) return true;
        failAction.accept(outbound.reason());
        return false;
    }
}
