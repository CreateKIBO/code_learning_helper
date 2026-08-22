package com.codementor.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelContextBuilderTest {

    @Test
    void includesStructuredMetadataAndTargetCode() {
        ModelContextBuilder.Result result = ModelContextBuilder.build(
                new ModelContextBuilder.Input(
                        "package demo;",
                        "import java.nio.file.Files;",
                        "class Demo",
                        List.of("private final Service service;"),
                        "Demo#load",
                        "String load() { return service.load(); }",
                        List.of("WARNING：返回值可能为空"),
                        List.of("Java IO")));

        assertFalse(result.truncated());
        assertTrue(result.text().contains("private final Service service;"));
        assertTrue(result.text().contains("IDEA 诊断摘要"));
        assertTrue(result.text().contains("技术标签：Java IO"));
        assertTrue(result.text().contains("String load()"));
    }

    @Test
    void truncatesMetadataBeforeTargetCode() {
        String largeImports = "import demo.Type;\n".repeat(2_000);
        String targetCode = "int calculate() { return 42; }";

        ModelContextBuilder.Result result = ModelContextBuilder.build(
                new ModelContextBuilder.Input(
                        "package demo;",
                        largeImports,
                        "class Demo",
                        List.of(),
                        "Demo#calculate",
                        targetCode,
                        List.of(),
                        List.of()));

        assertTrue(result.truncated());
        assertTrue(result.text().contains("补充上下文已按字符预算截断"));
        assertTrue(result.text().endsWith(targetCode));
    }

    @Test
    void explicitlyMarksAnOversizedTargetAsTruncated() {
        String targetHead = "HEAD_OF_TARGET";
        String omittedMiddle = "MIDDLE_SHOULD_NOT_SURVIVE";
        String targetTail = "TAIL_OF_TARGET";
        String targetCode = targetHead
                + "x".repeat(ModelContextBuilder.MAX_CONTEXT_CHARS)
                + omittedMiddle
                + "y".repeat(ModelContextBuilder.MAX_CONTEXT_CHARS)
                + targetTail;

        ModelContextBuilder.Result result = ModelContextBuilder.build(
                new ModelContextBuilder.Input(
                        "",
                        "",
                        "",
                        List.of(),
                        "Demo#large",
                        targetCode,
                        List.of(),
                        List.of()));

        assertTrue(result.truncated());
        assertTrue(result.text().contains("目标代码中段已按字符预算截断"));
        assertTrue(result.text().startsWith("// 分析目标：Demo#large\n" + targetHead));
        assertTrue(result.text().endsWith(targetTail));
        assertFalse(result.text().contains(omittedMiddle));
        assertTrue(result.text().length() <= ModelContextBuilder.MAX_CONTEXT_CHARS);
    }

    @Test
    void boundsAnOversizedReferencedFieldBeforeAssembly() {
        String targetCode = "void run() {}";
        ModelContextBuilder.Result result = ModelContextBuilder.build(
                new ModelContextBuilder.Input(
                        "",
                        "",
                        "class Demo",
                        List.of("private String data = \"" + "x".repeat(50_000) + "\";"),
                        "Demo#run",
                        targetCode,
                        List.of(),
                        List.of()));

        assertTrue(result.truncated());
        assertTrue(result.text().length() <= ModelContextBuilder.MAX_CONTEXT_CHARS);
        assertTrue(result.text().endsWith(targetCode));
    }
}
