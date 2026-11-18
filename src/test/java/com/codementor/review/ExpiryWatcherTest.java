package com.codementor.review;

import com.codementor.context.ContextTarget;
import com.codementor.context.PsiContextExtractor;
import com.codementor.project.ProjectCalls;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.util.List;

/** Verifies that expiry is conservative while the live method anchor remains usable. */
public class ExpiryWatcherTest extends LightJavaCodeInsightFixtureTestCase {

    public void testEditingTrackedDeclarationExpiresResultAndPendingFollowUp() {
        var dependency = myFixture.addFileToProject("Store.java", "class Store { int load() { return 1; } }")
                .getVirtualFile();
        var other = myFixture.addFileToProject("Other.java", "class Other {} ").getVirtualFile();
        var target = myFixture.configureByText("Demo.java", "class Demo { void run() { <caret> } }");
        var editor = myFixture.getEditor();
        var context = readContext(target, ContextTarget.capture(editor, null));
        assertNotNull(context);
        var manager = FileDocumentManager.getInstance();
        var dependencyDocument = manager.getDocument(dependency);
        var otherDocument = manager.getDocument(other);
        assertNotNull(dependencyDocument);
        assertNotNull(otherDocument);
        var calls = new ProjectCalls(List.of("int Store#load()"), List.of(new ProjectCalls.SourceVersion(
                dependency, dependency.getUrl(), dependency.getModificationStamp(),
                dependencyDocument.getModificationStamp())));
        var session = new ReviewSession();
        session.startRequest(context, "review-id");
        session.succeed("review-id", List.of());
        session.startFollowUp("S1", "follow-up", "What happens here?");
        var watcher = new ExpiryWatcher(editor.getDocument(), context, session, "review-id");
        watcher.attach();
        watcher.watchProjectCalls(calls);
        try {
            WriteCommandAction.runWriteCommandAction(getProject(), () -> otherDocument.insertString(0, "// edit\n"));
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            assertEquals(ReviewStatus.SUCCESS, session.getStatus());
            WriteCommandAction.runWriteCommandAction(getProject(), () -> dependencyDocument.insertString(0, "// edit\n"));
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            assertEquals(ReviewStatus.EXPIRED, session.getStatus());
            assertFalse(session.completeFollowUp("S1", "follow-up", "stale answer"));
            assertEquals(FollowUpResult.Status.IDLE, session.getFollowUpResult("S1").getStatus());
        } finally {
            watcher.detach();
        }
    }

    public void testAnchorFollowsEditsBeforeOriginalMethod() {
        var psiFile = myFixture.configureByText(
                "Demo.java",
                """
                class Demo {
                    void first() {}

                    void second() {
                        int value = <caret>1;
                        Runnable nested = new Runnable() {
                            @Override
                            public void run() {
                                System.out.println("nested method must not become the target");
                            }
                        };
                    }
                }
                """);
        ReviewContext original = readContext(psiFile, ContextTarget.capture(
                myFixture.getEditor(), null));
        assertNotNull(original);

        ReviewSession session = new ReviewSession();
        session.startRequest(original, "review-id");
        ExpiryWatcher watcher = new ExpiryWatcher(
                myFixture.getEditor().getDocument(), original, session, "review-id");
        watcher.attach();

        int insertionOffset = myFixture.getEditor().getDocument().getText().indexOf("void first");
        WriteCommandAction.runWriteCommandAction(getProject(), () ->
                myFixture.getEditor().getDocument().insertString(
                        insertionOffset, "void inserted() {}\n    "));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();

        ContextTarget anchored = watcher.captureCurrentTarget(
                myFixture.getEditor(), original);
        assertNotNull(anchored);
        ReviewContext relocated = readContext(psiFile, anchored);
        assertNotNull(relocated);
        assertEquals("Demo#second()", relocated.getTargetName());
        assertTrue(watcher.matchesCurrentTarget(original, relocated));
        assertEquals(ReviewStatus.EXPIRED, session.getStatus());
        watcher.detach();
    }

    public void testDeletingOriginalMethodInvalidatesAnchor() {
        var psiFile = myFixture.configureByText(
                "Demo.java",
                """
                class Demo {
                    void target() {
                        int value = <caret>1;
                    }

                    void next() {}
                }
                """);
        ReviewContext original = readContext(psiFile, ContextTarget.capture(
                myFixture.getEditor(), null));
        assertNotNull(original);

        ReviewSession session = new ReviewSession();
        session.startRequest(original, "review-id");
        ExpiryWatcher watcher = new ExpiryWatcher(
                myFixture.getEditor().getDocument(), original, session, "review-id");
        watcher.attach();

        WriteCommandAction.runWriteCommandAction(getProject(), () ->
                myFixture.getEditor().getDocument().deleteString(
                        original.getMethodStart(), original.getMethodEnd()));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();

        assertNull(watcher.captureCurrentTarget(myFixture.getEditor(), original));
        watcher.detach();
    }

    public void testWholeDocumentReplacementInvalidatesAnchor() {
        var psiFile = myFixture.configureByText(
                "Demo.java",
                """
                class Demo {
                    void target() {
                        int value = <caret>1;
                    }
                }
                """);
        ReviewContext original = readContext(psiFile, ContextTarget.capture(
                myFixture.getEditor(), null));
        assertNotNull(original);

        ReviewSession session = new ReviewSession();
        session.startRequest(original, "review-id");
        ExpiryWatcher watcher = new ExpiryWatcher(
                myFixture.getEditor().getDocument(), original, session, "review-id");
        watcher.attach();

        WriteCommandAction.runWriteCommandAction(getProject(), () ->
                myFixture.getEditor().getDocument().setText(
                        "class Other { void unrelated() {} }"));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();

        assertNull(watcher.captureCurrentTarget(myFixture.getEditor(), original));
        watcher.detach();
    }

    public void testCompletedVerificationExpiresOnNextDocumentEdit() {
        var psiFile = myFixture.configureByText(
                "Demo.java",
                """
                class Demo {
                    void target() {
                        int value = <caret>1;
                    }
                }
                """);
        ReviewContext original = readContext(psiFile, ContextTarget.capture(
                myFixture.getEditor(), null));
        assertNotNull(original);

        ReviewSession session = new ReviewSession();
        session.startRequest(original, "review-id");
        ExpiryWatcher watcher = new ExpiryWatcher(
                myFixture.getEditor().getDocument(), original, session, "review-id");
        watcher.attach();

        WriteCommandAction.runWriteCommandAction(getProject(), () ->
                myFixture.getEditor().getDocument().insertString(
                        original.getMethodEnd() - 1, "\n        // first change"));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        assertEquals(ReviewStatus.EXPIRED, session.getStatus());

        session.startVerification("S1", "verify-id");
        assertTrue(session.completeVerification(
                "S1",
                "verify-id",
                new VerificationResult(VerificationStatus.RESOLVED, "fixed", "")));
        assertEquals(
                VerificationStatus.RESOLVED,
                session.getVerificationResult("S1").getStatus());

        WriteCommandAction.runWriteCommandAction(getProject(), () ->
                myFixture.getEditor().getDocument().insertString(
                        myFixture.getEditor().getDocument().getTextLength() - 1,
                        "\n// later change"));
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();

        assertEquals(
                VerificationStatus.IDLE,
                session.getVerificationResult("S1").getStatus());
        watcher.detach();
    }

    private ReviewContext readContext(
            com.intellij.psi.PsiFile psiFile, ContextTarget target) {
        return ApplicationManager.getApplication().runReadAction(
                (Computable<ReviewContext>) () -> PsiContextExtractor.extract(
                        getProject(), target, psiFile));
    }
}
