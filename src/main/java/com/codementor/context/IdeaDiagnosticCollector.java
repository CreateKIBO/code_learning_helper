package com.codementor.context;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;

import java.util.List;

/**
 * Compatibility boundary for optional IDE diagnostics.
 *
 * <p>IntelliJ 2024.1 exposes the live daemon highlight snapshot only through implementation
 * classes. A distributable plugin must not bind to those unstable APIs, so CodeMentor currently
 * returns no daemon diagnostics and relies on the IDE to display its own errors. The model still
 * receives PSI-derived code, imports, fields and technology tags.
 */
public final class IdeaDiagnosticCollector {

    private IdeaDiagnosticCollector() {}

    public static List<String> collect(
            Project project, Document document, int startOffset, int endOffset) {
        return List.of();
    }
}
