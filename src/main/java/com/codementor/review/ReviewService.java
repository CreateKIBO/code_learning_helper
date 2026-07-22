package com.codementor.review;

import java.util.List;

/**
 * 导师模型客户端 —— 统一接口（方案第 12 节"统一接口 + 可配置适配器"）。
 *
 * <p>业务代码只依赖这个接口，不依赖任何具体供应商。
 * 阶段 0 的实现是 {@link FakeReviewService}（硬编码建议），
 * 阶段 1 再加一个真实供应商适配器（如 ZhiPuModelClient），业务代码无需改动。
 *
 * <p>回调风格：评审是异步的（真实模型要 1~3 秒），用简单回调避免阻塞 EDT。
 * 阶段 1 接真实模型时可换成 CompletableFuture 或协程，接口本身不变。
 */
public interface ReviewService {

    /**
     * 异步发起评审。
     *
     * @param context  上下文（含代码、指纹、文档版本）
     * @param requestId 请求 ID，用于日志追踪
     * @param callback 结果回调：成功给建议列表，失败给错误信息
     */
    void review(ReviewContext context, String requestId, Callback callback);

    interface Callback {
        void onSuccess(List<Suggestion> suggestions);
        void onFailure(String errorMessage);
    }
}
