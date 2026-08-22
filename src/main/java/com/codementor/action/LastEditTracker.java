package com.codementor.action;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录每个打开文档最后一次真实编辑的位置。
 *
 * <p>保存触发会消费这个位置，并据此定位被修改的方法；如果没有可靠位置则跳过，
 * 避免把已经移走的光标所在方法误当成本次修改目标。
 */
public final class LastEditTracker implements EditorFactoryListener, Disposable {

    private static final Key<Integer> EDITOR_COUNT =
            Key.create("CodeMentor.openEditorCount");
    private static final Key<LastEdit> LAST_EDIT =
            Key.create("CodeMentor.lastEdit");
    private final Set<Document> trackedDocuments = ConcurrentHashMap.newKeySet();

    private final DocumentListener documentListener = new DocumentListener() {
        @Override
        public void documentChanged(@NotNull DocumentEvent event) {
            Document document = event.getDocument();
            document.putUserData(
                    LAST_EDIT,
                    new LastEdit(event.getOffset(), document.getModificationStamp()));
        }
    };

    @Override
    public void editorCreated(@NotNull EditorFactoryEvent event) {
        Document document = event.getEditor().getDocument();
        int editorCount = editorCount(document);
        if (editorCount == 0) {
            document.addDocumentListener(documentListener, this);
            trackedDocuments.add(document);
        }
        document.putUserData(EDITOR_COUNT, editorCount + 1);
    }

    @Override
    public void editorReleased(@NotNull EditorFactoryEvent event) {
        Document document = event.getEditor().getDocument();
        int remaining = Math.max(0, editorCount(document) - 1);
        if (remaining == 0) {
            document.removeDocumentListener(documentListener);
            document.putUserData(EDITOR_COUNT, null);
            document.putUserData(LAST_EDIT, null);
            trackedDocuments.remove(document);
        } else {
            document.putUserData(EDITOR_COUNT, remaining);
        }
    }

    /**
     * 消费与指定文档版本匹配的最后编辑位置。
     *
     * @return offset；没有可靠记录时返回 -1。
     */
    public static int consumeLastEditOffset(Document document, long expectedStamp) {
        LastEdit edit = document.getUserData(LAST_EDIT);
        if (edit == null || edit.documentStamp != expectedStamp) {
            return -1;
        }
        document.putUserData(LAST_EDIT, null);
        return edit.offset;
    }

    private static int editorCount(Document document) {
        Integer count = document.getUserData(EDITOR_COUNT);
        return count == null ? 0 : count;
    }

    @Override
    public void dispose() {
        for (Document document : trackedDocuments) {
            document.removeDocumentListener(documentListener);
            document.putUserData(EDITOR_COUNT, null);
            document.putUserData(LAST_EDIT, null);
        }
        trackedDocuments.clear();
    }

    private record LastEdit(int offset, long documentStamp) {}
}
