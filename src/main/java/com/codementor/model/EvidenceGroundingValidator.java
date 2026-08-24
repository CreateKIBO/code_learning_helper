package com.codementor.model;

import com.codementor.review.ReviewResult;
import com.codementor.review.Suggestion;
import com.codementor.review.VerificationResult;

import java.util.regex.Pattern;

/** Rejects model suggestions whose claimed evidence cannot be found in the sent context. */
final class EvidenceGroundingValidator {

    /** 证据长度的合法区间；PromptBuilder 用同一常量生成提示词，避免校验与提示脱节。 */
    static final int MIN_NORMALIZED_EVIDENCE_CHARS = 8;
    static final int MAX_NORMALIZED_EVIDENCE_CHARS = 160;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private EvidenceGroundingValidator() {}

    static final class GroundingException extends JsonResponseParser.ParseException {
        GroundingException() {
            super("模型结论无法从当前可见代码中核对");
        }
    }

    static ReviewResult requireGrounded(ReviewResult result, String sentModelContext)
            throws JsonResponseParser.ParseException {
        return requireGrounded(result, sentModelContext, sentModelContext);
    }

    static ReviewResult requireGrounded(
            ReviewResult result,
            String sentModelContext,
            String allowedSourceCode) throws JsonResponseParser.ParseException {
        for (Suggestion suggestion : result.suggestions()) {
            requireEvidence(
                    suggestion.getEvidence(), sentModelContext, allowedSourceCode);
        }
        return result;
    }

    static VerificationResult requireGrounded(
            VerificationResult result,
            String sentCurrentContext,
            String allowedCurrentSourceCode) throws JsonResponseParser.ParseException {
        requireEvidence(
                result.getEvidence(), sentCurrentContext, allowedCurrentSourceCode);
        return result;
    }

    private static void requireEvidence(
            String rawEvidence,
            String sentContext,
            String allowedSourceCode) throws GroundingException {
        String evidence = normalize(stripWrappingQuotes(rawEvidence));
        if (evidence.length() < MIN_NORMALIZED_EVIDENCE_CHARS
                || evidence.length() > MAX_NORMALIZED_EVIDENCE_CHARS
                || !normalize(sentContext).contains(evidence)
                || !normalize(allowedSourceCode).contains(evidence)) {
            throw new GroundingException();
        }
    }

    private static String normalize(String value) {
        return WHITESPACE.matcher(value == null ? "" : value.trim()).replaceAll(" ");
    }

    private static String stripWrappingQuotes(String value) {
        if (value == null || value.length() < 2) return value;
        String trimmed = value.trim();
        if (trimmed.length() < 2) return trimmed;
        char first = trimmed.charAt(0);
        char last = trimmed.charAt(trimmed.length() - 1);
        boolean wrapped = (first == '`' && last == '`')
                || (first == '"' && last == '"')
                || (first == '\'' && last == '\'')
                || (first == '“' && last == '”')
                || (first == '‘' && last == '’');
        return wrapped ? trimmed.substring(1, trimmed.length() - 1) : trimmed;
    }
}
