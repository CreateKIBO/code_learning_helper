package com.codementor.model;

import com.codementor.practice.Exercise;
import com.codementor.review.ReviewResult;
import com.codementor.review.ReviewMode;
import com.codementor.review.Suggestion;
import com.codementor.review.VerificationResult;
import com.codementor.review.VerificationStatus;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonResponseParserTest {

    @Test
    void nextStepRequiresExactlyOneNeutralTask() throws Exception {
        JsonArray tasks = new JsonArray();
        assertThrows(JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseReview(reviewResponse(tasks), ReviewMode.NEXT_STEP));
        JsonObject task = validSuggestion();
        task.addProperty("certainty", "MEDIUM");
        task.addProperty("example", "");
        tasks.add(task);
        assertEquals(1, JsonResponseParser.parseReview(
                reviewResponse(tasks), ReviewMode.NEXT_STEP).suggestions().size());
        task.addProperty("example", "return completeSolution();");
        assertThrows(JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseReview(reviewResponse(tasks), ReviewMode.NEXT_STEP));
        task.addProperty("example", "");
        task.addProperty("certainty", "HIGH");
        assertThrows(JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseReview(reviewResponse(tasks), ReviewMode.NEXT_STEP));
        task.addProperty("certainty", "MEDIUM");
        tasks.add(task.deepCopy());
        assertThrows(JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseReview(reviewResponse(tasks), ReviewMode.NEXT_STEP));
    }

    @Test
    void parsesAtMostThreeSuggestionsAndAssignsStableIds() throws Exception {
        JsonArray suggestions = new JsonArray();
        for (int index = 0; index < 4; index++) {
            suggestions.add(validSuggestion());
        }

        List<Suggestion> result = JsonResponseParser.parse(reviewResponse(suggestions));

        assertEquals(3, result.size());
        assertEquals("S1", result.get(0).getId());
        assertEquals("S3", result.get(2).getId());
        assertEquals("所有路径都能关闭资源", result.get(0).getVerificationCriteria());
    }

    @Test
    void parsesSummaryAndRequiredLearningActions() throws Exception {
        JsonArray suggestions = new JsonArray();
        suggestions.add(validSuggestion());

        ReviewResult result = JsonResponseParser.parseReview(
                reviewResponse("  资源处理整体清晰  ", suggestions));

        assertEquals("资源处理整体清晰", result.summary());
        assertEquals("先把资源创建语句放进 try 的括号中", result.suggestions().get(0).getNextStep());
        assertEquals(
                "异常路径是否也会自动关闭资源？",
                result.suggestions().get(0).getSelfCheckQuestion());
    }

    @Test
    void acceptsExplicitlyEmptySuggestionsWhenSummaryIsPresent() throws Exception {
        ReviewResult result = JsonResponseParser.parseReview(
                reviewResponse("当前代码没有需要优先处理的问题", new JsonArray()));

        assertEquals("当前代码没有需要优先处理的问题", result.summary());
        assertTrue(result.suggestions().isEmpty());
    }

    @Test
    void rejectsSummaryThatClaimsUnperformedExecution() {
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseReview(
                        reviewResponse("所有测试均已通过，运行完全正确", new JsonArray())));
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseReview(
                        reviewResponse("测试全部通过，代码可以正常运行", new JsonArray())));

        JsonObject suggestion = validSuggestion();
        suggestion.addProperty("explanation", "所有测试均已通过");
        JsonArray suggestions = new JsonArray();
        suggestions.add(suggestion);
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseReview(
                        reviewResponse("仅基于静态代码给出建议", suggestions)));
    }

    @Test
    void followUpRequiresContentSelfCheckAndStaticOnlyClaims() throws Exception {
        assertEquals(
                "先看条件分支。你能说出空值会走哪条路径吗？",
                JsonResponseParser.parseFollowUp(
                        "  先看条件分支。你能说出空值会走哪条路径吗？  "));
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseFollowUp("   "));
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseFollowUp("先看条件分支，但这里没有自测问题。"));
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseFollowUp(
                        "所有测试均已通过。你能解释原因吗？"));
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseFollowUp(
                        "为什么会这样？因为这里没有判空。"));
        assertEquals(
                "请确保单元测试通过。你会先覆盖哪个边界条件？",
                JsonResponseParser.parseFollowUp(
                        "请确保单元测试通过。你会先覆盖哪个边界条件？"));
    }

    @Test
    void explanationRequiresAtLeastOneMediumCertaintyUnderstandingPoint() throws Exception {
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseReview(
                        reviewResponse("解释", new JsonArray()), ReviewMode.EXPLAIN));

        JsonObject high = validSuggestion();
        high.addProperty("certainty", "HIGH");
        JsonArray highArray = new JsonArray();
        highArray.add(high);
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseReview(
                        reviewResponse(highArray), ReviewMode.EXPLAIN));

        JsonObject medium = validSuggestion();
        medium.addProperty("certainty", "MEDIUM");
        JsonArray mediumArray = new JsonArray();
        mediumArray.add(medium);
        assertEquals(
                1,
                JsonResponseParser.parseReview(
                        reviewResponse(mediumArray), ReviewMode.EXPLAIN)
                        .suggestions().size());
    }

    @Test
    void requiresSummaryForStructuredReviewResult() {
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseReview("{\"suggestions\":[]}"));
    }

    @Test
    void rejectsMissingOrWrongSuggestionsField() {
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parse("{\"summary\":\"缺少数组\"}"));
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parse("{\"suggestions\":{}}"));
    }

    @Test
    void rejectsNonEmptyArrayWhenEverySuggestionIsInvalid() {
        JsonArray suggestions = new JsonArray();
        suggestions.add(new JsonObject());
        suggestions.add("not-an-object");

        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parse(reviewResponse(suggestions)));
    }

    @Test
    void requiresEveryLearningFieldButAllowsOptionalExample() throws Exception {
        List<String> required = List.of(
                "title",
                "evidence",
                "hint",
                "explanation",
                "knowledgePoint",
                "verificationCriteria",
                "nextStep",
                "selfCheckQuestion");
        for (String field : required) {
            JsonObject suggestion = validSuggestion();
            suggestion.remove(field);
            JsonArray suggestions = new JsonArray();
            suggestions.add(suggestion);
            assertThrows(
                    JsonResponseParser.ParseException.class,
                    () -> JsonResponseParser.parse(reviewResponse(suggestions)),
                    field);
        }

        JsonObject withoutExample = validSuggestion();
        withoutExample.remove("example");
        JsonArray suggestions = new JsonArray();
        suggestions.add(withoutExample);
        assertEquals(1, JsonResponseParser.parse(reviewResponse(suggestions)).size());
    }

    @Test
    void keepsValidSuggestionsWhenAnotherItemIsInvalid() throws Exception {
        JsonArray suggestions = new JsonArray();
        suggestions.add(new JsonObject());
        suggestions.add(validSuggestion());

        List<Suggestion> result = JsonResponseParser.parse(reviewResponse(suggestions));

        assertEquals(1, result.size());
        assertEquals("S1", result.get(0).getId());
    }

    @Test
    void boundsResponseAndSuggestionFieldLengths() {
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parse(
                        "x".repeat(JsonResponseParser.MAX_RESPONSE_CHARS + 1)));

        JsonObject longText = validSuggestion();
        longText.addProperty(
                "explanation",
                "x".repeat(JsonResponseParser.MAX_FIELD_CHARS + 1));
        JsonArray longTextArray = new JsonArray();
        longTextArray.add(longText);
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parse(reviewResponse(longTextArray)));

        JsonObject longTitle = validSuggestion();
        longTitle.addProperty(
                "title",
                "x".repeat(JsonResponseParser.MAX_SHORT_FIELD_CHARS + 1));
        JsonArray longTitleArray = new JsonArray();
        longTitleArray.add(longTitle);
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parse(reviewResponse(longTitleArray)));

        JsonObject longEvidence = validSuggestion();
        longEvidence.addProperty("evidence", "x".repeat(513));
        JsonArray longEvidenceArray = new JsonArray();
        longEvidenceArray.add(longEvidence);
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parse(reviewResponse(longEvidenceArray)));

        JsonObject longNextStep = validSuggestion();
        longNextStep.addProperty("nextStep", "x".repeat(2_001));
        JsonArray longNextStepArray = new JsonArray();
        longNextStepArray.add(longNextStep);
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parse(reviewResponse(longNextStepArray)));

        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseReview(
                        reviewResponse("x".repeat(2_001), new JsonArray())));
    }

    @Test
    void rejectsUnknownSuggestionEnumsWithoutEchoingRemoteValues() {
        for (String field : List.of("category", "certainty")) {
            String hostile = "REMOTE_SECRET_" + field.toUpperCase();
            JsonObject suggestion = validSuggestion();
            suggestion.addProperty(field, hostile);
            JsonArray suggestions = new JsonArray();
            suggestions.add(suggestion);

            JsonResponseParser.ParseException error = assertThrows(
                    JsonResponseParser.ParseException.class,
                    () -> JsonResponseParser.parseReview(reviewResponse(suggestions)),
                    field);

            assertFalse(error.getMessage().contains(hostile));
        }
    }

    @Test
    void parsesVerificationResult() throws Exception {
        VerificationResult result = JsonResponseParser.parseVerification("""
                {
                  "status":"PARTIALLY_RESOLVED",
                  "evidence":"资源已放入 try，但第二个流仍在外部创建",
                  "nextHint":"检查第二个资源的生命周期"
                }
                """);

        assertEquals(VerificationStatus.PARTIALLY_RESOLVED, result.getStatus());
        assertEquals("资源已放入 try，但第二个流仍在外部创建", result.getEvidence());
    }

    @Test
    void verificationRequiresNextHintUntilResolvedAndDropsItAfterResolution()
            throws Exception {
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseVerification(
                        "{\"status\":\"UNRESOLVED\",\"evidence\":\"current code evidence\"}"));

        VerificationResult resolved = JsonResponseParser.parseVerification("""
                {
                  "status":"RESOLVED",
                  "evidence":"try (InputStream input = open())",
                  "nextHint":"不应继续指导"
                }
                """);

        assertTrue(resolved.getNextHint().isEmpty());
    }

    @Test
    void rejectsInternalOrUnknownVerificationStatusWithoutEchoingRawValue() {
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseVerification(
                        "{\"status\":\"VERIFYING\",\"evidence\":\"x\"}"));

        String hostile = "SECRET_FROM_REMOTE_STATUS";
        JsonResponseParser.ParseException error = assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseVerification(
                        "{\"status\":\"" + hostile + "\",\"evidence\":\"x\"}"));
        assertFalse(error.getMessage().contains(hostile));
    }

    @Test
    void parsesFocusedExercise() throws Exception {
        Exercise exercise = JsonResponseParser.parseExercise(validExercise().toString());

        assertEquals("Spring 事务", exercise.getKnowledgePoint());
        assertEquals(2, exercise.getHints().size());
        assertEquals("只修改 Service 方法", exercise.getConstraints().get(0));
    }

    @Test
    void rejectsExerciseWithoutTask() {
        JsonObject exercise = validExercise();
        exercise.remove("task");

        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseExercise(exercise.toString()));
    }

    @Test
    void exerciseRequiresHintsAndAcceptanceCriteria() {
        for (String field : List.of("hints", "acceptanceCriteria")) {
            JsonObject missing = validExercise();
            missing.remove(field);
            assertThrows(
                    JsonResponseParser.ParseException.class,
                    () -> JsonResponseParser.parseExercise(missing.toString()),
                    field);

            JsonObject empty = validExercise();
            empty.add(field, new JsonArray());
            assertThrows(
                    JsonResponseParser.ParseException.class,
                    () -> JsonResponseParser.parseExercise(empty.toString()),
                    field);
        }

        JsonObject oneHint = validExercise();
        JsonArray hints = new JsonArray();
        hints.add("只给一条提示");
        oneHint.add("hints", hints);
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseExercise(oneHint.toString()));

        JsonObject duplicateHints = validExercise();
        JsonArray repeated = new JsonArray();
        repeated.add("先找出原子操作");
        repeated.add("先找出原子操作！");
        duplicateHints.add("hints", repeated);
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseExercise(duplicateHints.toString()));
    }

    @Test
    void exerciseKeepsRequestedKnowledgeLabelAndRejectsOffTopicResult() throws Exception {
        JsonObject equivalent = validExercise();
        equivalent.addProperty("knowledgePoint", "spring-事务");

        Exercise exercise = JsonResponseParser.parseExercise(
                equivalent.toString(), "Spring 事务");

        assertEquals("Spring 事务", exercise.getKnowledgePoint());

        JsonObject offTopic = validExercise();
        offTopic.addProperty("knowledgePoint", "Java 集合");
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseExercise(
                        offTopic.toString(), "Spring 事务"));
    }

    @Test
    void boundsExerciseListSizeAndItemLength() {
        JsonObject tooMany = validExercise();
        JsonArray items = new JsonArray();
        for (int index = 0; index <= JsonResponseParser.MAX_LIST_ITEMS; index++) {
            items.add("item-" + index);
        }
        tooMany.add("hints", items);
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseExercise(tooMany.toString()));

        JsonObject tooLong = validExercise();
        JsonArray longItem = new JsonArray();
        longItem.add("x".repeat(JsonResponseParser.MAX_LIST_ITEM_CHARS + 1));
        tooLong.add("constraints", longItem);
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseExercise(tooLong.toString()));
    }

    private static JsonObject validSuggestion() {
        JsonObject suggestion = new JsonObject();
        suggestion.addProperty("category", "JAVA_USAGE");
        suggestion.addProperty("certainty", "HIGH");
        suggestion.addProperty("title", "关闭资源");
        suggestion.addProperty("evidence", "new FileInputStream");
        suggestion.addProperty("hint", "考虑自动关闭");
        suggestion.addProperty("explanation", "使用 try-with-resources");
        suggestion.addProperty("example", "try (...) {}");
        suggestion.addProperty("knowledgePoint", "资源管理");
        suggestion.addProperty("verificationCriteria", "所有路径都能关闭资源");
        suggestion.addProperty("nextStep", "先把资源创建语句放进 try 的括号中");
        suggestion.addProperty("selfCheckQuestion", "异常路径是否也会自动关闭资源？");
        return suggestion;
    }

    private static String reviewResponse(JsonArray suggestions) {
        return reviewResponse("测试摘要", suggestions);
    }

    private static String reviewResponse(String summary, JsonArray suggestions) {
        JsonObject response = new JsonObject();
        response.addProperty("summary", summary);
        response.add("suggestions", suggestions);
        return response.toString();
    }

    private static JsonObject validExercise() {
        JsonObject exercise = new JsonObject();
        exercise.addProperty("title", "事务边界练习");
        exercise.addProperty("knowledgePoint", "Spring 事务");
        exercise.addProperty("task", "判断并调整转账方法的事务边界");
        exercise.addProperty("starterCode", "void transfer() { /* TODO */ }");

        JsonArray constraints = new JsonArray();
        constraints.add("只修改 Service 方法");
        exercise.add("constraints", constraints);

        JsonArray hints = new JsonArray();
        hints.add("先找出原子操作");
        hints.add("再检查异常路径的回滚边界");
        exercise.add("hints", hints);

        JsonArray acceptance = new JsonArray();
        acceptance.add("异常时余额不会部分更新");
        exercise.add("acceptanceCriteria", acceptance);
        return exercise;
    }
}
