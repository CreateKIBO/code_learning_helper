package com.codementor.review;

import com.codementor.model.ModelRequestConfig;
import com.codementor.settings.CodeMentorProjectConsent;
import com.codementor.settings.CodeMentorSettings;
import com.intellij.testFramework.LightPlatformTestCase;

import java.util.ArrayList;
import java.util.List;

/** Coordinator 共用守卫的失败路径与放行路径回归。 */
public class ModelTaskGuardsTest extends LightPlatformTestCase {

    private CodeMentorSettings.State originalState;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        originalState = CodeMentorSettings.getInstance().getState();
    }

    @Override
    protected void tearDown() throws Exception {
        CodeMentorSettings.getInstance().loadState(originalState);
        super.tearDown();
    }

    public void testCaptureConfigReportsInvalidSettings() {
        loadState("not-a-url", "any-model");

        List<String> messages = new ArrayList<>();
        ModelRequestConfig config = ModelTaskGuards.captureConfig(messages::add);

        assertNull("无效配置应返回 null", config);
        assertEquals(1, messages.size());
        assertNotNull(messages.get(0));
    }

    public void testCaptureConfigReturnsConfigForValidSettings() {
        loadState("https://api.deepseek.com", "deepseek-v4-flash");

        List<String> messages = new ArrayList<>();
        ModelRequestConfig config = ModelTaskGuards.captureConfig(messages::add);

        assertNotNull("有效配置不应失败：" + messages, config);
        assertTrue(messages.isEmpty());
        assertEquals("https://api.deepseek.com/chat/completions", config.endpoint());
    }

    public void testGuardOutboundDeniesWhenProjectConsentMissing() {
        loadState("https://api.deepseek.com", "deepseek-v4-flash");
        ModelRequestConfig config = ModelRequestConfig.capture(
                CodeMentorSettings.getInstance());

        List<String> reasons = new ArrayList<>();
        boolean allowed = ModelTaskGuards.guardOutbound(
                getProject(), config, null, reasons::add);

        assertFalse(allowed);
        assertEquals(1, reasons.size());
        assertTrue("拒绝原因应提到项目同意：" + reasons.get(0),
                reasons.get(0).contains("同意"));
    }

    public void testGuardOutboundPermitsAfterConsentGranted() {
        loadState("https://api.deepseek.com", "deepseek-v4-flash");
        ModelRequestConfig config = ModelRequestConfig.capture(
                CodeMentorSettings.getInstance());
        CodeMentorProjectConsent.getInstance(getProject()).grant(config);

        boolean allowed = ModelTaskGuards.guardOutbound(
                getProject(), config, null, reason -> fail("不应拒绝：" + reason));

        assertTrue(allowed);
    }

    private static void loadState(String apiBaseUrl, String model) {
        CodeMentorSettings.State state = new CodeMentorSettings.State();
        state.apiBaseUrl = apiBaseUrl;
        state.model = model;
        CodeMentorSettings.getInstance().loadState(state);
    }
}
