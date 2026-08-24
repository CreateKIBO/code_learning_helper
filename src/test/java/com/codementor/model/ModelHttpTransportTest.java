package com.codementor.model;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelHttpTransportTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsAuthorizationAndUtf8JsonBody() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, successResponse("{\"suggestions\":[]}"));
        });

        String body = ZhiPuModelClient.buildRequestBody(
                "glm-5.2", "system", "你好，Java", true);
        String content = new ModelHttpTransport(HttpClient.newHttpClient())
                .send(config(), "secret-key", body);

        assertEquals("Bearer secret-key", authorization.get());
        assertEquals(body, requestBody.get());
        assertEquals("{\"suggestions\":[]}", content);
        assertTrue(body.contains("\"response_format\""));
        assertTrue(body.contains("\"json_object\""));
    }

    @Test
    void rejectsOversizedResponseBeforeMaterializingIt() throws Exception {
        startServer(exchange -> {
            int length = ModelHttpTransport.MAX_RESPONSE_BYTES + 1;
            exchange.sendResponseHeaders(200, length);
            exchange.getResponseBody().close();
        });

        ModelHttpTransport.ApiException error = assertThrows(
                ModelHttpTransport.ApiException.class,
                () -> new ModelHttpTransport(HttpClient.newHttpClient())
                        .send(config(), "key", "{}"));

        assertTrue(error.getMessage().contains("安全大小限制"));
    }

    @Test
    void rejectsOversizedChunkedResponseWithoutContentLength() throws Exception {
        startServer(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(
                    new byte[ModelHttpTransport.MAX_RESPONSE_BYTES + 1]);
        });

        ModelHttpTransport.ApiException error = assertThrows(
                ModelHttpTransport.ApiException.class,
                () -> new ModelHttpTransport(HttpClient.newHttpClient())
                        .send(config(), "key", "{}"));

        assertTrue(error.getMessage().contains("安全大小限制"));
    }

    @Test
    void requestTimeoutCoversAResponseBodyThatStopsAfterHeaders() throws Exception {
        startServer(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });

        ModelHttpTransport.ApiException error = assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> assertThrows(
                        ModelHttpTransport.ApiException.class,
                        () -> new ModelHttpTransport(
                                HttpClient.newHttpClient(), Duration.ofMillis(150))
                                .send(config(), "key", "{}")));

        assertTrue(error.getMessage().contains("请求超时"));
    }

    @Test
    void returnsBoundedServerErrorWithoutLeakingWholeResponse() throws Exception {
        startServer(exchange -> respond(
                exchange,
                429,
                "{\"error\":{\"message\":\"" + "x".repeat(1_000) + "\"}}"));

        ModelHttpTransport.ApiException error = assertThrows(
                ModelHttpTransport.ApiException.class,
                () -> new ModelHttpTransport(HttpClient.newHttpClient())
                        .send(config(), "key", "{}"));

        assertTrue(error.getMessage().startsWith("服务端返回 429"));
        assertTrue(error.getMessage().length() < 340);
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private ModelRequestConfig config() {
        return new ModelRequestConfig(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/chat",
                "glm-5.2",
                CodeMentorSettings.TeachingLevel.JAVAWEB,
                ModelRequestConfig.CURRENT_PROMPT_SCHEMA);
    }

    private static String successResponse(String content) {
        return "{\"choices\":[{\"message\":{\"content\":"
                + jsonString(content) + "}}]}";
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
