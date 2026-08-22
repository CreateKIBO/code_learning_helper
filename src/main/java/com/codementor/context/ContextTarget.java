package com.codementor.context;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;

/** Immutable snapshot of the editor target captured on the EDT before background work. */
public record ContextTarget(
        Document document,
        int selectionStart,
        int selectionEnd,
        int caretOffset,
        Integer preferredOffset) {

    public static ContextTarget capture(Editor editor, Integer preferredOffset) {
        int start = editor.getSelectionModel().getSelectionStart();
        int end = editor.getSelectionModel().getSelectionEnd();
        return new ContextTarget(
                editor.getDocument(),
                start,
                end,
                editor.getCaretModel().getOffset(),
                preferredOffset);
    }

    public boolean hasSelection() {
        return preferredOffset == null && selectionEnd > selectionStart;
    }

    public int effectiveOffset() {
        return preferredOffset == null ? caretOffset : preferredOffset;
    }
}
