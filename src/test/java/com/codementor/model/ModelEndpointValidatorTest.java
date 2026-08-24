package com.codementor.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelEndpointValidatorTest {

    @Test
    void acceptsAndNormalizesHttpsEndpoint() {
        ModelEndpointValidator.Result result = ModelEndpointValidator.validate(
                " HTTPS://OPEN.BIGMODEL.CN/api/paas/v4/../v4/chat/completions ", false);

        assertTrue(result.valid());
        assertEquals(
                "https://open.bigmodel.cn/api/paas/v4/chat/completions",
                result.normalizedEndpoint());
    }

    @Test
    void resolvesOpenAiCompatibleBaseUrlsWithoutDuplicatingTheRoute() {
        assertEquals(
                "https://api.deepseek.com/chat/completions",
                ModelEndpointValidator.validate(
                        "https://api.deepseek.com", false).normalizedEndpoint());
        assertEquals(
                "https://api.deepseek.com/v1/chat/completions",
                ModelEndpointValidator.validate(
                        "https://api.deepseek.com/v1/", false).normalizedEndpoint());
        assertEquals(
                "https://api.deepseek.com/beta/chat/completions",
                ModelEndpointValidator.validate(
                        "https://api.deepseek.com/beta", false).normalizedEndpoint());
        assertEquals(
                "https://api.deepseek.com/v1/chat/completions",
                ModelEndpointValidator.validate(
                        "https://api.deepseek.com/v1/chat/completions/", false)
                        .normalizedEndpoint());
        assertEquals(
                "https://example.com/custom/generate",
                ModelEndpointValidator.validate(
                        "https://example.com/custom/generate", false)
                        .normalizedEndpoint());
    }

    @Test
    void rejectsCredentialBearingOrAmbiguousEndpoints() {
        assertFalse(ModelEndpointValidator.validate(
                "https://user:secret@example.com/chat", false).valid());
        assertFalse(ModelEndpointValidator.validate(
                "https://example.com/chat?token=x", false).valid());
        assertFalse(ModelEndpointValidator.validate(
                "https://example.com/chat#fragment", false).valid());
    }

    @Test
    void permitsOnlyExplicitLoopbackHttp() {
        assertFalse(ModelEndpointValidator.validate(
                "http://127.0.0.1:8080/chat", false).valid());
        assertTrue(ModelEndpointValidator.validate(
                "http://127.0.0.2:8080/chat", true).valid());
        assertTrue(ModelEndpointValidator.validate(
                "http://localhost:8080/chat", true).valid());
        assertTrue(ModelEndpointValidator.validate(
                "http://[::1]:8080/chat", true).valid());
        assertTrue(ModelEndpointValidator.validate(
                "http://[0:0:0:0:0:0:0:1]:8080/chat", true).valid());
        assertFalse(ModelEndpointValidator.validate(
                "http://example.com/chat", true).valid());
        assertFalse(ModelEndpointValidator.validate(
                "http://localhost.example.com/chat", true).valid());
    }

    @Test
    void validatesModelName() {
        assertEquals("", ModelEndpointValidator.validateModel("glm-5.2"));
        assertEquals("", ModelEndpointValidator.validateModel("provider/model:v1"));
        assertFalse(ModelEndpointValidator.validateModel("bad model").isEmpty());
        assertFalse(ModelEndpointValidator.validateModel("model\nname").isEmpty());
    }

    @Test
    void rejectsRetiredModelNamesOnlyForTheOfficialDeepSeekEndpoint() {
        String officialEndpoint = "https://api.deepseek.com/chat/completions";
        assertTrue(ModelEndpointValidator.validateModelForEndpoint(
                "deepseek-chat", officialEndpoint).contains("已停用"));
        assertTrue(ModelEndpointValidator.validateModelForEndpoint(
                "deepseek-reasoner", officialEndpoint).contains("已停用"));
        assertEquals("", ModelEndpointValidator.validateModelForEndpoint(
                "deepseek-v4-flash", officialEndpoint));
        assertEquals("", ModelEndpointValidator.validateModelForEndpoint(
                "deepseek-chat", "https://proxy.example.com/v1/chat/completions"));
    }
}
