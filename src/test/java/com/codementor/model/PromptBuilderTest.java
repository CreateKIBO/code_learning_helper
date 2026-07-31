package com.codementor.model;

import com.codementor.javaweb.JavaWebContext;
import com.codementor.javaweb.JavaWebRole;
import com.codementor.settings.CodeMentorSettings;
import com.codementor.review.ReviewMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBuilderTest {

    @Test
    void keepsProjectRulesInDelimitedReferenceSection() {
        JavaWebContext webContext = new JavaWebContext(
                JavaWebRole.CONTROLLER,
                List.of("参数校验", "HTTP / REST"),
                "关注外部输入与响应语义");

        String prompt = PromptBuilder.buildUserPrompt(
                "class Demo {}",
                CodeMentorSettings.TeachingLevel.JAVAWEB,
                webContext,
                "接口路径使用小写");

        assertTrue(prompt.contains("JavaWeb 场景角色：Controller"));
        assertTrue(prompt.contains("<project-learning-rules>"));
        assertTrue(prompt.contains("仅是用户维护的编码与学习约定"));
        assertTrue(prompt.contains("class Demo {}"));
    }

    @Test
    void explanationModeRequestsUnderstandingPointsInsteadOfFixes() {
        String systemPrompt = PromptBuilder.buildSystemPrompt(ReviewMode.EXPLAIN);
        String userPrompt = PromptBuilder.buildUserPrompt(
                "int size() { return items.size(); }",
                CodeMentorSettings.TeachingLevel.BEGINNER,
                JavaWebContext.unknown(),
                "",
                ReviewMode.EXPLAIN);

        assertTrue(systemPrompt.contains("不把理解要点描述成错误"));
        assertTrue(systemPrompt.contains("不要求用户修改代码"));
        assertTrue(userPrompt.contains("学习请求：解释这段代码"));
        assertTrue(userPrompt.contains("请解释下面这段 Java 代码"));
    }
}
