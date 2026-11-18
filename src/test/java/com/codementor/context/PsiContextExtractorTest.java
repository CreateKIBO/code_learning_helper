package com.codementor.context;

import com.codementor.review.ReviewContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

/** 使用真实 Java PSI 验证目标定位和相关字段裁剪。 */
public class PsiContextExtractorTest extends LightJavaCodeInsightFixtureTestCase {

    public void testSelectionGetsMethodBackgroundButKeepsEvidenceAndVerificationScope() {
        var file = myFixture.configureByText("Counter.java", """
                class Counter {
                    int count(String input) {
                        int length = input.length();
                        <selection>return length + 1;</selection>
                    }
                    boolean isEmpty(String value) { return value.isEmpty(); }
                    String unrelated() { return "PRIVATE_BODY_MUST_STAY_OUT"; }
                }
                """);
        ReviewContext context = ApplicationManager.getApplication().runReadAction(
                (Computable<ReviewContext>) () -> PsiContextExtractor.extract(
                        getProject(), myFixture.getEditor(), file));

        assertNotNull(context);
        assertEquals("return length + 1;", context.getCode());
        assertEquals(-1, context.getMethodStart());
        assertEquals("", context.getTargetIdentity());
        assertTrue(context.getModelContext().contains("int count(String input)"));
        assertTrue(context.getModelContext().contains("int length = input.length();"));
        assertTrue(context.getModelContext().contains("boolean isEmpty(String value);"));
        assertFalse(context.getModelContext().contains("PRIVATE_BODY_MUST_STAY_OUT"));
        assertTrue(context.getModelContext().endsWith(context.getCode()));
    }

    public void testSelectionAcrossMethodsDoesNotClaimOneEnclosingMethod() {
        var file = myFixture.configureByText("Example.java", """
                class Example {
                    int first() { <selection>return 1; }
                    int second() { return 2;</selection> }
                }
                """);
        ReviewContext context = ApplicationManager.getApplication().runReadAction(
                (Computable<ReviewContext>) () -> PsiContextExtractor.extract(
                        getProject(), myFixture.getEditor(), file));
        assertNotNull(context);
        assertFalse(context.getModelContext().contains("// 所在方法背景"));
        assertEquals(-1, context.getMethodStart());
    }

    public void testLargeSelectionBackgroundKeepsParametersAndNearestLocalVariables() {
        var file = myFixture.configureByText("Counter.java",
                "class Counter { int count(String input) {\n"
                        + "// unrelated padding\n".repeat(1_000)
                        + "int length = input.length();\n<selection>return length + 1;</selection>\n} }");
        ReviewContext context = ApplicationManager.getApplication().runReadAction(
                (Computable<ReviewContext>) () -> PsiContextExtractor.extract(
                        getProject(), myFixture.getEditor(), file));
        assertNotNull(context);
        assertTrue(context.getModelContext().contains("int count(String input)"));
        assertTrue(context.getModelContext().contains("int length = input.length();"));
        assertTrue(context.getModelContext().contains("选区前背景中段已省略"));
        assertTrue(context.getModelContext().length() <= ModelContextBuilder.MAX_CONTEXT_CHARS);
    }

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
