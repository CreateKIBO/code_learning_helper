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
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Future;

/**
 * GLM/OpenAI-compatible model client. Every operation uses the immutable configuration supplied
 * by its coordinator; mutable settings are never reread in the worker thread.
 */
public final class ZhiPuModelClient implements ReviewService {

    private static final Logger LOG = Logger.getInstance(ZhiPuModelClient.class);
    private static final Gson GSON = new Gson();
    private static final int MAX_TOKENS = 2_400;

    private final ModelHttpTransport transport;

    public ZhiPuModelClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    ZhiPuModelClient(HttpClient client) {
        transport = new ModelHttpTransport(client);
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
                    config.model(),
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
                    config.model(),
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
                    config.model(),
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
                    config.model(),
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
        JsonResponseParser.ParseException lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return parser.parse(transport.send(config, apiKey, requestBody));
            } catch (JsonResponseParser.ParseException e) {
                lastError = e;
                LOG.warn("[" + shortId(requestId) + "] " + operation
                        + " 响应格式校验失败（第 " + attempt + " 次）");
            }
        }
        throw lastError;
    }

    @FunctionalInterface
    private interface StructuredParser<T> {
        T parse(String content) throws JsonResponseParser.ParseException;
    }

    static String buildRequestBody(
            String model,
            String systemPrompt,
            String userPrompt,
            boolean structuredOutput) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", 0.3);
        body.addProperty("max_tokens", MAX_TOKENS);
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

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static String requireApiKey() {
        String apiKey = ApiKeyStore.getApiKey();
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
        String modelError = ModelEndpointValidator.validateModel(model);
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
                    .build());
            String body = buildRequestBody(
                    config.model(),
                    PromptBuilder.buildSystemPrompt(),
                    "只回复 OK",
                    false);
            String content = transport.send(config, apiKey.trim(), body);
            return new TestResult(true, "模型：" + config.model()
                    + "，返回长度 " + content.length());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TestResult(false, "连接测试已取消");
        } catch (Exception e) {
            return new TestResult(false, e.getMessage());
        }
    }

    /** Backward-compatible secure default used by older callers. */
    public static TestResult testConnection(String url, String apiKey, String model) {
        return testConnection(url, apiKey, model, false);
    }
}
