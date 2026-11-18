package com.codementor.project;

import com.codementor.settings.CodeMentorProjectConsent;
import com.codementor.settings.CodeMentorProjectConsent.ProjectScanDecision;
import com.codementor.settings.CodeMentorSettings;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;

import java.util.List;

public class ProjectCallsTest extends JavaCodeInsightFixtureTestCase {

    public void testOnlyResolvesInScopeAllowedOverloadsAndDetectsEditedSource() {
        PsiFile store = myFixture.addFileToProject("demo/Store.java", """
                package demo;
                class Store {
                    int load(int id) { return 1; }
                    String load(String id) { return "body-not-for-context"; }
                    int outsideSelection() { return 2; }
                }
                """);
        myFixture.addFileToProject("other/Store.java", """
                package other;
                public class Store { public int load(int id) { return 3; } }
                """);
        PsiFile controller = myFixture.addFileToProject("demo/Controller.java", """
                package demo;
                class Controller {
                    int run(Store store) {
                        store.outsideSelection();
                        return store.load(1);
                    }
                }
                """);
        int start = controller.getText().indexOf("store.load(1)");
        var range = TextRange.from(start, "store.load(1)".length());
        var consent = CodeMentorProjectConsent.getInstance(getProject());
        var settings = CodeMentorSettings.getInstance();
        String originalPatterns = settings.getExcludedFilePatterns();
        var originalDecision = consent.getProjectScanDecision();
        try {
            consent.setProjectScanDecision(ProjectScanDecision.DENIED);
            assertTrue(collect(controller, range).signatures().isEmpty());
            consent.setProjectScanDecision(ProjectScanDecision.GRANTED);
            ProjectCalls calls = collect(controller, range);
            assertEquals(List.of("int demo.Store#load(int)"), calls.signatures());
            assertTrue(calls.isCurrent());

            settings.setExcludedFilePatterns("Store.java");
            assertTrue(collect(controller, range).signatures().isEmpty());

            var document = FileDocumentManager.getInstance().getDocument(store.getVirtualFile());
            assertNotNull(document);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(0, "// edit\n"));
            assertFalse(calls.isCurrent());
        } finally {
            settings.setExcludedFilePatterns(originalPatterns);
            consent.setProjectScanDecision(originalDecision);
        }
    }

    private ProjectCalls collect(PsiFile file, TextRange range) {
        return ReadAction.compute(() -> ProjectCalls.collect(getProject(), file, range));
    }
}
