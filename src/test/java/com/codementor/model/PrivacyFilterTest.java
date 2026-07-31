package com.codementor.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivacyFilterTest {

    @Test
    void masksCommonSecretsBeforePreviewOrUpload() {
        String source = """
                String password = "plain-password";
                String token: 'abc-token';
                String jdbc = "jdbc:mysql://localhost/db?user=a&password=db-secret";
                -----BEGIN PRIVATE KEY-----
                private material
                -----END PRIVATE KEY-----
                """;

        String masked = PrivacyFilter.mask(source);

        assertFalse(masked.contains("plain-password"));
        assertFalse(masked.contains("abc-token"));
        assertFalse(masked.contains("db-secret"));
        assertFalse(masked.contains("private material"));
        assertTrue(masked.contains("***"));
    }
}
