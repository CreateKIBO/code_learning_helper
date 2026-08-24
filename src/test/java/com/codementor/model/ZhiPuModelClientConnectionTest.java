package com.codementor.model;

import com.codementor.settings.CodeMentorSettings;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZhiPuModelClientConnectionTest {

    private HttpServer server;

    @Test
    void mentorOperationsHaveMoreTimeThanTheMinimalConnectionProbe() {
        assertEquals(Duration.ofSeconds(30), ConnectionTester.CONNECTION_TEST_TIMEOUT);
        assertEquals(Duration.ofSeconds(120), ZhiPuModelClient.MENTOR_REQUEST_TIMEOUT);
        assertTrue(ZhiPuModelClient.MENTOR_REQUEST_TIMEOUT.compareTo(
                ConnectionTester.CONNECTION_TEST_TIMEOUT) > 0);
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void connectionTestResolvesAVersionedBaseUrlToChatCompletions() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"choices\":[{\"message\":{\"content\":"
                            + "\"{\\\"summary\\\":\\\"连接正常\\\","
                            + "\\\"suggestions\\\":[]}\"}}]}");
        });
        server.start();

        ConnectionTester.TestResult result = ConnectionTester.testConnection(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-key",
                "test-model",
                true);

        assertTrue(result.success, result.message);
        assertEquals("/v1/chat/completions", requestPath.get());
        assertTrue(requestBody.get().contains("\"response_format\""));
        assertTrue(result.message.contains("导师 JSON 协议可用"));
    }

    @Test
    void connectionTestRejectsRetiredOfficialDeepSeekModelsBeforeNetworkUse() {
        ConnectionTester.TestResult result = ConnectionTester.testConnection(
                "https://api.deepseek.com",
                "test-key",
                "deepseek-chat",
                false);

        assertFalse(result.success);
        assertTrue(result.message.contains("已停用"));
        assertTrue(result.message.contains("deepseek-v4-flash"));
    }

    @Test
    void officialDeepSeekStructuredRequestsDisableThinkingAndIncreaseOutputBudget() {
        ModelRequestConfig config = new ModelRequestConfig(
                "https://api.deepseek.com/chat/completions",
                "deepseek-v4-flash",
                CodeMentorSettings.TeachingLevel.JAVAWEB,
                ModelRequestConfig.CURRENT_PROMPT_SCHEMA);

        JsonObject body = JsonParser.parseString(ZhiPuModelClient.buildRequestBody(
                config, "system JSON schema", "user", true)).getAsJsonObject();

        assertEquals(8_192, body.get("max_tokens").getAsInt());
        assertEquals(
                "disabled",
                body.getAsJsonObject("thinking").get("type").getAsString());
    }

    @Test
    void protocolRetryActuallyChangesThePrompt() {
        ModelRequestConfig config = new ModelRequestConfig(
                "https://api.deepseek.com/chat/completions",
                "deepseek-v4-pro",
                CodeMentorSettings.TeachingLevel.JAVAWEB,
                ModelRequestConfig.CURRENT_PROMPT_SCHEMA);
        String original = ZhiPuModelClient.buildRequestBody(
                config, "system JSON schema", "review this", true);

        String retry = ZhiPuModelClient.buildProtocolRetryBody(original);
        JsonObject retryBody = JsonParser.parseString(retry).getAsJsonObject();
        String retryPrompt = retryBody.getAsJsonArray("messages")
                .get(1).getAsJsonObject().get("content").getAsString();

        assertTrue(retryPrompt.startsWith("review this"));
        assertTrue(retryPrompt.contains("完整 JSON"));
        assertEquals(0.1, retryBody.get("temperature").getAsDouble(), 0.0001);
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
