package com.codementor.model;

import com.codementor.review.Suggestion;
import com.codementor.review.VerificationResult;
import com.codementor.review.VerificationStatus;
import com.codementor.practice.Exercise;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonResponseParserTest {

    @Test
    void parsesAtMostThreeSuggestionsAndAssignsStableIds() throws Exception {
        String suggestion = """
                {
                  "category":"JAVA_USAGE",
                  "certainty":"HIGH",
                  "title":"关闭资源",
                  "evidence":"new FileInputStream",
                  "hint":"考虑自动关闭",
                  "explanation":"使用 try-with-resources",
                  "example":"try (...) {}",
                  "knowledgePoint":"资源管理",
                  "verificationCriteria":"所有路径都能关闭资源"
                }
                """;
        String response = "{\"summary\":\"x\",\"suggestions\":["
                + suggestion + "," + suggestion + "," + suggestion + "," + suggestion + "]}";

        List<Suggestion> result = JsonResponseParser.parse(response);

        assertEquals(3, result.size());
        assertEquals("S1", result.get(0).getId());
        assertEquals("S3", result.get(2).getId());
        assertEquals("所有路径都能关闭资源", result.get(0).getVerificationCriteria());
    }

    @Test
    void rejectsResponseWithoutSuggestionsField() {
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parse("{\"summary\":\"缺少数组\"}"));
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
    void rejectsInternalVerificationStatus() {
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseVerification(
                        "{\"status\":\"VERIFYING\",\"evidence\":\"x\"}"));
    }

    @Test
    void parsesFocusedExercise() throws Exception {
        Exercise exercise = JsonResponseParser.parseExercise("""
                {
                  "title":"事务边界练习",
                  "knowledgePoint":"Spring 事务",
                  "task":"判断并调整转账方法的事务边界",
                  "starterCode":"void transfer() { /* TODO */ }",
                  "constraints":["只修改 Service 方法"],
                  "hints":["先找出原子操作"],
                  "acceptanceCriteria":["异常时余额不会部分更新"]
                }
                """);

        assertEquals("Spring 事务", exercise.getKnowledgePoint());
        assertEquals(1, exercise.getHints().size());
        assertEquals("只修改 Service 方法", exercise.getConstraints().get(0));
    }

    @Test
    void rejectsExerciseWithoutTask() {
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> JsonResponseParser.parseExercise(
                        "{\"title\":\"x\",\"knowledgePoint\":\"事务\"}"));
    }
}
