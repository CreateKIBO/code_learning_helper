package com.codementor.review;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 假评审服务 —— 阶段 0 用，返回硬编码的教学建议。
 *
 * <p>为什么要先做假的（方案第 16 节第 6、7 步）：
 * <ul>
 *   <li>把 UI、状态机、过期检测、错误展示这条链路先跑通；</li>
 *   <li>避免一开始就把"UI 调试问题"和"模型接入问题"搅在一起，难以定位；</li>
 *   <li>不用消耗真实 API 额度。</li>
 * </ul>
 *
 * <p>行为：模拟 800ms 网络延迟（真实模型通常更久），让"请求中"状态可见，
 * 然后返回固定的 JavaWeb 教学建议（对应你选择的默认教学级别）。
 *
 * <p>失败演示：每 5 次请求随机失败 1 次（约 20%），用真实模型常见错误原因模拟，
 * 让用户在阶段 0 就能验证 FAILED 状态的 UI 表现。接真模型后这个机制仍可保留作降级测试。
 */
public class FakeReviewService implements ReviewService {

    /** 请求计数，用于周期性触发失败（每 5 次 1 次），比纯随机更可控、可复现。 */
    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public void review(ReviewContext context, String requestId, Callback callback) {
        int seq = counter.incrementAndGet();
        // 用守护线程模拟异步网络调用，绝不阻塞调用方（EDT）
        Thread worker = new Thread(() -> {
            try {
                // 模拟模型延迟，让 UI 能看到 "请求中..." 状态
                TimeUnit.MILLISECONDS.sleep(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                // 每 5 次失败一次，模拟真实模型的网络/解析错误，验证 FAILED 链路
                if (seq % 5 == 0) {
                    callback.onFailure(simulatedFailure());
                    return;
                }
                callback.onSuccess(buildFakeSuggestions(context));
            } catch (Exception e) {
                callback.onFailure("假服务内部错误: " + e.getMessage());
            }
        }, "CodeMentor-FakeReview-" + requestId);
        worker.setDaemon(true);
        worker.start();
    }

    /** 模拟真实模型常见的失败原因，让 FAILED 状态有真实感。 */
    private String simulatedFailure() {
        String[] reasons = {
            "请求超时（模型响应超过 30 秒）",
            "响应 JSON 解析失败：缺少 suggestions 字段",
            "模型返回了空建议列表",
            "网络连接中断"
        };
        return reasons[(int) (System.currentTimeMillis() % reasons.length)];
    }

    /**
     * 构造硬编码建议。用 JavaWeb 场景（你选的默认级别），
     * 让阶段 0 就能看到贴近真实使用场景的卡片样式。
     */
    private List<Suggestion> buildFakeSuggestions(ReviewContext context) {
        List<Suggestion> list = new ArrayList<>();

        list.add(new Suggestion(
                "S1",
                Suggestion.Category.JAVAWEB,
                Suggestion.Certainty.MEDIUM,
                "Controller 方法缺少参数校验",
                "评审的代码是: " + context.getTargetName(),
                "有没有办法在进入方法体之前，就保证参数不为空、长度合法？",
                "可以用 Jakarta Validation（@Valid + @NotBlank 等）在 Controller 层声明约束，"
                        + "配合全局异常处理器统一返回校验失败信息，避免每个方法手写 if 判空。",
                "@PostMapping(\"/login\")\n"
                        + "public Result login(@Valid @RequestBody LoginDTO dto) { ... }\n\n"
                        + "// LoginDTO 内部：\n"
                        + "@NotBlank(message = \"用户名不能为空\")\n"
                        + "private String username;",
                "Bean Validation 参数校验"
        ));

        list.add(new Suggestion(
                "S2",
                Suggestion.Category.DESIGN,
                Suggestion.Certainty.MEDIUM,
                "返回值建议用统一响应体",
                "评审的代码是: " + context.getTargetName(),
                "如果前端要统一处理成功/失败，这个方法的返回结构是否一致？",
                "企业项目通常约定一个统一的响应体（如 Result<T>），包含 code/message/data，"
                        + "配合全局异常处理器，让所有接口返回结构一致，便于前端统一拦截。",
                "public class Result<T> {\n"
                        + "    private int code;\n"
                        + "    private String message;\n"
                        + "    private T data;\n"
                        + "    // 成功/失败的静态工厂方法省略\n"
                        + "}",
                "统一响应体设计"
        ));

        list.add(new Suggestion(
                "S3",
                Suggestion.Category.READABILITY,
                Suggestion.Certainty.LOW,
                "方法可能承担了过多职责",
                "评审的代码是: " + context.getTargetName(),
                "这个方法是不是同时做了：参数处理、业务逻辑、数据库访问、结果组装？",
                "如果一个方法超过几十行且混合多种关注点，可考虑拆分。"
                        + "注意这是设计取舍，不是绝对错误——按项目阶段和复杂度判断。",
                "// 例如把数据库访问抽到 Repository/Mapper 层：\n"
                        + "// Controller 只做参数接收与响应组装，\n"
                        + "// Service 负责业务编排，Repository 负责持久化。",
                "单一职责与分层"
        ));

        // 方案约束：每次最多 3 条。这里固定构造 3 条（S1/S2/S3），故直接返回。
        // 接真实模型后，截断逻辑会移到 JSON 解析层统一处理。
        return list;
    }
}
