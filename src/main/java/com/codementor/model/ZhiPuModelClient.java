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
import java.util.concurrent.Future;
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
    static final Duration CONNECTION_TEST_TIMEOUT = Duration.ofSeconds(30);

    private final ModelHttpTransport transport;
    private final Supplier<String> apiKeySupplier;

    public ZhiPuModelClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), ApiKeyStore::getApiKey);
    }

    ZhiPuModelClient(HttpClient client) {
        this(client, ApiKeyStore::getApiKey);
    }

    ZhiPuModelClient(HttpClient client, Supplier<String> apiKeySupplier) {
        transport = new ModelHttpTransport(client, MENTOR_REQUEST_TIMEOUT);
        this.apiKeySupplier = Objects.requireNonNull(apiKeySupplier, "apiKeySupplier");
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

    private static CancellableRequest startWorker(Runnable task) {
        Future<?> future = AppExecutorUtil.getAppExecutorService().submit(task);
        return CancellableRequest.forFuture(future);
    }

    private void doReview(
            ModelRequestConfig config,
            ReviewContext context,
            String requestId,
            Callback callback) {
        long startedAt = System.currentTimeMillis();
        try {
            String apiKey = requireApiKey();
            if (apiKey == null) {
                callback.onFailure(apiKeyMissingMessage());
                return;
            }
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
            ReviewResult result = sendAndParseWithRetry(
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
            if (!Thread.currentThread().isInterrupted()) callback.onSuccess(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (EvidenceGroundingValidator.GroundingException e) {
            callback.onFailure("模型建议无法从当前可见代码中核对，请重试。");
        } catch (JsonResponseParser.ParseException e) {
            callback.onFailure("模型返回格式不正确：" + e.getMessage());
        } catch (ModelHttpTransport.ApiException e) {
            callback.onFailure(e.getMessage());
        } catch (Exception e) {
            LOG.warn("[" + shortId(requestId) + "] 评审内部异常", e);
            callback.onFailure("评审失败，请稍后重试");
        } finally {
            logElapsed(requestId, "评审", startedAt);
        }
    }

    private void doVerification(
            ModelRequestConfig config,
            VerificationRequest request,
            String requestId,
            VerificationCallback callback) {
        long startedAt = System.currentTimeMillis();
        try {
            String apiKey = requireApiKey();
            if (apiKey == null) {
                callback.onFailure(apiKeyMissingMessage());
                return;
            }
            String userPrompt = PrivacyFilter.mask(PromptBuilder.buildVerificationUserPrompt(
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
            VerificationResult result = sendAndParseWithRetry(
                    config,
                    apiKey,
                    body,
                    requestId,
                    "修改验证",
                    content -> EvidenceGroundingValidator.requireGrounded(
                            JsonResponseParser.parseVerification(content),
                            maskedCurrentContext,
                            maskedCurrentCode));
            if (!Thread.currentThread().isInterrupted()) callback.onSuccess(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (EvidenceGroundingValidator.GroundingException e) {
            callback.onFailure("模型验证结论无法从当前代码中核对，请重试。");
        } catch (JsonResponseParser.ParseException e) {
            callback.onFailure("修改验证返回格式不正确：" + e.getMessage());
        } catch (ModelHttpTransport.ApiException e) {
            callback.onFailure(e.getMessage());
        } catch (Exception e) {
            LOG.warn("[" + shortId(requestId) + "] 修改验证内部异常", e);
            callback.onFailure("修改验证失败，请稍后重试");
        } finally {
            logElapsed(requestId, "修改验证", startedAt);
        }
    }

    private void doFollowUp(
            ModelRequestConfig config,
            FollowUpRequest request,
            String requestId,
            FollowUpCallback callback) {
        long startedAt = System.currentTimeMillis();
        try {
            String apiKey = requireApiKey();
            if (apiKey == null) {
                callback.onFailure(apiKeyMissingMessage());
                return;
            }
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
            String answer = sendAndParseWithRetry(
                    config,
                    apiKey,
                    body,
                    requestId,
                    "追问",
                    JsonResponseParser::parseFollowUp);
            if (!Thread.currentThread().isInterrupted()) callback.onSuccess(answer);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (JsonResponseParser.ParseException e) {
            callback.onFailure("导师回答未满足学习格式：" + e.getMessage());
        } catch (ModelHttpTransport.ApiException e) {
            callback.onFailure("追问失败：" + e.getMessage());
        } catch (Exception e) {
            LOG.warn("[" + shortId(requestId) + "] 追问内部异常", e);
            callback.onFailure("追问失败，请稍后重试");
        } finally {
            logElapsed(requestId, "追问", startedAt);
        }
    }

    private void doExercise(
            ModelRequestConfig config,
            ExerciseRequest request,
            String requestId,
            ExerciseCallback callback) {
        long startedAt = System.currentTimeMillis();
        try {
            String apiKey = requireApiKey();
            if (apiKey == null) {
                callback.onFailure(apiKeyMissingMessage());
                return;
            }
            String userPrompt = PrivacyFilter.mask(PromptBuilder.buildExerciseUserPrompt(request));
            String body = buildRequestBody(
                    config,
                    PromptBuilder.buildExerciseSystemPrompt(),
                    userPrompt,
                    true);
            Exercise exercise = sendAndParseWithRetry(
                    config,
                    apiKey,
                    body,
                    requestId,
                    "小练习",
                    content -> JsonResponseParser.parseExercise(
                            content, request.getKnowledgePoint()));
            if (!Thread.currentThread().isInterrupted()) callback.onSuccess(exercise);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (JsonResponseParser.ParseException e) {
            callback.onFailure("小练习返回格式不正确：" + e.getMessage());
        } catch (ModelHttpTransport.ApiException e) {
            callback.onFailure(e.getMessage());
        } catch (Exception e) {
            LOG.warn("[" + shortId(requestId) + "] 小练习内部异常", e);
            callback.onFailure("生成小练习失败，请稍后重试");
        } finally {
            logElapsed(requestId, "小练习", startedAt);
        }
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
        body.addProperty("temperature", 0.3);
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
        body.addProperty("temperature", 0.1);
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

    public static final class TestResult {
        public final boolean success;
        public final String message;

        public TestResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    /** Performs a minimal blocking request. Call from a background task only. */
    public static TestResult testConnection(
            String url,
            String apiKey,
            String model,
            boolean allowInsecureLocalEndpoint) {
        ModelEndpointValidator.Result endpoint =
                ModelEndpointValidator.validate(url, allowInsecureLocalEndpoint);
        if (!endpoint.valid()) return new TestResult(false, endpoint.errorMessage());
        String modelError = ModelEndpointValidator.validateModelForEndpoint(
                model, endpoint.normalizedEndpoint());
        if (!modelError.isEmpty()) return new TestResult(false, modelError);
        if (apiKey == null || apiKey.isBlank()) {
            return new TestResult(false, "API Key 不能为空");
        }
        try {
            ModelRequestConfig config = new ModelRequestConfig(
                    endpoint.normalizedEndpoint(),
                    model.trim(),
                    com.codementor.settings.CodeMentorSettings.TeachingLevel.JAVAWEB,
                    ModelRequestConfig.CURRENT_PROMPT_SCHEMA);
            ModelHttpTransport transport = new ModelHttpTransport(HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build(), CONNECTION_TEST_TIMEOUT);
            String body = buildRequestBody(
                    config,
                    "你正在执行 CodeMentor 连接兼容性测试，不分析真实项目。"
                            + "只输出严格 JSON："
                            + "{\"summary\":\"连接正常\",\"suggestions\":[]}",
                    "请返回上述 JSON，不要 markdown 或额外文字。",
                    true);
            ReviewResult result = sendAndParseWithRetry(
                    transport,
                    config,
                    apiKey.trim(),
                    body,
                    "connection",
                    "导师协议测试",
                    JsonResponseParser::parseReview);
            return new TestResult(true, "模型：" + config.model()
                    + "，导师 JSON 协议可用（摘要长度 "
                    + result.summary().length() + "）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TestResult(false, "连接测试已取消");
        } catch (ModelHttpTransport.EmptyContentException e) {
            return new TestResult(
                    false,
                    "网络与鉴权已通过，但导师协议没有最终内容：" + e.getMessage()
                            + "；实际请求：" + endpoint.normalizedEndpoint());
        } catch (JsonResponseParser.ParseException e) {
            return new TestResult(
                    false,
                    "网络与鉴权已通过，但导师 JSON 协议不兼容：" + e.getMessage()
                            + "；实际请求：" + endpoint.normalizedEndpoint());
        } catch (Exception e) {
            String message = e.getMessage() == null || e.getMessage().isBlank()
                    ? "连接测试失败，服务端未返回详细信息"
                    : e.getMessage();
            return new TestResult(
                    false, message + "；实际请求：" + endpoint.normalizedEndpoint());
        }
    }

    /** Backward-compatible secure default used by older callers. */
    public static TestResult testConnection(String url, String apiKey, String model) {
        return testConnection(url, apiKey, model, false);
    }
}
