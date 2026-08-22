package com.codementor.context;

import com.codementor.review.ReviewContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

/** 使用真实 Java PSI 验证目标定位和相关字段裁剪。 */
public class PsiContextExtractorTest extends LightJavaCodeInsightFixtureTestCase {

    public void testIncludesOnlyFieldReferencedByCurrentMethod() {
        var psiFile = myFixture.configureByText(
                "UserService.java",
                """
                import java.util.ArrayList;
                import java.util.List;
                import java.util.concurrent.CompletableFuture;

                class UserService {
                    private final List<String> names = new ArrayList<>();
                    private final String unusedSecret = "do-not-include";

                    int count() {
                        return names.<caret>size();
                    }
                }
                """);

        ReviewContext context = ApplicationManager.getApplication().runReadAction(
                (Computable<ReviewContext>) () -> PsiContextExtractor.extract(
                        getProject(), myFixture.getEditor(), psiFile));

        assertNotNull(context);
        assertEquals("UserService#count()", context.getTargetName());
        assertTrue(context.getModelContext().contains("List<String> names"));
        assertFalse(context.getModelContext().contains("unusedSecret"));
        assertTrue(context.getTechnologyTags().contains("Java Concurrency"));
    }

    public void testOverloadedMethodsHaveDistinctTargetNames() {
        var psiFile = myFixture.configureByText(
                "Handler.java",
                """
                class Handler {
                    String handle(String value) {
                        return value.<caret>trim();
                    }

                    String handle(int value) {
                        return Integer.toString(value);
                    }
                }
                """);

        ReviewContext stringOverload = ApplicationManager.getApplication().runReadAction(
                (Computable<ReviewContext>) () -> PsiContextExtractor.extract(
                        getProject(), myFixture.getEditor(), psiFile));
        myFixture.getEditor().getCaretModel().moveToOffset(
                myFixture.getEditor().getDocument().getText().indexOf("Integer.toString"));
        ReviewContext intOverload = ApplicationManager.getApplication().runReadAction(
                (Computable<ReviewContext>) () -> PsiContextExtractor.extract(
                        getProject(), myFixture.getEditor(), psiFile));

        assertNotNull(stringOverload);
        assertNotNull(intOverload);
        assertEquals("Handler#handle(String)", stringOverload.getTargetName());
        assertEquals("Handler#handle(int)", intOverload.getTargetName());
        assertFalse(stringOverload.getTargetName().equals(intOverload.getTargetName()));
        assertFalse(stringOverload.getTargetIdentity().equals(
                intOverload.getTargetIdentity()));
    }
}
