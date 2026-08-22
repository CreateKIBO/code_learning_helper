package com.codementor.history;

import com.codementor.review.ReviewContext;
import com.codementor.review.Suggestion;
import com.codementor.review.VerificationResult;
import com.codementor.review.VerificationStatus;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningHistoryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultRecordingOmitsModelEvidenceCodeAndAbsolutePathsAndClearsAsync() throws Exception {
        Path eventFile = tempDir.resolve("learning-events.jsonl");
        LearningEventStore store = new LearningEventStore(eventFile);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LearningHistoryService service = new LearningHistoryService(store, executor);
        try {
            ReviewContext context = new ReviewContext(
                    "C:\\Users\\alice\\graduation-project\\src\\Demo.java",
                    "Demo#run",
                    "class SecretSource { String password; }",
                    "class SecretSource { String password; }",
                    false,
                    1L,
                    "fingerprint",
                    0,
                    10);
            Suggestion suggestion = new Suggestion.Builder()
                    .id("S1")
                    .category(Suggestion.Category.CORRECTNESS)
                    .certainty(Suggestion.Certainty.HIGH)
                    .knowledgePoint("资源管理")
                    .title("关闭资源")
                    .evidence("class SecretSource { String password; }")
                    .build();
            Project project = projectNamed("demo");

            service.recordReview(project, context, List.of(suggestion), "review-1");
            service.recordVerification(
                    project,
                    context,
                    suggestion,
                    new VerificationResult(
                            VerificationStatus.RESOLVED,
                            "model evidence with password",
                            ""),
                    "review-1");
            service.loadSummary().join(); // 同一执行器上的栅栏，等待两条记录写入。

            List<LearningEvent> events = store.readAll();
            String rawJsonl = Files.readString(eventFile, StandardCharsets.UTF_8);

            assertEquals(2, events.size());
            assertTrue(events.stream().allMatch(event -> event.getDetail().isEmpty()));
            assertEquals("HIGH", events.get(0).getOutcome());
            assertTrue(events.stream().allMatch(
                    event -> "Demo.java".equals(event.getFilePath())));
            assertFalse(rawJsonl.contains("SecretSource"));
            assertFalse(rawJsonl.contains("password"));
            assertFalse(rawJsonl.contains("Users"));
            assertFalse(rawJsonl.contains("alice"));

            service.clearHistory().join();

            assertFalse(Files.exists(eventFile));
            assertTrue(store.readAll().isEmpty());
        } finally {
            service.dispose();
        }
    }

    private static Project projectNamed(String name) {
        return (Project) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class<?>[]{Project.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) {
                        return name;
                    }
                    if ("toString".equals(method.getName())) {
                        return "Project(" + name + ")";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) return false;
                    if (returnType == int.class) return 0;
                    if (returnType == long.class) return 0L;
                    return null;
                });
    }
}
