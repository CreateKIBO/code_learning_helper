package com.codementor.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationTargetIdentityTest {

    @Test
    void acceptsSignatureChangeForSamePsiMethodIdentity() {
        ReviewContext original = context(
                "Demo#handle(java.util.List)", 10, "Demo#method-index-0");
        ReviewContext changed = context(
                "Demo#handle(java.util.List<java.lang.String>)",
                10,
                "Demo#method-index-0");

        assertTrue(VerificationCoordinator.isSameTarget(original, changed));
    }

    @Test
    void rejectsAnotherOverloadAndAllSelectionTargets() {
        ReviewContext original = context(
                "Demo#handle(java.lang.String)", 10, "Demo#method-index-0");
        ReviewContext otherOverload = context(
                "Demo#handle(int)", 30, "Demo#method-index-1");
        ReviewContext selection = context("选中代码", -1, "");

        assertFalse(VerificationCoordinator.isSameTarget(original, otherOverload));
        assertFalse(VerificationCoordinator.isSameTarget(selection, selection));
    }

    @Test
    void verificationInputIncludesReferencedContextNotOnlyMethodFingerprint() {
        ReviewContext original = context(
                "Demo#handle()", 10, "Demo#method-index-0", "字段: List names");
        ReviewContext fieldChanged = context(
                "Demo#handle()", 10, "Demo#method-index-0", "字段: List<String> names");
        ReviewContext unchanged = context(
                "Demo#handle()", 10, "Demo#method-index-0", "字段: List names");

        assertTrue(VerificationCoordinator.hasVerificationInputChanged(
                original, fieldChanged));
        assertFalse(VerificationCoordinator.hasVerificationInputChanged(
                original, unchanged));
    }

    private static ReviewContext context(
            String targetName, int methodStart, String targetIdentity) {
        return context(targetName, methodStart, targetIdentity, "void handle() {}");
    }

    private static ReviewContext context(
            String targetName,
            int methodStart,
            String targetIdentity,
            String modelContext) {
        return new ReviewContext(
                "src/Demo.java",
                targetName,
                "void handle() {}",
                modelContext,
                false,
                1L,
                "fingerprint",
                methodStart,
                methodStart < 0 ? -1 : methodStart + 16)
                .withTargetIdentity(targetIdentity);
    }
}
