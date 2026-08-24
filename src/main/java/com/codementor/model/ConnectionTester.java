package com.codementor.model;

import com.codementor.review.ReviewResult;
import com.codementor.settings.CodeMentorSettings;
import com.intellij.openapi.diagnostic.Logger;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 设置页连接测试：发送一个固定、合成的导师 JSON 协议探测请求。
 * 不读取项目源码、规则、历史或会话内容；成功即代表网络、鉴权、模型、
 * JSON Output、最终 content 和基础解析全部可用。
 */
public final class ConnectionTester {

    private static final Logger LOG = Logger.getInstance(ConnectionTester.class);

    static final Duration CONNECTION_TEST_TIMEOUT = Duration.ofSeconds(30);

    /** Java 17 的 HttpClient 无法显式关闭；连接测试复用同一实例，避免反复点测试累积线程。 */
    private static volatile HttpClient client;

    private ConnectionTester() {}

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
                    // 连接测试不依赖教学层级，仅需要一个合法占位值。
                    CodeMentorSettings.TeachingLevel.JAVAWEB,
                    ModelRequestConfig.CURRENT_PROMPT_SCHEMA);
            ModelHttpTransport transport =
                    new ModelHttpTransport(sharedClient(), CONNECTION_TEST_TIMEOUT);
            String body = ZhiPuModelClient.buildRequestBody(
                    config,
                    "你正在执行 CodeMentor 连接兼容性测试，不分析真实项目。"
                            + "只输出严格 JSON："
                            + "{\"summary\":\"连接正常\",\"suggestions\":[]}",
                    "请返回上述 JSON，不要 markdown 或额外文字。",
                    true);
            ReviewResult result = ZhiPuModelClient.sendAndParseForConnectionTest(
                    transport,
                    config,
                    apiKey.trim(),
                    body,
                    "connection",
                    "导师协议测试");
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

    private static HttpClient sharedClient() {
        HttpClient current = client;
        if (current != null) return current;
        synchronized (ConnectionTester.class) {
            if (client == null) {
                client = HttpClient.newBuilder()
                        .connectTimeout(ZhiPuModelClient.CONNECT_TIMEOUT)
                        .build();
            }
            return client;
        }
    }

    public static final class TestResult {
        public final boolean success;
        public final String message;

        public TestResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
