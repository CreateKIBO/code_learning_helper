package com.codementor.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TechnologyTagDetectorTest {

    @Test
    void detectsSpringValidationAndTransactionInStableOrder() {
        List<String> tags = TechnologyTagDetector.detect("""
                import org.springframework.web.bind.annotation.RestController;
                import jakarta.validation.Valid;
                import org.springframework.transaction.annotation.Transactional;
                @RestController
                @Transactional
                """);

        assertEquals(
                List.of("Spring MVC", "Bean Validation", "Spring Transaction"),
                tags);
    }

    @Test
    void detectsPersistenceTestingAndCoreJavaTechnologies() {
        List<String> tags = TechnologyTagDetector.detect("""
                import org.apache.ibatis.annotations.Mapper;
                import org.junit.jupiter.api.Test;
                import java.nio.file.Files;
                import java.util.concurrent.CompletableFuture;
                """);

        assertTrue(tags.contains("MyBatis"));
        assertTrue(tags.contains("JUnit"));
        assertTrue(tags.contains("Java IO"));
        assertTrue(tags.contains("Java Concurrency"));
    }
}
