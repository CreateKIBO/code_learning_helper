package com.codementor.model;

import com.codementor.review.ReviewContext;
import com.codementor.review.ReviewResult;
import com.codementor.review.ReviewService;
import com.codementor.settings.CodeMentorSettings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZhiPuModelClientReviewTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void mentorReviewRecoversFromEmptyProviderContent() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        List<String> requestBodies = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            requestBodies.add(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (attempts.incrementAndGet() == 1) {
                respond(exchange, reasoningOnlyResponse());
            } else {
                respond(exchange, successResponse(
                        "{\"summary\":\"这个小方法职责清晰。\",\"suggestions\":[]}"));
            }
        });
        server.start();

        ModelRequestConfig config = new ModelRequestConfig(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/chat",
                "test-model",
                CodeMentorSettings.TeachingLevel.JAVAWEB,
                ModelRequestConfig.CURRENT_PROMPT_SCHEMA);
        ZhiPuModelClient client = new ZhiPuModelClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                () -> "test-key");
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<ReviewResult> result = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();

        client.review(config, context(), "review-empty-retry", new ReviewService.Callback() {
            @Override
            public void onSuccess(ReviewResult reviewResult) {
                result.set(reviewResult);
                completed.countDown();
            }

            @Override
            public void onFailure(String errorMessage) {
                failure.set(errorMessage);
                completed.countDown();
            }
        });

        assertTrue(completed.await(5, TimeUnit.SECONDS), "mentor callback timed out");
        assertNull(failure.get(), failure.get());
        assertNotNull(result.get());
        assertEquals("这个小方法职责清晰。", result.get().summary());
        assertEquals(2, attempts.get());
        assertEquals(2, requestBodies.size());
        assertTrue(requestBodies.get(1).contains("完整 JSON"));
    }

    private static ReviewContext context() {
        String code = "int add(int left, int right) { return left + right; }";
        return new ReviewContext(
                "src/Demo.java",
                "Demo#add(int,int)",
                code,
                "class Demo { " + code + " }",
                false,
                1L,
                "fingerprint",
                13,
                64);
    }

    private static String reasoningOnlyResponse() {
        return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{"
                + "\"reasoning_content\":\"internal reasoning\",\"content\":\"\"}}]}";
    }

    private static String successResponse(String content) {
        return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":"
                + jsonString(content) + "}}]}";
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
