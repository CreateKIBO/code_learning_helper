package com.codementor.privacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileExclusionPolicyTest {

    @Test
    void blocksBuiltInSensitiveAndGeneratedPaths() {
        FileExclusionPolicy policy = new FileExclusionPolicy("");

        assertTrue(policy.evaluate(".env").excluded());
        assertTrue(policy.evaluate("config/client.pem").excluded());
        assertTrue(policy.evaluate("CONFIG/CLIENT.PEM").excluded());
        assertTrue(policy.evaluate("build/generated/Secret.java").excluded());
        assertTrue(policy.evaluate("module/target/generated/Dto.java").excluded());
    }

    @Test
    void appliesCustomPatternsAndIgnoresComments() {
        FileExclusionPolicy policy = new FileExclusionPolicy("""
                # personal rules
                **/generated/**
                *Credential.java;**/private/**
                """);

        assertEquals(
                "**/generated/**",
                policy.evaluate("src/generated/Mapper.java").matchedPattern());
        assertTrue(policy.evaluate("src/AuthCredential.java").excluded());
        assertTrue(policy.evaluate("module/private/KeyHolder.java").excluded());
        assertFalse(policy.evaluate("src/main/java/demo/UserService.java").excluded());
    }
}
