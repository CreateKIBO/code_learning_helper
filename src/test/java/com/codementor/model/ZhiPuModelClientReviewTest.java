package com.codementor.model;

import com.codementor.review.ReviewContext;
import com.codementor.review.ReviewResult;
import com.codementor.review.ReviewService;
import com.codementor.review.ReviewMode;
import com.codementor.privacy.SendPreviewFormatter;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void nextStepMasksGoalAndRejectsEvidenceOutsideTheSelectionBeforeRetrying() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        List<String> bodies = new CopyOnWriteArrayList<>();
        String target = "return left + right;";
        String background = "int add(int left, int right)";
        ReviewContext context = new ReviewContext("src/Demo.java", "选中代码", target,
                "// 所在方法背景\n" + background + "\n// 分析目标：选中代码\n" + target,
                false, 1L, "selection", -1, -1)
                .withReviewMode(ReviewMode.NEXT_STEP)
                .withProjectRules("本次学习目标：添加边界校验。apiKey = \"goal-secret-value\"");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            JsonObject task = new JsonObject();
            task.addProperty("category", "JAVA_USAGE");
            task.addProperty("certainty", "MEDIUM");
            task.addProperty("title", "明确边界输入");
            task.addProperty("evidence", attempts.incrementAndGet() == 1 ? background : target);
            task.addProperty("hint", "极值输入会怎样？");
            task.addProperty("explanation", "先定义边界再动手实现。");
            task.addProperty("example", "");
            task.addProperty("knowledgePoint", "整数边界");
            task.addProperty("verificationCriteria", "列出正常值和极值的预期结果");
            task.addProperty("nextStep", "先写出两个边界输入的预期结果");
            task.addProperty("selfCheckQuestion", "相加溢出时你期望怎样处理？");
            JsonArray tasks = new JsonArray();
            tasks.add(task);
            JsonObject response = new JsonObject();
            response.addProperty("summary", "从当前加法语句出发明确边界");
            response.add("suggestions", tasks);
            respond(exchange, successResponse(response.toString()));
        });
        server.start();
        ZhiPuModelClient client = new ZhiPuModelClient(HttpClient.newHttpClient(), () -> "test-key");
        ModelRequestConfig config = new ModelRequestConfig(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/chat", "test-model",
                CodeMentorSettings.TeachingLevel.BEGINNER, ModelRequestConfig.CURRENT_PROMPT_SCHEMA);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ReviewResult> result = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        client.review(config, context, "next-step", new ReviewService.Callback() {
            @Override
            public void onSuccess(ReviewResult value) {
                result.set(value);
                done.countDown();
            }
            @Override
            public void onFailure(String message) {
                failure.set(message);
                done.countDown();
            }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertNull(failure.get(), failure.get());
        assertEquals(2, attempts.get());
        assertEquals(target, result.get().suggestions().get(0).getEvidence());
        assertFalse(SendPreviewFormatter.format(context, 32_000).contains("goal-secret-value"));
        for (String body : bodies) {
            assertTrue(body.contains("指导下一步编码"));
            assertTrue(body.contains("添加边界校验"));
            assertFalse(body.contains("goal-secret-value"));
        }
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
