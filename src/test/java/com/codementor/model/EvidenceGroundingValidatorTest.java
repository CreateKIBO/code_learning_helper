package com.codementor.model;

import com.codementor.review.ReviewResult;
import com.codementor.review.Suggestion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvidenceGroundingValidatorTest {

    @Test
    void acceptsQuotedSourceExcerptWithEquivalentWhitespace() throws Exception {
        String context = """
                class Demo {
                    void load() {
                        try (InputStream input =
                                Files.newInputStream(path)) {
                            consume(input);
                        }
                    }
                }
                """;
        ReviewResult result = resultWithEvidence(
                "`try (InputStream input = Files.newInputStream(path))`");

        ReviewResult validated = EvidenceGroundingValidator.requireGrounded(result, context);

        assertSame(result, validated);
    }

    @Test
    void rejectsParaphrasedEvidenceWithoutEchoingRemoteText() {
        String hostileEvidence = "REMOTE_SECRET_EVIDENCE_NOT_PRESENT_IN_SOURCE";
        ReviewResult result = resultWithEvidence(hostileEvidence);

        JsonResponseParser.ParseException error = assertThrows(
                JsonResponseParser.ParseException.class,
                () -> EvidenceGroundingValidator.requireGrounded(
                        result, "class Demo { void load() {} }"));

        assertFalse(error.getMessage().contains(hostileEvidence));
    }

    @Test
    void rejectsTooShortEvidenceEvenWhenItOccursInSource() {
        ReviewResult result = resultWithEvidence("x + 1");

        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> EvidenceGroundingValidator.requireGrounded(
                        result, "int value = x + 1;"));
    }

    @Test
    void rejectsEvidenceLongerThanPromptContractEvenWhenItOccursInSource() {
        String oversizedEvidence = "x".repeat(161);
        ReviewResult result = resultWithEvidence(oversizedEvidence);

        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> EvidenceGroundingValidator.requireGrounded(
                        result, "prefix " + oversizedEvidence + " suffix"));
    }

    @Test
    void validatesEverySuggestion() {
        Suggestion grounded = suggestion("return service.load(id);");
        Suggestion invented = suggestion("repository.flushEverythingNow();");
        ReviewResult result = new ReviewResult(
                "检查证据来源",
                List.of(grounded, invented));

        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> EvidenceGroundingValidator.requireGrounded(
                        result,
                        "Result load(String id) { return service.load(id); }"));
    }

    @Test
    void rejectsEvidenceFoundOnlyInSyntheticMetadata() {
        String evidence = "技术标签：Spring MVC";
        ReviewResult result = resultWithEvidence(evidence);

        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> EvidenceGroundingValidator.requireGrounded(
                        result,
                        "// 技术标签：Spring MVC\nResult load() { return ok(); }",
                        "Result load() { return ok(); }"));
    }

    @Test
    void verificationEvidenceMustComeFromCurrentTargetCode() throws Exception {
        com.codementor.review.VerificationResult result =
                new com.codementor.review.VerificationResult(
                        com.codementor.review.VerificationStatus.RESOLVED,
                        "try (InputStream input = open())",
                        "");

        assertSame(
                result,
                EvidenceGroundingValidator.requireGrounded(
                        result,
                        "try (InputStream input = open()) { consume(input); }",
                        "try (InputStream input = open()) { consume(input); }"));
        assertThrows(
                JsonResponseParser.ParseException.class,
                () -> EvidenceGroundingValidator.requireGrounded(
                        result,
                        "return cachedValue;",
                        "return cachedValue;"));
    }

    private static ReviewResult resultWithEvidence(String evidence) {
        return new ReviewResult("检查证据来源", List.of(suggestion(evidence)));
    }

    private static Suggestion suggestion(String evidence) {
        return new Suggestion.Builder()
                .id("S1")
                .category(Suggestion.Category.CORRECTNESS)
                .certainty(Suggestion.Certainty.HIGH)
                .title("核对具体代码")
                .evidence(evidence)
                .build();
    }
}
