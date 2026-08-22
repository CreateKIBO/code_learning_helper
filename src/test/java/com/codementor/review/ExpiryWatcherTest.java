package com.codementor.review;

import com.codementor.context.ContextTarget;
import com.codementor.context.PsiContextExtractor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

/** Verifies that expiry is conservative while the live method anchor remains usable. */
public class ExpiryWatcherTest extends LightJavaCodeInsightFixtureTestCase {

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
