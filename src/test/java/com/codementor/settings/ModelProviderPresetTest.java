package com.codementor.settings;

import com.codementor.model.ModelEndpointValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelProviderPresetTest {

    @Test
    void everyProviderPresetHasAValidEndpointAndModelButNoApiKeyField() {
        for (ModelProviderPreset preset : ModelProviderPreset.values()) {
            if (preset.isCustom()) continue;
            ModelEndpointValidator.Result endpoint = ModelEndpointValidator.validate(
                    preset.endpoint(), false);
            assertTrue(endpoint.valid(), preset.toString());
            assertEquals("", ModelEndpointValidator.validateModelForEndpoint(
                    preset.model(), endpoint.normalizedEndpoint()), preset.toString());
            assertFalse(preset.endpoint().isBlank());
            assertFalse(preset.model().isBlank());
        }
    }

    @Test
    void matchingUsesTheNormalizedEndpointAndFallsBackToCustom() {
        assertEquals(ModelProviderPreset.DEEPSEEK_V4_FLASH,
                ModelProviderPreset.matching(
                        "https://api.deepseek.com/v1/../chat/completions/",
                        "deepseek-v4-flash"));
        assertEquals(ModelProviderPreset.CUSTOM,
                ModelProviderPreset.matching(
                        "https://proxy.example.com/v1/chat/completions",
                        "deepseek-v4-flash"));
    }
}
