package com.codementor.context;

import java.util.ArrayList;
import java.util.List;

/**
 * 按固定优先级组装发送给模型的有界上下文。
 *
 * <p>目标代码优先保留；只有目标本身超出预算时才保留首尾并明确标记中段截断。
 */
public final class ModelContextBuilder {

    public static final int MAX_CONTEXT_CHARS = 16_000;
    private static final String TRUNCATED_MARKER =
            "// ... 补充上下文已按字符预算截断 ...\n\n";
    private static final String TARGET_TRUNCATED_MARKER =
            "\n// ... 目标代码中段已按字符预算截断 ...\n";

    private ModelContextBuilder() {}

    public static Result build(Input input) {
        String targetHeader = "// 分析目标：" + input.targetName + "\n";
        if (targetHeader.length() + input.targetCode.length() > MAX_CONTEXT_CHARS) {
            int codeBudget = Math.max(
                    0,
                    MAX_CONTEXT_CHARS
                            - targetHeader.length()
                            - TARGET_TRUNCATED_MARKER.length());
            int headBudget = codeBudget * 2 / 3;
            int tailBudget = codeBudget - headBudget;
            String text = targetHeader
                    + input.targetCode.substring(0, headBudget)
                    + TARGET_TRUNCATED_MARKER
                    + input.targetCode.substring(input.targetCode.length() - tailBudget);
            return new Result(text, true);
        }
        String targetBlock = targetHeader + input.targetCode;

        List<String> sections = optionalSections(input);
        int metadataBudget = MAX_CONTEXT_CHARS - targetBlock.length();
        StringBuilder metadata = new StringBuilder();
        boolean truncated = false;
        for (String section : sections) {
            if (section.isBlank()) continue;
            if (metadata.length() + section.length() <= metadataBudget) {
                metadata.append(section);
                continue;
            }
            truncated = true;
            int remaining = metadataBudget
                    - metadata.length()
                    - TRUNCATED_MARKER.length();
            if (remaining > 0) {
                metadata.append(section, 0, Math.min(remaining, section.length()));
            }
            if (metadataBudget - metadata.length() >= TRUNCATED_MARKER.length()) {
                metadata.append(TRUNCATED_MARKER);
            }
            break;
        }
        return new Result(metadata.append(targetBlock).toString(), truncated);
    }

    private static List<String> optionalSections(Input input) {
        List<String> sections = new ArrayList<>();
        addSection(sections, input.packageDeclaration);
        addSection(sections, input.imports);

        StringBuilder typeSummary = new StringBuilder();
        if (!input.classDeclaration.isBlank()) {
            appendBounded(typeSummary, input.classDeclaration + " {\n");
            for (String field : input.referencedFields) {
                appendBounded(typeSummary, "  " + field + '\n');
                if (typeSummary.length() >= MAX_CONTEXT_CHARS) break;
            }
            appendBounded(typeSummary, "}\n");
        }
        addSection(sections, typeSummary.toString());
        if (!input.diagnostics.isEmpty()) {
            addSection(
                    sections,
                    "// IDEA 诊断摘要（只用于解释，不要重复冒充新发现）：\n// - "
                            + String.join("\n// - ", input.diagnostics));
        }
        if (!input.technologyTags.isEmpty()) {
            addSection(
                    sections,
                    "// 技术标签：" + String.join("、", input.technologyTags));
        }
        return sections;
    }

    private static void addSection(List<String> sections, String content) {
        if (content == null || content.isBlank()) return;
        String normalized = content.strip();
        if (normalized.length() > MAX_CONTEXT_CHARS) {
            normalized = normalized.substring(0, MAX_CONTEXT_CHARS);
        }
        sections.add(normalized + "\n\n");
    }

    private static void appendBounded(StringBuilder target, String value) {
        if (target.length() >= MAX_CONTEXT_CHARS || value == null || value.isEmpty()) return;
        int remaining = MAX_CONTEXT_CHARS - target.length();
        target.append(value, 0, Math.min(remaining, value.length()));
    }

    public static final class Input {
        private final String packageDeclaration;
        private final String imports;
        private final String classDeclaration;
        private final List<String> referencedFields;
        private final String targetName;
        private final String targetCode;
        private final List<String> diagnostics;
        private final List<String> technologyTags;

        public Input(String packageDeclaration,
                     String imports,
                     String classDeclaration,
                     List<String> referencedFields,
                     String targetName,
                     String targetCode,
                     List<String> diagnostics,
                     List<String> technologyTags) {
            this.packageDeclaration = safe(packageDeclaration);
            this.imports = safe(imports);
            this.classDeclaration = safe(classDeclaration);
            this.referencedFields = List.copyOf(referencedFields);
            this.targetName = safe(targetName);
            this.targetCode = safe(targetCode);
            this.diagnostics = List.copyOf(diagnostics);
            this.technologyTags = List.copyOf(technologyTags);
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }

    public record Result(String text, boolean truncated) {}
}
