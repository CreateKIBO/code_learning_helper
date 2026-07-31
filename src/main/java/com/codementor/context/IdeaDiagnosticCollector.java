package com.codementor.context;

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 读取 IDEA 已完成的当前范围错误/警告；不主动运行编译或 Inspection。 */
public final class IdeaDiagnosticCollector {

    private static final int MAX_DIAGNOSTICS = 5;

    private IdeaDiagnosticCollector() {}

    public static List<String> collect(
            Project project, Document document, int startOffset, int endOffset) {
        if (startOffset < 0 || endOffset <= startOffset) {
            return List.of();
        }
        Set<String> diagnostics = new LinkedHashSet<>();
        try {
            DaemonCodeAnalyzerEx.processHighlights(
                    document,
                    project,
                    HighlightSeverity.WARNING,
                    startOffset,
                    endOffset,
                    info -> collect(info, diagnostics));
        } catch (RuntimeException ignored) {
            // 索引未就绪或 Daemon 正在重启时没有可靠摘要；评审仍可继续。
            return List.of();
        }
        return diagnostics.stream().limit(MAX_DIAGNOSTICS).toList();
    }

    private static boolean collect(HighlightInfo info, Set<String> diagnostics) {
        String description = info.getDescription();
        if (description != null && !description.isBlank()) {
            diagnostics.add(info.getSeverity().getName() + "：" + description.strip());
        }
        return diagnostics.size() < MAX_DIAGNOSTICS;
    }
}
