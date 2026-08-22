package com.codementor.learning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgePointNormalizerTest {

    @Test
    void mergesFormattingAndConservativeSemanticAliases() {
        assertEquals(
                KnowledgePointNormalizer.key("Spring 事务"),
                KnowledgePointNormalizer.key("spring-事务"));
        assertEquals(
                KnowledgePointNormalizer.key("NullPointerException"),
                KnowledgePointNormalizer.key("空指针异常"));
        assertEquals(
                KnowledgePointNormalizer.key("NullPointerException"),
                KnowledgePointNormalizer.key("NullPointerException（NPE）"));
        assertEquals(
                KnowledgePointNormalizer.key("@Transactional"),
                KnowledgePointNormalizer.key("Spring 声明式事务"));
    }
}
