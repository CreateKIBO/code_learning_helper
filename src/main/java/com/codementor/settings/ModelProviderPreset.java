package com.codementor.settings;

import com.codementor.model.ModelEndpointValidator;

/** Endpoint/model pairs only. API keys always remain user-owned PasswordSafe data. */
enum ModelProviderPreset {
    CUSTOM("自定义", "", ""),
    ZHIPU_GLM_5_2(
            "智谱 GLM-5.2",
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            "glm-5.2"),
    DEEPSEEK_V4_FLASH(
            "DeepSeek V4 Flash",
            "https://api.deepseek.com",
            "deepseek-v4-flash"),
    DEEPSEEK_V4_PRO(
            "DeepSeek V4 Pro",
            "https://api.deepseek.com",
            "deepseek-v4-pro"),
    QWEN_PLUS(
            "通义千问 Qwen Plus",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "qwen-plus"),
    OPENAI_GPT_4_1_MINI(
            "OpenAI GPT-4.1 mini",
            "https://api.openai.com/v1",
            "gpt-4.1-mini"),
    GEMINI_3_7_FLASH(
            "Gemini 3.7 Flash",
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            "gemini-3.7-flash");

    private final String displayName;
    private final String endpoint;
    private final String model;

    ModelProviderPreset(String displayName, String endpoint, String model) {
        this.displayName = displayName;
        this.endpoint = endpoint;
        this.model = model;
    }

    String endpoint() {
        return endpoint;
    }

    String model() {
        return model;
    }

    boolean isCustom() {
        return this == CUSTOM;
    }

    static ModelProviderPreset matching(String endpoint, String model) {
        ModelEndpointValidator.Result actual = ModelEndpointValidator.validate(
                endpoint, false);
        if (!actual.valid()) return CUSTOM;
        String normalizedModel = model == null ? "" : model.trim();
        for (ModelProviderPreset preset : values()) {
            if (preset.isCustom() || !preset.model.equals(normalizedModel)) continue;
            ModelEndpointValidator.Result candidate = ModelEndpointValidator.validate(
                    preset.endpoint, false);
            if (candidate.valid()
                    && candidate.normalizedEndpoint().equals(actual.normalizedEndpoint())) {
                return preset;
            }
        }
        return CUSTOM;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
