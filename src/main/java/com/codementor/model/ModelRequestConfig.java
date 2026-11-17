package com.codementor.model;

import com.codementor.settings.CodeMentorSettings;

import java.net.URI;
import java.util.Objects;

/** Immutable model configuration captured once for the complete lifetime of a request. */
public record ModelRequestConfig(
        String endpoint,
        String model,
        CodeMentorSettings.TeachingLevel teachingLevel,
        String promptSchemaVersion) {

    public static final String CURRENT_PROMPT_SCHEMA = "2026-11-v7";

    public ModelRequestConfig {
        endpoint = Objects.requireNonNull(endpoint, "endpoint");
        model = Objects.requireNonNull(model, "model");
        teachingLevel = Objects.requireNonNull(teachingLevel, "teachingLevel");
        promptSchemaVersion = Objects.requireNonNull(
                promptSchemaVersion, "promptSchemaVersion");
    }

    public static ModelRequestConfig capture(CodeMentorSettings settings) {
        ModelEndpointValidator.Result endpoint = ModelEndpointValidator.validate(
                settings.getApiBaseUrl(), settings.isInsecureLocalEndpointAllowed());
        if (!endpoint.valid()) {
            throw new IllegalArgumentException(endpoint.errorMessage());
        }
        String model = settings.getModel().trim();
        String modelError = ModelEndpointValidator.validateModelForEndpoint(
                model, endpoint.normalizedEndpoint());
        if (!modelError.isEmpty()) {
            throw new IllegalArgumentException(modelError);
        }
        return new ModelRequestConfig(
                endpoint.normalizedEndpoint(),
                model,
                settings.getTeachingLevel(),
                CURRENT_PROMPT_SCHEMA);
    }

    public URI endpointUri() {
        return URI.create(endpoint);
    }
}
