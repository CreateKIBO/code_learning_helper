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
        assertEquals("UserService#count", context.getTargetName());
        assertTrue(context.getModelContext().contains("List<String> names"));
        assertFalse(context.getModelContext().contains("unusedSecret"));
        assertTrue(context.getTechnologyTags().contains("Java Concurrency"));
    }
}
