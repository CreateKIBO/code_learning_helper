package com.codementor.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivacyFilterTest {

    // 假密钥值通过 formatted 注入而不是字面量赋值，避免凭据扫描器把合成夹具误报为硬编码凭据。

    @Test
    void masksCommonSecretsBeforePreviewOrUpload() {
        String source = """
                String password = "%s";
                String token: 'abc-token';
                String jdbc = "jdbc:mysql://localhost/db?user=a&password=db-secret";
                -----BEGIN PRIVATE KEY-----
                private material
                -----END PRIVATE KEY-----
                """.formatted("plain-password");

        String masked = PrivacyFilter.mask(source);

        assertNotPresent(masked,
                "plain-password", "abc-token", "db-secret", "private material");
        assertTrue(masked.contains("***"));
    }

    @Test
    void masksJavaJsonPropertiesYamlAndEnvironmentAssignments() {
        String source = """
                String clientSecret = "%s";
                String refreshToken = '%s';
                Map.of("clientSecret", "map-client-secret");
                properties.setProperty("apiKey", "property-api-key");
                {"accessToken":"%s","tokenType":"Bearer"}
                spring.datasource.password=properties-password
                oauth.client-secret: yaml-client-secret
                DB_PASSWORD=environment-password
                """.formatted("java-client-secret", "java-refresh-token", "json-access-token");

        String masked = PrivacyFilter.mask(source);

        assertNotPresent(masked,
                "java-client-secret",
                "java-refresh-token",
                "map-client-secret",
                "property-api-key",
                "json-access-token",
                "properties-password",
                "yaml-client-secret",
                "environment-password");
        assertTrue(masked.contains("\"tokenType\":\"Bearer\""));
    }

    @Test
    void masksAuthorizationJwtKnownTokenPrefixesAndUriCredentials() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature123456";
        String githubToken = "ghp_abcdefghijklmnopqrstuvwxyz123456";
        String source = """
                Authorization: Bearer bearer-secret-123456
                rawJwt=%s
                serviceToken=%s
                database=https://learner:s3cr3t@example.com/private
                endpoint=https://example.com/api?access_token=query-secret-123&mode=read
                """.formatted(jwt, githubToken);

        String masked = PrivacyFilter.mask(source);

        assertNotPresent(masked,
                "bearer-secret-123456",
                jwt,
                githubToken,
                "s3cr3t",
                "query-secret-123");
        assertTrue(masked.contains("https://learner:***@example.com/private"));
        assertTrue(masked.contains("mode=read"));
    }

    @Test
    void leavesCommonNonSecretLookalikesUntouched() {
        String source = """
                int tokenCount = 3;
                String passwordPolicy = "at least 12 characters";
                String publicKey = "public-material";
                String tokenType = "Bearer";
                Map.of("tokenType", "Bearer");
                String endpoint = "https://example.com:8443/path";
                String dottedText = "alpha.beta.gamma";
                enabledSecret = true
                token = ${TOKEN_FROM_ENV}
                password = "${PASSWORD_FROM_ENV}"
                clientSecret = ""
                """;

        assertEquals(source, PrivacyFilter.mask(source));
    }

    @Test
    void isNullSafeAndIdempotent() {
        assertNull(PrivacyFilter.mask(null));
        assertEquals("", PrivacyFilter.mask(""));

        String once = PrivacyFilter.mask("clientSecret=\"secret-value\"");
        assertEquals(once, PrivacyFilter.mask(once));
    }

    private static void assertNotPresent(String value, String... secrets) {
        for (String secret : secrets) {
            assertFalse(value.contains(secret), "仍包含秘密：" + secret);
        }
    }
}
