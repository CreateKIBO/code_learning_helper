package com.codementor.model;

import com.codementor.review.ReviewContext;
import com.codementor.review.ReviewService;
import com.codementor.review.Suggestion;
import com.codementor.review.CancellableRequest;
import com.codementor.review.FollowUpRequest;
import com.codementor.review.VerificationRequest;
import com.codementor.review.VerificationResult;
import com.codementor.practice.Exercise;
import com.codementor.practice.ExerciseRequest;
import com.codementor.settings.ApiKeyStore;
import com.codementor.settings.CodeMentorSettings;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 智谱 GLM 模型客户端 —— 实现 {@link ReviewService}（方案第 6、9 节）。
 *
 * <p>调用智谱 chat/completions（OpenAI 兼容），把返回的结构化 JSON 解析成建议。
 *
 * <p>线程模型（镜像 FakeReviewService）：
 * <ul>
 *   <li>{@link #review} 立即返回，HTTP 请求在守护线程执行；</li>
 *   <li>回调 {@code onSuccess/onFailure} 在后台线程触发，<b>不</b>跳 EDT（协调器负责）；</li>
 *   <li>{@link InterruptedException} 恢复中断标志，不回调。</li>
 * </ul>
 *
 * <p>健壮性：
 * <ul>
 *   <li>未配置 API Key → 直接 onFailure("请先在设置中配置 API Key")，不发请求；</li>
 *   <li>HTTP 错误 → onFailure 带状态码；</li>
 *   <li>JSON 解析失败 → 重试 1 次（方案第 9 节）；仍失败 → onFailure。</li>
 * </ul>
 *
 * <p>安全（方案第 11 节）：日志只记请求 ID 和耗时，<b>不</b>记代码和 Key。
 */
public class ZhiPuModelClient implements ReviewService {

    private static final Logger LOG = Logger.getInstance(ZhiPuModelClient.class);
    private static final Gson GSON = new Gson();
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int REQUEST_TIMEOUT_MS = 30_000;

    /** 单例 HttpClient，跨请求复用。 */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .build();

    @Override
    public CancellableRequest review(ReviewContext context, String requestId, Callback callback) {
        return startWorker(
                "CodeMentor-ZhiPu-" + requestId,
                () -> doReview(context, requestId, callback));
    }

    @Override
    public CancellableRequest verify(VerificationRequest request, String requestId,
                                     VerificationCallback callback) {
        return startWorker(
                "CodeMentor-ZhiPu-Verify-" + requestId,
                () -> doVerification(request, requestId, callback));
    }

    @Override
    public CancellableRequest followUp(
            FollowUpRequest request, String requestId, FollowUpCallback callback) {
        return startWorker(
                "CodeMentor-ZhiPu-FollowUp-" + requestId,
                () -> doFollowUp(request, requestId, callback));
    }

    @Override
    public CancellableRequest generateExercise(
            ExerciseRequest request, String requestId, ExerciseCallback callback) {
        return startWorker(
                "CodeMentor-ZhiPu-Exercise-" + requestId,
                () -> doExercise(request, requestId, callback));
    }

    private static CancellableRequest startWorker(String name, Runnable task) {
        Thread worker = new Thread(task, name);
        worker.setDaemon(true);
        worker.start();
        return CancellableRequest.forThread(worker);
    }

    private void doReview(ReviewContext context, String requestId, Callback callback) {
        long start = System.currentTimeMillis();
        try {
            String apiKey = ApiKeyStore.getApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                callback.onFailure("请先在 Settings → Tools → CodeMentor 配置 API Key");
                return;
            }
            CodeMentorSettings settings = CodeMentorSettings.getInstance();
            String url = settings.getApiBaseUrl();
            String model = settings.getModel();

            // 敏感遮盖后再发
            String code = PrivacyFilter.mask(context.getModelContext());
            String projectRules = PrivacyFilter.mask(context.getProjectRules());
            String systemPrompt =
                    PromptBuilder.buildSystemPrompt(context.getReviewMode());
            String userPrompt = PromptBuilder.buildUserPrompt(
                    code,
                    settings.getTeachingLevel(),
                    context.getJavaWebContext(),
                    projectRules,
                    context.getReviewMode());

            String requestBody = buildRequestBody(model, systemPrompt, userPrompt);

            List<Suggestion> suggestions = sendAndParseWithRetry(
                    url,
                    apiKey,
                    requestBody,
                    requestId,
                    "评审",
                    JsonResponseParser::parse);
            callback.onSuccess(suggestions);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return; // 不回调
        } catch (JsonResponseParser.ParseException e) {
            callback.onFailure("模型返回解析失败：" + e.getMessage()
                    + "（请重试，或检查代码是否过长）");
        } catch (ApiException e) {
            callback.onFailure(e.getMessage());
        } catch (Exception e) {
            LOG.warn("[" + requestId + "] 评审异常", e);
            callback.onFailure("评审失败：" + e.getMessage());
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            LOG.info("[" + requestId + "] 评审耗时 " + elapsed + "ms");
        }
    }

    private void doVerification(VerificationRequest request, String requestId,
                                VerificationCallback callback) {
        long start = System.currentTimeMillis();
        try {
            String apiKey = ApiKeyStore.getApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                callback.onFailure("请先在 Settings → Tools → CodeMentor 配置 API Key");
                return;
            }
            CodeMentorSettings settings = CodeMentorSettings.getInstance();
            String originalCode = PrivacyFilter.mask(request.getOriginalContext().getCode());
            String currentCode = PrivacyFilter.mask(request.getCurrentContext().getCode());
            String body = buildRequestBody(
                    settings.getModel(),
                    PromptBuilder.buildVerificationSystemPrompt(),
                    PromptBuilder.buildVerificationUserPrompt(
                            originalCode, currentCode, request.getSuggestion()));

            VerificationResult result = sendAndParseWithRetry(
                    settings.getApiBaseUrl(),
                    apiKey,
                    body,
                    requestId,
                    "修改验证",
                    JsonResponseParser::parseVerification);
            callback.onSuccess(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (JsonResponseParser.ParseException e) {
            callback.onFailure("修改验证返回解析失败：" + e.getMessage());
        } catch (ApiException e) {
            callback.onFailure(e.getMessage());
        } catch (Exception e) {
            LOG.warn("[" + requestId + "] 修改验证异常", e);
            callback.onFailure("修改验证失败：" + e.getMessage());
        } finally {
            LOG.info("[" + requestId + "] 修改验证耗时 "
                    + (System.currentTimeMillis() - start) + "ms");
        }
    }

    private void doFollowUp(FollowUpRequest request, String requestId,
                            FollowUpCallback callback) {
        long start = System.currentTimeMillis();
        try {
            String apiKey = ApiKeyStore.getApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                callback.onFailure("请先在 Settings → Tools → CodeMentor 配置 API Key");
                return;
            }
            CodeMentorSettings settings = CodeMentorSettings.getInstance();
            String code = PrivacyFilter.mask(request.getContext().getModelContext());
            String body = buildRequestBody(
                    settings.getModel(),
                    PromptBuilder.buildFollowUpSystemPrompt(),
                    PromptBuilder.buildFollowUpUserPrompt(
                            code, request.getSuggestion(), request.getQuestion()));
            String answer = sendChatRequest(
                    settings.getApiBaseUrl(), apiKey, body, requestId);
            callback.onSuccess(answer);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.warn("[" + requestId + "] 追问异常", e);
            callback.onFailure("追问失败：" + e.getMessage());
        } finally {
            LOG.info("[" + requestId + "] 追问耗时 "
                    + (System.currentTimeMillis() - start) + "ms");
        }
    }

    private void doExercise(
            ExerciseRequest request, String requestId, ExerciseCallback callback) {
        long start = System.currentTimeMillis();
        try {
            String apiKey = ApiKeyStore.getApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                callback.onFailure("请先在 Settings → Tools → CodeMentor 配置 API Key");
                return;
            }
            CodeMentorSettings settings = CodeMentorSettings.getInstance();
            String body = buildRequestBody(
                    settings.getModel(),
                    PromptBuilder.buildExerciseSystemPrompt(),
                    PromptBuilder.buildExerciseUserPrompt(request));
            Exercise exercise = sendAndParseWithRetry(
                    settings.getApiBaseUrl(),
                    apiKey,
                    body,
                    requestId,
                    "小练习",
                    JsonResponseParser::parseExercise);
            callback.onSuccess(exercise);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (JsonResponseParser.ParseException e) {
            callback.onFailure("小练习返回解析失败：" + e.getMessage());
        } catch (ApiException e) {
            callback.onFailure(e.getMessage());
        } catch (Exception e) {
            LOG.warn("[" + requestId + "] 生成小练习异常", e);
            callback.onFailure("生成小练习失败：" + e.getMessage());
        } finally {
            LOG.info("[" + requestId + "] 生成小练习耗时 "
                    + (System.currentTimeMillis() - start) + "ms");
        }
    }

    private static <T> T sendAndParseWithRetry(
            String url,
            String apiKey,
            String requestBody,
            String requestId,
            String operation,
            StructuredParser<T> parser)
            throws InterruptedException, ApiException, JsonResponseParser.ParseException {
        JsonResponseParser.ParseException lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String content = sendChatRequest(url, apiKey, requestBody, requestId);
                return parser.parse(content);
            } catch (JsonResponseParser.ParseException e) {
                lastError = e;
                LOG.warn("[" + requestId + "] " + operation + " JSON 解析失败（第"
                        + attempt + "次）: " + e.getMessage());
            }
        }
        throw lastError;
    }

    @FunctionalInterface
    private interface StructuredParser<T> {
        T parse(String content) throws JsonResponseParser.ParseException;
    }

    /** 构造 chat/completions 请求体（OpenAI 兼容）。 */
    private static String buildRequestBody(String model, String systemPrompt, String userPrompt) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", 0.3);
        body.addProperty("max_tokens", 2000);

        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);

        var messages = new com.google.gson.JsonArray();
        messages.add(sysMsg);
        messages.add(userMsg);
        body.add("messages", messages);
        return GSON.toJson(body);
    }

    /**
     * 发送 chat/completions 请求，返回 choices[0].message.content。
     * 业务异常（超时、HTTP 非 2xx、响应缺字段）抛 {@link ApiException}，不重试。
     */
    private static String sendChatRequest(String url, String apiKey, String requestBody, String requestId)
            throws InterruptedException, ApiException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response;
        try {
            response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            // HttpTimeoutException 是 IOException 子类，必须先于 IOException 捕获
            throw new ApiException("请求超时（" + (REQUEST_TIMEOUT_MS / 1000) + "秒）");
        } catch (java.io.IOException e) {
            throw new ApiException("网络错误：" + e.getMessage());
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = response.body();
            String hint = extractApiError(body);
            throw new ApiException("服务端返回 " + response.statusCode()
                    + (hint != null ? "：" + hint : ""));
        }

        return extractContent(response.body());
    }

    /** 从 chat/completions 响应里取 choices[0].message.content。 */
    private static String extractContent(String responseBody) throws ApiException {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            var choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new ApiException("响应缺少 choices（可能是内容触发安全策略）");
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null || !message.has("content")) {
                throw new ApiException("响应缺少 message.content");
            }
            return message.get("content").getAsString();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("响应解析失败：" + e.getMessage());
        }
    }

    /** 从错误响应里提取 message 字段（智谱错误格式），失败返回 null。 */
    private static String extractApiError(String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            if (root.has("error")) {
                JsonObject err = root.getAsJsonObject("error");
                if (err != null && err.has("message")) {
                    return err.get("message").getAsString();
                }
            }
            if (root.has("msg")) return root.get("msg").getAsString();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 设置页"测试连接"用 ====================

    /** 测试连接结果。 */
    public static final class TestResult {
        public final boolean success;
        public final String message;
        public TestResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    /**
     * 用指定配置发一个最小请求，验证连通性。供设置页"测试连接"调用。
     * 必须在后台线程调用（HTTP + 阻塞）。
     */
    public static TestResult testConnection(String url, String apiKey, String model) {
        try {
            String body = buildRequestBody(model, PromptBuilder.buildSystemPrompt(), "回复 OK 即可");
            String content = sendChatRequest(url, apiKey, body, "test");
            return new TestResult(true, "模型：" + model + "，返回长度 " + content.length());
        } catch (Exception e) {
            return new TestResult(false, e.getMessage());
        }
    }

    /** 业务异常：HTTP 错误、超时、响应缺失字段等，不触发重试。 */
    private static final class ApiException extends Exception {
        public ApiException(String message) { super(message); }
    }
}
