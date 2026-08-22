package com.codementor.review;

import com.codementor.practice.Exercise;
import com.codementor.practice.ExerciseRequest;
import com.codementor.model.ModelRequestConfig;

/**
 * 导师模型客户端 —— 统一接口（方案第 12 节"统一接口 + 可配置适配器"）。
 *
 * <p>业务代码只依赖这个接口，不依赖任何具体供应商。
 * 真实模型和 {@link FakeReviewService} 测试桩都实现此接口，业务流程不依赖具体供应商。
 *
 * <p>回调风格：评审是异步的（真实模型要 1~3 秒），用简单回调避免阻塞 EDT。
 * 每个方法都返回可取消句柄，回调可从后台线程触发，由协调器切换到 EDT。
 */
public interface ReviewService {

    /**
     * 异步发起评审。
     *
     * @param context  上下文（含代码、指纹、文档版本）
     * @param requestId 请求 ID，用于日志追踪
     * @param callback 结果回调：成功给建议列表，失败给错误信息
     */
    CancellableRequest review(
            ModelRequestConfig config,
            ReviewContext context,
            String requestId,
            Callback callback);

    interface Callback {
        void onSuccess(ReviewResult result);
        void onFailure(String errorMessage);
    }

    /**
     * 异步验证用户的新代码是否解决了某条建议。
     *
     * @param request 原代码、当前代码和原建议
     * @param requestId 独立的验证请求 ID
     * @param callback 结构化验证结果
     */
    CancellableRequest verify(
            ModelRequestConfig config,
            VerificationRequest request,
            String requestId,
            VerificationCallback callback);

    interface VerificationCallback {
        void onSuccess(VerificationResult result);
        void onFailure(String errorMessage);
    }

    CancellableRequest followUp(
            ModelRequestConfig config,
            FollowUpRequest request,
            String requestId,
            FollowUpCallback callback);

    interface FollowUpCallback {
        void onSuccess(String answer);
        void onFailure(String errorMessage);
    }

    CancellableRequest generateExercise(
            ModelRequestConfig config,
            ExerciseRequest request,
            String requestId,
            ExerciseCallback callback);

    interface ExerciseCallback {
        void onSuccess(Exercise exercise);
        void onFailure(String errorMessage);
    }
}
