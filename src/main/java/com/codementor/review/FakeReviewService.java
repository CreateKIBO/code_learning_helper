package com.codementor.review;

import com.codementor.practice.Exercise;
import com.codementor.practice.ExerciseRequest;
import com.codementor.model.ModelRequestConfig;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Future;

/**
 * 假评审服务 —— 本地演示和自动测试用，返回硬编码的教学建议。
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
 * 用于验证 FAILED 状态的 UI 表现，不会消耗真实模型额度。
 */
public class FakeReviewService implements ReviewService {

    /** 请求计数，用于周期性触发失败（每 5 次 1 次），比纯随机更可控、可复现。 */
    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public CancellableRequest review(
            ModelRequestConfig config,
            ReviewContext context,
            String requestId,
            Callback callback) {
        int seq = counter.incrementAndGet();
        // 用守护线程模拟异步网络调用，绝不阻塞调用方（EDT）
        return startAsync(() -> {
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
                boolean explaining = context.getReviewMode() == ReviewMode.EXPLAIN;
                callback.onSuccess(new ReviewResult(
                        explaining
                                ? "这段代码可以沿着输入、关键调用和输出建立执行流程。"
                                : "优先关注输入边界、分层职责和方法职责这三个学习点。",
                        explaining
                                ? buildFakeExplanations(context)
                                : buildFakeSuggestions(context)));
            } catch (Exception e) {
                callback.onFailure("假服务内部错误: " + e.getMessage());
            }
        });
    }

    @Override
    public CancellableRequest verify(ModelRequestConfig config,
                                     VerificationRequest request, String requestId,
                                     VerificationCallback callback) {
        return startAsync(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(500);
                boolean changed = !request.getOriginalContext().getFingerprint()
                        .equals(request.getCurrentContext().getFingerprint());
                if (!changed) {
                    callback.onFailure("代码尚未修改，请先尝试按提示调整代码。");
                    return;
                }
                callback.onSuccess(new VerificationResult(
                        VerificationStatus.RESOLVED,
                        "假服务检测到代码指纹已经变化。",
                        "接入真实模型后会根据原建议的验证标准判断。"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                callback.onFailure("假验证服务内部错误：" + e.getMessage());
            }
        });
    }

    @Override
    public CancellableRequest followUp(
            ModelRequestConfig config,
            FollowUpRequest request, String requestId, FollowUpCallback callback) {
        return startAsync(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(400);
                callback.onSuccess(
                        "这是关于“" + request.getSuggestion().getTitle()
                                + "”的假追问回答。接入真实模型时会结合当前建议和代码上下文回答。");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Override
    public CancellableRequest generateExercise(
            ModelRequestConfig config,
            ExerciseRequest request, String requestId, ExerciseCallback callback) {
        return startAsync(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(450);
                callback.onSuccess(new Exercise(
                        "练习：" + request.getKnowledgePoint(),
                        request.getKnowledgePoint(),
                        "补全下面的方法，让它在输入不合法时给出清晰结果，并写出你选择这种处理方式的理由。",
                        "public Result handle(Input input) {\n    // TODO: 由你完成\n}",
                        List.of("只修改这个方法和必要的数据约束", "不要引入新的框架"),
                        List.of("先明确什么输入属于不合法", "再考虑校验发生在哪一层"),
                        List.of("正常输入可得到预期结果", "非法输入有明确且一致的处理")));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static CancellableRequest startAsync(Runnable task) {
        Future<?> future = AppExecutorUtil.getAppExecutorService().submit(task);
        return CancellableRequest.forFuture(future);
    }

    /** 模拟真实模型常见的失败原因，让 FAILED 状态有真实感。 */
    private String simulatedFailure() {
        String[] reasons = {
            "请求超时（模型响应超过 120 秒）",
            "响应 JSON 解析失败：缺少 suggestions 字段",
            "模型返回结果未通过结构校验",
            "网络连接中断"
        };
        return reasons[(int) (System.currentTimeMillis() % reasons.length)];
    }

    /**
     * 构造硬编码 JavaWeb 建议，让离线演示也能看到完整卡片。
     */
    private List<Suggestion> buildFakeSuggestions(ReviewContext context) {
        // 用 Builder 构造：每个字段都有名字，比 9 参构造器易读、不易传错。
        // 与模型 JSON 解析复用同一个 Builder 数据结构。
        return List.of(
                new Suggestion.Builder()
                        .id("S1")
                        .category(Suggestion.Category.JAVAWEB)
                        .certainty(Suggestion.Certainty.MEDIUM)
                        .title("Controller 方法缺少参数校验")
                        .evidence("评审的代码是: " + context.getTargetName())
                        .hint("有没有办法在进入方法体之前，就保证参数不为空、长度合法？")
                        .explanation("可以用 Jakarta Validation（@Valid + @NotBlank 等）在 Controller 层声明约束，"
                                + "配合全局异常处理器统一返回校验失败信息，避免每个方法手写 if 判空。")
                        .example("@PostMapping(\"/login\")\n"
                                + "public Result login(@Valid @RequestBody LoginDTO dto) { ... }\n\n"
                                + "// LoginDTO 内部：\n"
                                + "@NotBlank(message = \"用户名不能为空\")\n"
                                + "private String username;")
                        .knowledgePoint("Bean Validation 参数校验")
                        .verificationCriteria("外部输入在进入业务逻辑前具有明确的校验约束")
                        .nextStep("先标出所有来自 HTTP 请求的外部输入，再确认它们进入业务逻辑前是否有约束。")
                        .selfCheckQuestion("为什么参数校验通常应发生在进入核心业务逻辑之前？")
                        .build(),

                new Suggestion.Builder()
                        .id("S2")
                        .category(Suggestion.Category.DESIGN)
                        .certainty(Suggestion.Certainty.MEDIUM)
                        .title("返回值建议用统一响应体")
                        .evidence("评审的代码是: " + context.getTargetName())
                        .hint("如果前端要统一处理成功/失败，这个方法的返回结构是否一致？")
                        .explanation("企业项目通常约定一个统一的响应体（如 Result<T>），包含 code/message/data，"
                                + "配合全局异常处理器，让所有接口返回结构一致，便于前端统一拦截。")
                        .example("public class Result<T> {\n"
                                + "    private int code;\n"
                                + "    private String message;\n"
                                + "    private T data;\n"
                                + "    // 成功/失败的静态工厂方法省略\n"
                                + "}")
                        .knowledgePoint("统一响应体设计")
                        .verificationCriteria("返回结构与当前项目既有 API 契约保持一致")
                        .nextStep("先对照项目中已有接口，找出统一响应结构的既有约定。")
                        .selfCheckQuestion("统一响应体解决的是哪一类前后端协作问题？")
                        .build(),

                new Suggestion.Builder()
                        .id("S3")
                        .category(Suggestion.Category.READABILITY)
                        .certainty(Suggestion.Certainty.LOW)
                        .title("方法可能承担了过多职责")
                        .evidence("评审的代码是: " + context.getTargetName())
                        .hint("这个方法是不是同时做了：参数处理、业务逻辑、数据库访问、结果组装？")
                        .explanation("如果一个方法超过几十行且混合多种关注点，可考虑拆分。"
                                + "注意这是设计取舍，不是绝对错误——按项目阶段和复杂度判断。")
                        .example("// 例如把数据库访问抽到 Repository/Mapper 层：\n"
                                + "// Controller 只做参数接收与响应组装，\n"
                                + "// Service 负责业务编排，Repository 负责持久化。")
                        .knowledgePoint("单一职责与分层")
                        .verificationCriteria("方法中的职责边界比修改前更清晰，且没有引入无意义抽象")
                        .nextStep("用一句话分别写出方法当前承担的职责，再判断是否真的需要拆分。")
                        .selfCheckQuestion("什么时候拆分方法会增加复杂度而不是改善设计？")
                        .build()
        );
        // 方案约束：每次最多 3 条。这里固定构造 3 条（S1/S2/S3）。
        // 接真实模型后，截断逻辑会移到 JSON 解析层统一处理。
    }

    private List<Suggestion> buildFakeExplanations(ReviewContext context) {
        return List.of(
                new Suggestion.Builder()
                        .id("S1")
                        .category(Suggestion.Category.JAVA_USAGE)
                        .certainty(Suggestion.Certainty.MEDIUM)
                        .title("先看清这个方法的输入、处理和输出")
                        .evidence("当前解释目标：" + context.getTargetName())
                        .hint("这个方法接收了什么，最终把什么交给调用方？")
                        .explanation("可以先沿着参数、关键分支和 return 语句走一遍，"
                                + "再看它调用的依赖分别承担什么职责。")
                        .example("// 先用自己的话写三行：输入 → 处理 → 输出")
                        .knowledgePoint("代码执行流程")
                        .verificationCriteria("能用自己的话说明方法的主要执行流程")
                        .nextStep("按输入、关键分支和输出的顺序，用三句话复述执行过程。")
                        .selfCheckQuestion("如果关键条件取反，执行路径和返回结果会怎样变化？")
                        .build(),
                new Suggestion.Builder()
                        .id("S2")
                        .category(Suggestion.Category.JAVAWEB)
                        .certainty(Suggestion.Certainty.MEDIUM)
                        .title("结合当前分层角色理解职责")
                        .evidence("识别到角色："
                                + context.getJavaWebContext().getRole().getLabel())
                        .hint("这段逻辑属于输入适配、业务规则，还是数据访问？")
                        .explanation(context.getJavaWebContext().getReviewFocus())
                        .example("// 这是一条理解提示，不要求你立刻重构。")
                        .knowledgePoint("JavaWeb 分层职责")
                        .verificationCriteria("能说明当前类型在调用链中的职责")
                        .nextStep("从一次请求进入当前类的位置开始，继续追踪它调用的下一层。")
                        .selfCheckQuestion("当前类型应该知道哪些信息，又不应该知道哪些实现细节？")
                        .build());
    }
}
