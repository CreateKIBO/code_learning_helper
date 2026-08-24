package com.codementor.model;

import com.codementor.practice.Exercise;
import com.codementor.practice.ExerciseRequest;
import com.codementor.privacy.MaskedReviewPayload;
import com.codementor.review.CancellableRequest;
import com.codementor.review.FollowUpRequest;
import com.codementor.review.ReviewContext;
import com.codementor.review.ReviewResult;
import com.codementor.review.ReviewService;
import com.codementor.review.Suggestion;
import com.codementor.review.VerificationRequest;
import com.codementor.review.VerificationResult;
import com.codementor.settings.ApiKeyStore;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * GLM/OpenAI-compatible model client. Every operation uses the immutable configuration supplied
 * by its coordinator; mutable settings are never reread in the worker thread.
 */
public final class ZhiPuModelClient implements ReviewService {

    private static final Logger LOG = Logger.getInstance(ZhiPuModelClient.class);
    private static final Gson GSON = new Gson();
    private static final int DEFAULT_MAX_TOKENS = 2_400;
    private static final int DEEPSEEK_STRUCTURED_MAX_TOKENS = 8_192;
    private static final String STRUCTURED_RETRY_INSTRUCTION =
            "上一次响应没有产生可解析的最终答案。请跳过思考过程，"
                    + "从字符 { 开始，立即只输出符合既定 schema 的完整 JSON；"
                    + "不要 markdown、前言、解释或空白占位。";
    private static final String TEXT_RETRY_INSTRUCTION =
            "上一次响应没有产生符合要求的最终答案。请立即直接回答，"
                    + "不要输出思考过程，并严格遵守上一条消息的格式要求。";
    static final Duration MENTOR_REQUEST_TIMEOUT = Duration.ofSeconds(120);
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final double TEMPERATURE_DEFAULT = 0.3;
    private static final double TEMPERATURE_RETRY = 0.1;

    private final ModelHttpTransport transport;
    private final Supplier<String> apiKeySupplier;
    private final ExecutorService workerExecutor;

    public ZhiPuModelClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build(), ApiKeyStore::getApiKey);
    }

    ZhiPuModelClient(HttpClient client) {
        this(client, ApiKeyStore::getApiKey);
    }

    ZhiPuModelClient(HttpClient client, Supplier<String> apiKeySupplier) {
        this(client, apiKeySupplier, AppExecutorUtil.createBoundedApplicationPoolExecutor(
                "CodeMentor Model IO", 4));
    }

    ZhiPuModelClient(
            HttpClient client,
            Supplier<String> apiKeySupplier,
            ExecutorService workerExecutor) {
        transport = new ModelHttpTransport(client, MENTOR_REQUEST_TIMEOUT);
        this.apiKeySupplier = Objects.requireNonNull(apiKeySupplier, "apiKeySupplier");
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
    }

    /** 释放模型 IO 线程池；由 CodeMentorService 在应用关闭时调用。 */
    public void shutdown() {
        workerExecutor.shutdown();
    }

    @Override
    public CancellableRequest review(
            ModelRequestConfig config,
            ReviewContext context,
            String requestId,
            Callback callback) {
        return startWorker(() -> doReview(config, context, requestId, callback));
    }

    @Override
    public CancellableRequest verify(
            ModelRequestConfig config,
            VerificationRequest request,
            String requestId,
            VerificationCallback callback) {
        return startWorker(() -> doVerification(config, request, requestId, callback));
    }

    @Override
    public CancellableRequest followUp(
            ModelRequestConfig config,
            FollowUpRequest request,
            String requestId,
            FollowUpCallback callback) {
        return startWorker(() -> doFollowUp(config, request, requestId, callback));
    }

    @Override
    public CancellableRequest generateExercise(
            ModelRequestConfig config,
            ExerciseRequest request,
            String requestId,
            ExerciseCallback callback) {
        return startWorker(() -> doExercise(config, request, requestId, callback));
    }

    private CancellableRequest startWorker(Runnable task) {
        // 模型请求是长达 120 秒的阻塞 IO；专用有界池避免评审/验证/追问/练习并发时
        // 占满 AppExecutorUtil 共享池，饿死平台上其他短时任务。
        Future<?> future = workerExecutor.submit(task);
        return CancellableRequest.forFuture(future);
    }

    private void doReview(
            ModelRequestConfig config,
            ReviewContext context,
            String requestId,
            Callback callback) {
        executeOperation(
                requestId,
                "评审",
                new OperationMessages(
                        "模型建议无法从当前可见代码中核对，请重试。",
                        "模型返回格式不正确：",
                        "",
                        "评审失败，请稍后重试"),
                apiKey -> {
                    MaskedReviewPayload payload = MaskedReviewPayload.from(context);
                    String userPrompt = PromptBuilder.buildUserPrompt(
                            payload.modelContext(),
                            config.teachingLevel(),
                            context.getJavaWebContext(),
                            payload.projectRules(),
                            context.getReviewMode(),
                            context.isTruncated());
                    String requestBody = buildRequestBody(
                            config,
                            PromptBuilder.buildSystemPrompt(context.getReviewMode()),
                            userPrompt,
                            true);
                    return sendAndParseWithRetry(
                            config,
                            apiKey,
                            requestBody,
                            requestId,
                            "评审",
                            content -> EvidenceGroundingValidator.requireGrounded(
                                    JsonResponseParser.parseReview(
                                            content, context.getReviewMode()),
                                    payload.modelContext(),
                                    PrivacyFilter.mask(context.getCode())));
                },
                callback::onSuccess,
                callback::onFailure);
    }

    private void doVerification(
            ModelRequestConfig config,
            VerificationRequest request,
            String requestId,
            VerificationCallback callback) {
        executeOperation(
                requestId,
                "修改验证",
                new OperationMessages(
                        "模型验证结论无法从当前代码中核对，请重试。",
                        "修改验证返回格式不正确：",
                        "",
                        "修改验证失败，请稍后重试"),
                apiKey -> {
                    String userPrompt = PrivacyFilter.mask(
                            PromptBuilder.buildVerificationUserPrompt(
                                    request.getOriginalContext().getModelContext(),
                                    request.getCurrentContext().getModelContext(),
                                    request.getSuggestion()));
                    String maskedCurrentContext = PrivacyFilter.mask(
                            request.getCurrentContext().getModelContext());
                    String maskedCurrentCode = PrivacyFilter.mask(
                            request.getCurrentContext().getCode());
                    String body = buildRequestBody(
                            config,
                            PromptBuilder.buildVerificationSystemPrompt(),
                            userPrompt,
                            true);
                    return sendAndParseWithRetry(
                            config,
                            apiKey,
                            body,
                            requestId,
                            "修改验证",
                            content -> EvidenceGroundingValidator.requireGrounded(
                                    JsonResponseParser.parseVerification(content),
                                    maskedCurrentContext,
                                    maskedCurrentCode));
                },
                callback::onSuccess,
                callback::onFailure);
    }

    private void doFollowUp(
            ModelRequestConfig config,
            FollowUpRequest request,
            String requestId,
            FollowUpCallback callback) {
        executeOperation(
                requestId,
                "追问",
                new OperationMessages(
                        // 追问不经过证据校验器，不会抛出 GroundingException；占位保持骨架统一。
                        "",
                        "导师回答未满足学习格式：",
                        "追问失败：",
                        "追问失败，请稍后重试"),
                apiKey -> {
                    String userPrompt = PrivacyFilter.mask(PromptBuilder.buildFollowUpUserPrompt(
                            request.getContext().getModelContext(),
                            request.getSuggestion(),
                            request.getQuestion(),
                            config.teachingLevel(),
                            request.getContext().getJavaWebContext()));
                    String body = buildRequestBody(
                            config,
                            PromptBuilder.buildFollowUpSystemPrompt(),
                            userPrompt,
                            false);
                    return sendAndParseWithRetry(
                            config,
                            apiKey,
                            body,
                            requestId,
                            "追问",
                            JsonResponseParser::parseFollowUp);
                },
                callback::onSuccess,
                callback::onFailure);
    }

    private void doExercise(
            ModelRequestConfig config,
            ExerciseRequest request,
            String requestId,
            ExerciseCallback callback) {
        executeOperation(
                requestId,
                "小练习",
                new OperationMessages(
                        // 小练习不经过证据校验器，不会抛出 GroundingException；占位保持骨架统一。
                        "",
                        "小练习返回格式不正确：",
                        "",
                        "生成小练习失败，请稍后重试"),
                apiKey -> {
                    String userPrompt = PrivacyFilter.mask(
                            PromptBuilder.buildExerciseUserPrompt(request));
                    String body = buildRequestBody(
                            config,
                            PromptBuilder.buildExerciseSystemPrompt(),
                            userPrompt,
                            true);
                    return sendAndParseWithRetry(
                            config,
                            apiKey,
                            body,
                            requestId,
                            "小练习",
                            content -> JsonResponseParser.parseExercise(
                                    content, request.getKnowledgePoint()));
                },
                callback::onSuccess,
                callback::onFailure);
    }

    /** 四个模型操作的公共执行骨架：取 Key、执行任务、统一的失败分类与耗时日志。 */
    private <T> void executeOperation(
            String requestId,
            String operation,
            OperationMessages messages,
            ApiKeyTask<T> task,
            Consumer<T> onSuccess,
            Consumer<String> onFailure) {
        long startedAt = System.currentTimeMillis();
        try {
            String apiKey = requireApiKey();
            if (apiKey == null) {
                onFailure.accept(apiKeyMissingMessage());
                return;
            }
            T result = task.run(apiKey);
            if (!Thread.currentThread().isInterrupted()) onSuccess.accept(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (EvidenceGroundingValidator.GroundingException e) {
            onFailure.accept(messages.groundingFailure());
        } catch (JsonResponseParser.ParseException e) {
            onFailure.accept(messages.parsePrefix() + e.getMessage());
        } catch (ModelHttpTransport.ApiException e) {
            onFailure.accept(messages.apiPrefix().isEmpty()
                    ? e.getMessage()
                    : messages.apiPrefix() + e.getMessage());
        } catch (Exception e) {
            LOG.warn("[" + shortId(requestId) + "] " + operation + "内部异常", e);
            onFailure.accept(messages.genericFailure());
        } finally {
            logElapsed(requestId, operation, startedAt);
        }
    }

    private record OperationMessages(
            String groundingFailure,
            String parsePrefix,
            String apiPrefix,
            String genericFailure) {}

    @FunctionalInterface
    private interface ApiKeyTask<T> {
        T run(String apiKey) throws Exception;
    }

    private <T> T sendAndParseWithRetry(
            ModelRequestConfig config,
            String apiKey,
            String requestBody,
            String requestId,
            String operation,
            StructuredParser<T> parser)
            throws InterruptedException,
                    ModelHttpTransport.ApiException,
                    JsonResponseParser.ParseException {
        return sendAndParseWithRetry(
                transport,
                config,
                apiKey,
                requestBody,
                requestId,
                operation,
                parser);
    }

    private static <T> T sendAndParseWithRetry(
            ModelHttpTransport transport,
            ModelRequestConfig config,
            String apiKey,
            String requestBody,
            String requestId,
            String operation,
            StructuredParser<T> parser)
            throws InterruptedException,
                    ModelHttpTransport.ApiException,
                    JsonResponseParser.ParseException {
        String attemptBody = requestBody;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return parser.parse(transport.send(config, apiKey, attemptBody));
            } catch (ModelHttpTransport.EmptyContentException e) {
                LOG.warn("[" + shortId(requestId) + "] " + operation
                        + " 未收到最终答案（第 " + attempt + " 次）");
                if (attempt == 2) throw e;
            } catch (JsonResponseParser.ParseException e) {
                LOG.warn("[" + shortId(requestId) + "] " + operation
                        + " 响应格式校验失败（第 " + attempt + " 次）");
                if (attempt == 2) throw e;
            }
            attemptBody = buildProtocolRetryBody(requestBody);
        }
        throw new IllegalStateException("unreachable retry state");
    }

    /** 供 ConnectionTester 复用同一重试骨架；固定使用评审 JSON 解析器。 */
    static ReviewResult sendAndParseForConnectionTest(
            ModelHttpTransport transport,
            ModelRequestConfig config,
            String apiKey,
            String requestBody,
            String requestId,
            String operation)
            throws InterruptedException,
                    ModelHttpTransport.ApiException,
                    JsonResponseParser.ParseException {
        return sendAndParseWithRetry(
                transport,
                config,
                apiKey,
                requestBody,
                requestId,
                operation,
                JsonResponseParser::parseReview);
    }

    @FunctionalInterface
    private interface StructuredParser<T> {
        T parse(String content) throws JsonResponseParser.ParseException;
    }

    static String buildRequestBody(
            ModelRequestConfig config,
            String systemPrompt,
            String userPrompt,
            boolean structuredOutput) {
        JsonObject body = new JsonObject();
        boolean officialDeepSeek =
                ModelEndpointValidator.isOfficialDeepSeekEndpoint(config.endpoint());
        body.addProperty("model", config.model());
        body.addProperty("temperature", TEMPERATURE_DEFAULT);
        body.addProperty(
                "max_tokens",
                structuredOutput && officialDeepSeek
                        ? DEEPSEEK_STRUCTURED_MAX_TOKENS
                        : DEFAULT_MAX_TOKENS);
        if (officialDeepSeek) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "disabled");
            body.add("thinking", thinking);
        }
        if (structuredOutput) {
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            body.add("response_format", responseFormat);
        }

        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        body.add("messages", messages);
        return GSON.toJson(body);
    }

    /** Changes the second attempt so providers with occasional empty JSON output do not replay it. */
    static String buildProtocolRetryBody(String requestBody) {
        JsonObject body = JsonParser.parseString(requestBody).getAsJsonObject();
        JsonArray messages = body.getAsJsonArray("messages");
        JsonObject lastUserMessage = messages.get(messages.size() - 1).getAsJsonObject();
        String originalPrompt = lastUserMessage.get("content").getAsString();
        String retryInstruction = body.has("response_format")
                ? STRUCTURED_RETRY_INSTRUCTION
                : TEXT_RETRY_INSTRUCTION;
        lastUserMessage.addProperty("content", originalPrompt + "\n\n" + retryInstruction);
        body.addProperty("temperature", TEMPERATURE_RETRY);
        return GSON.toJson(body);
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private String requireApiKey() {
        String apiKey = apiKeySupplier.get();
        return apiKey == null || apiKey.isBlank() ? null : apiKey;
    }

    private static String apiKeyMissingMessage() {
        return "请先在 Settings → Tools → CodeMentor 配置 API Key";
    }

    private static void logElapsed(String requestId, String operation, long startedAt) {
        LOG.info("[" + shortId(requestId) + "] " + operation + "耗时 "
                + (System.currentTimeMillis() - startedAt) + "ms");
    }

    private static String shortId(String requestId) {
        if (requestId == null) return "unknown";
        return requestId.substring(0, Math.min(8, requestId.length()));
    }
}
