package com.codementor.privacy;

import com.codementor.javaweb.JavaWebContext;
import com.codementor.review.ReviewContext;
import com.codementor.review.ReviewMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SendPreviewFormatterTest {

    @Test
    void masksCodeAndProjectRulesInOnePreview() {
        ReviewContext context = new ReviewContext(
                "src/Demo.java",
                "Demo#run",
                "void run() {}",
                "String password = \"secret-value\";",
                false,
                1L,
                "fingerprint",
                0,
                10,
                JavaWebContext.unknown(),
                "apiKey = \"project-secret\"",
                ReviewMode.EXPLAIN,
                List.of("Java IO"),
                List.of());

        String preview = SendPreviewFormatter.format(context, 10_000);

        assertTrue(preview.contains(".codementor/rules.md"));
        assertFalse(preview.contains("src/Demo.java"));
        assertTrue(preview.contains("***"));
        assertFalse(preview.contains("secret-value"));
        assertFalse(preview.contains("project-secret"));
    }
}
