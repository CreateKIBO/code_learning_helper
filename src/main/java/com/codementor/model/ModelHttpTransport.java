package com.codementor.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Bounded HTTP transport for OpenAI-compatible chat completion endpoints. */
final class ModelHttpTransport {

    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_CONTENT_CHARS = 64 * 1024;
    private static final int MAX_ERROR_CHARS = 300;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient client;

    ModelHttpTransport(HttpClient client) {
        this.client = client;
    }

    String send(
            ModelRequestConfig config,
            String apiKey,
            String requestBody)
            throws InterruptedException, ApiException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(config.endpointUri())
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        requestBody, StandardCharsets.UTF_8))
                .build();

        final HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ApiException("请求超时（30 秒）");
        } catch (IOException e) {
            throw new ApiException("网络连接失败");
        }

        long declaredLength = response.headers()
                .firstValueAsLong("Content-Length")
                .orElse(-1L);
        try (InputStream body = response.body()) {
            if (declaredLength > MAX_RESPONSE_BYTES) {
                throw new ApiException("模型响应超过安全大小限制");
            }
            byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new ApiException("模型响应超过安全大小限制");
            }
            String responseBody = new String(bytes, StandardCharsets.UTF_8);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String hint = extractApiError(responseBody);
                throw new ApiException("服务端返回 " + response.statusCode()
                        + (hint.isEmpty() ? "" : "：" + hint));
            }
            return extractContent(responseBody);
        } catch (IOException e) {
            throw new ApiException("读取模型响应失败");
        }
    }

    private static String extractContent(String responseBody) throws ApiException {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            var choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new ApiException("响应缺少 choices（内容可能被安全策略拦截）");
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null
                    || !message.has("content")
                    || !message.get("content").isJsonPrimitive()) {
                throw new ApiException("响应缺少文本 message.content");
            }
            String content = message.get("content").getAsString();
            if (content.length() > MAX_CONTENT_CHARS) {
                throw new ApiException("模型输出内容超过安全大小限制");
            }
            return content;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("模型响应 JSON 格式不正确");
        }
    }

    private static String extractApiError(String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            String message = "";
            if (root.has("error") && root.get("error").isJsonObject()) {
                JsonObject error = root.getAsJsonObject("error");
                if (error.has("message") && error.get("message").isJsonPrimitive()) {
                    message = error.get("message").getAsString();
                }
            } else if (root.has("msg") && root.get("msg").isJsonPrimitive()) {
                message = root.get("msg").getAsString();
            }
            return sanitize(message);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String sanitize(String value) {
        if (value == null) return "";
        String printable = value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return printable.length() <= MAX_ERROR_CHARS
                ? printable
                : printable.substring(0, MAX_ERROR_CHARS) + "…";
    }

    static final class ApiException extends Exception {
        ApiException(String message) {
            super(message);
        }
    }
}
