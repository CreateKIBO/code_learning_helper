package com.codementor.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded HTTP transport for OpenAI-compatible chat completion endpoints. */
final class ModelHttpTransport {

    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_CONTENT_CHARS = 64 * 1024;
    private static final int MAX_ERROR_CHARS = 300;
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient client;
    private final Duration requestTimeout;

    ModelHttpTransport(HttpClient client) {
        this(client, DEFAULT_REQUEST_TIMEOUT);
    }

    ModelHttpTransport(HttpClient client, Duration requestTimeout) {
        this.client = client;
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        this.requestTimeout = requestTimeout;
    }

    String send(
            ModelRequestConfig config,
            String apiKey,
            String requestBody)
            throws InterruptedException, ApiException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(config.endpointUri())
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        requestBody, StandardCharsets.UTF_8))
                .build();

        AtomicBoolean responseTooLarge = new AtomicBoolean();
        AtomicReference<BoundedBodySubscriber> activeSubscriber = new AtomicReference<>();
        CompletableFuture<HttpResponse<byte[]>> responseFuture = client.sendAsync(
                request,
                responseInfo -> {
                    BoundedBodySubscriber subscriber = boundedBodySubscriber(
                            responseInfo, responseTooLarge);
                    activeSubscriber.set(subscriber);
                    return subscriber;
                });
        final HttpResponse<byte[]> response;
        try {
            response = responseFuture.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            cancelResponse(responseFuture, activeSubscriber.get());
            throw new ApiException("请求超时（" + timeoutLabel(requestTimeout) + "）");
        } catch (InterruptedException error) {
            cancelResponse(responseFuture, activeSubscriber.get());
            throw error;
        } catch (ExecutionException error) {
            if (responseTooLarge.get()) {
                throw new ApiException("模型响应超过安全大小限制");
            }
            Throwable cause = error.getCause();
            if (cause instanceof java.net.http.HttpTimeoutException) {
                throw new ApiException("请求超时（" + timeoutLabel(requestTimeout) + "）");
            }
            throw new ApiException("网络连接失败");
        }

        if (responseTooLarge.get()) {
            throw new ApiException("模型响应超过安全大小限制");
        }
        String responseBody = new String(response.body(), StandardCharsets.UTF_8);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String hint = extractApiError(responseBody);
            throw new ApiException(buildServerError(response.statusCode(), hint));
        }
        return extractContent(responseBody);
    }

    private static String buildServerError(int statusCode, String hint) {
        String message = "服务端返回 " + statusCode
                + (hint.isEmpty() ? "" : "：" + hint);
        if (statusCode == 404) {
            return message + "；未找到接口或模型，请检查最终请求地址"
                    + "是否以 /chat/completions 结尾，并确认模型名称受服务商支持";
        }
        return message;
    }

    private static BoundedBodySubscriber boundedBodySubscriber(
            HttpResponse.ResponseInfo responseInfo,
            AtomicBoolean responseTooLarge) {
        long declaredLength = responseInfo.headers()
                .firstValueAsLong("Content-Length")
                .orElse(-1L);
        return new BoundedBodySubscriber(
                MAX_RESPONSE_BYTES,
                responseTooLarge,
                declaredLength > MAX_RESPONSE_BYTES);
    }

    private static void cancelResponse(
            CompletableFuture<HttpResponse<byte[]>> responseFuture,
            BoundedBodySubscriber subscriber) {
        if (subscriber != null) subscriber.cancel();
        responseFuture.cancel(true);
    }

    private static String timeoutLabel(Duration timeout) {
        return timeout.compareTo(Duration.ofSeconds(1)) >= 0
                ? timeout.toSeconds() + " 秒"
                : timeout.toMillis() + " 毫秒";
    }

    private static String extractContent(String responseBody) throws ApiException {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            var choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new ApiException("响应缺少 choices（内容可能被安全策略拦截）");
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject message = choice.getAsJsonObject("message");
            if (message == null) {
                throw new ApiException("响应缺少文本 message.content");
            }
            var contentElement = message.get("content");
            if (contentElement == null || contentElement.isJsonNull()) {
                throw emptyContentException(choice, message);
            }
            if (!contentElement.isJsonPrimitive()
                    || !contentElement.getAsJsonPrimitive().isString()) {
                throw new ApiException("响应中的 message.content 不是文本");
            }
            String content = contentElement.getAsString();
            if (content.isBlank()) {
                throw emptyContentException(choice, message);
            }
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

    private static EmptyContentException emptyContentException(
            JsonObject choice, JsonObject message) {
        String finishReason = primitiveString(choice, "finish_reason");
        if ("length".equals(finishReason)) {
            return new EmptyContentException("模型输出达到上限，未产生最终答案");
        }
        if ("content_filter".equals(finishReason)) {
            return new EmptyContentException("模型未产生最终答案，可能被内容安全策略拦截");
        }
        if (!primitiveString(message, "reasoning_content").isBlank()) {
            return new EmptyContentException("模型只返回了推理过程，未产生最终答案");
        }
        return new EmptyContentException("模型返回的最终答案为空");
    }

    private static String primitiveString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        var value = object.get(key);
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : "";
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

    /** Completes only after the whole bounded body is consumed, so request timeout still applies. */
    private static final class BoundedBodySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private final AtomicBoolean responseTooLarge;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private volatile Flow.Subscription subscription;

        private BoundedBodySubscriber(
                int maxBytes,
                AtomicBoolean responseTooLarge,
                boolean rejectImmediately) {
            this.maxBytes = maxBytes;
            this.responseTooLarge = responseTooLarge;
            if (rejectImmediately) {
                responseTooLarge.set(true);
                body.complete(new byte[0]);
            }
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription newSubscription) {
            if (subscription != null) {
                newSubscription.cancel();
                return;
            }
            subscription = newSubscription;
            if (body.isDone()) {
                newSubscription.cancel();
            } else {
                newSubscription.request(1);
            }
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            try {
                for (ByteBuffer buffer : buffers) {
                    int chunkSize = buffer.remaining();
                    if (output.size() > maxBytes - chunkSize) {
                        responseTooLarge.set(true);
                        subscription.cancel();
                        body.complete(new byte[0]);
                        return;
                    }
                    byte[] chunk = new byte[chunkSize];
                    buffer.get(chunk);
                    output.writeBytes(chunk);
                }
                subscription.request(1);
            } catch (RuntimeException error) {
                subscription.cancel();
                body.completeExceptionally(error);
            }
        }

        @Override
        public void onError(Throwable error) {
            body.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            body.complete(output.toByteArray());
        }

        private void cancel() {
            Flow.Subscription active = subscription;
            if (active != null) active.cancel();
            body.cancel(true);
        }
    }

    static class ApiException extends Exception {
        ApiException(String message) {
            super(message);
        }
    }

    /** Retryable protocol response: HTTP succeeded but no final assistant text was produced. */
    static final class EmptyContentException extends ApiException {
        EmptyContentException(String message) {
            super(message);
        }
    }
}
