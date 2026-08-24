package com.codementor;

import com.codementor.model.ZhiPuModelClient;
import com.codementor.review.FakeReviewService;
import com.codementor.review.ReviewService;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;

/**
 * CodeMentor 应用服务 —— 持有评审服务的统一入口（方案第 7 节"统一接口 + 可配置适配器"）。
 *
 * <p>为什么需要这层：让 {@code ReviewAction} 不再自己 new 客户端，而是从这里取。
 * 这样未来换模型供应商（如从智谱换成通义/DeepSeek）只改本类一处，业务代码零改动。
 *
 * <p>默认返回 {@link ZhiPuModelClient}（真实模型）。
 * {@link FakeReviewService} 保留但默认不用，仅供本地演示和自动测试。
 */
@Service(Service.Level.APP)
public final class CodeMentorService implements Disposable {

    private final ZhiPuModelClient reviewService = new ZhiPuModelClient();

    public static CodeMentorService getInstance() {
        return ApplicationManager.getApplication().getService(CodeMentorService.class);
    }

    /** 获取当前评审服务实现。 */
    public ReviewService getReviewService() {
        return reviewService;
    }

    /** 获取本地假服务；不会因真实模型失败而自动切换，避免掩盖配置问题。 */
    public ReviewService getFakeService() {
        return new FakeReviewService();
    }

    @Override
    public void dispose() {
        reviewService.shutdown();
    }
}
