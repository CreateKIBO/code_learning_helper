package com.codementor.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewContextTest {

    @Test
    void storedTargetCodeKeepsHeadAndTailWithinMemoryBound() {
        String head = "HEAD_SENTINEL";
        String middle = "MIDDLE_SENTINEL";
        String tail = "TAIL_SENTINEL";
        String code = head
                + "a".repeat(11_000)
                + middle
                + "b".repeat(11_000)
                + tail;

        ReviewContext context = new ReviewContext(
                "src/Demo.java",
                "Demo#large()",
                code,
                code,
                true,
                1L,
                "fingerprint",
                0,
                code.length());

        assertTrue(context.getCode().startsWith(head));
        assertTrue(context.getCode().endsWith(tail));
        assertFalse(context.getCode().contains(middle));
        assertTrue(context.getCode().length() <= 16_000);
    }
}
