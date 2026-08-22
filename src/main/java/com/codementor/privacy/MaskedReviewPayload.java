package com.codementor.privacy;

import com.codementor.model.PrivacyFilter;
import com.codementor.review.ReviewContext;

/** The exact masked learning payload used by both preview and model request paths. */
public record MaskedReviewPayload(String modelContext, String projectRules) {

    public static MaskedReviewPayload from(ReviewContext context) {
        return new MaskedReviewPayload(
                safeMask(context.getModelContext()),
                safeMask(context.getProjectRules()));
    }

    private static String safeMask(String value) {
        String masked = PrivacyFilter.mask(value);
        return masked == null ? "" : masked;
    }
}
