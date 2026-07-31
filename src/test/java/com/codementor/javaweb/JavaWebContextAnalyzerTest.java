package com.codementor.javaweb;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaWebContextAnalyzerTest {

    @Test
    void recognizesControllerAndItsWebConcerns() {
        JavaWebContext context = JavaWebContextAnalyzer.classify(
                "UserController",
                Set.of("org.springframework.web.bind.annotation.RestController",
                        "org.springframework.web.bind.annotation.PostMapping",
                        "jakarta.validation.Valid"),
                Set.of("org.springframework.web.bind.annotation.RequestBody"));

        assertEquals(JavaWebRole.CONTROLLER, context.getRole());
        assertTrue(context.getConcerns().contains("HTTP / REST"));
        assertTrue(context.getConcerns().contains("参数校验"));
    }

    @Test
    void recognizesRepositoryFromClassNaming() {
        JavaWebContext context = JavaWebContextAnalyzer.classify(
                "OrderMapper",
                Set.of(),
                Set.of("org.apache.ibatis.annotations.Mapper"));

        assertEquals(JavaWebRole.REPOSITORY, context.getRole());
        assertTrue(context.getConcerns().contains("持久化"));
    }

    @Test
    void doesNotForcePlainClassIntoWebLayer() {
        JavaWebContext context = JavaWebContextAnalyzer.classify(
                "StringParser", Set.of(), Set.of("java.util.List"));

        assertEquals(JavaWebRole.UNKNOWN, context.getRole());
    }
}
