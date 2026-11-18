package com.codementor.settings;

import com.codementor.model.ConnectionTester;
import com.codementor.model.ModelEndpointValidator;
import com.codementor.review.ReviewCoordinator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.JBColor;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Arrays;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Settings → Tools → CodeMentor. Secrets are never refilled into the password field. */
public final class CodeMentorConfigurable implements Configurable {

    private JPasswordField apiKeyField;
    private JBLabel apiKeyStatusLabel;
    private JBCheckBox clearApiKeyCheckBox;
    private JComboBox<ModelProviderPreset> presetCombo;
    private JBTextField apiUrlField;
    private JBTextField modelField;
    private JComboBox<CodeMentorSettings.TeachingLevel> levelCombo;
    private JBCheckBox enableReviewCheckBox;
    private JBCheckBox enableSaveReviewCheckBox;
    private JBCheckBox previewEveryRequestCheckBox;
    private JBCheckBox allowInsecureLocalEndpointCheckBox;
    private JBTextArea excludedPatternsArea;
    private JButton testConnectionButton;
    private JBLabel connectionStatusLabel;

    private final AtomicLong apiKeyStatusGeneration = new AtomicLong();
    private final AtomicLong connectionGeneration = new AtomicLong();
    private Future<?> apiKeyStatusFuture;
    private Future<?> connectionTestFuture;
    private volatile boolean disposed;
    private boolean syncingPreset;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "CodeMentor";
    }

    @Override
    public @Nullable JComponent createComponent() {
        disposed = false;
        JPanel panel = new JBPanel<>(new GridBagLayout());
        GridBagConstraints gbc = constraints();

        addLabel(panel, gbc, 0, "API Key：");
        apiKeyField = new JPasswordField();
        apiKeyField.setToolTipText("留空表示不修改；API Key 仅保存到 IntelliJ PasswordSafe。");
        apiKeyField.getAccessibleContext().setAccessibleName("CodeMentor API Key");
        addWide(panel, gbc, 0, apiKeyField);

        apiKeyStatusLabel = new JBLabel("正在检查已保存的 API Key…");
        addWide(panel, gbc, 1, apiKeyStatusLabel);
        clearApiKeyCheckBox = new JBCheckBox("删除 PasswordSafe 中已保存的 API Key");
        clearApiKeyCheckBox.addActionListener(event -> {
            boolean deleting = clearApiKeyCheckBox.isSelected();
            if (deleting) {
                apiKeyField.setText("");
                cancelConnectionTest();
            }
            apiKeyField.setEnabled(!deleting);
            testConnectionButton.setEnabled(!deleting);
        });
        addWide(panel, gbc, 2, clearApiKeyCheckBox);

        addLabel(panel, gbc, 3, "常用模型预设：");
        presetCombo = new JComboBox<>(ModelProviderPreset.values());
        presetCombo.setToolTipText("只填充 API 地址和模型名；API Key 保持空白。");
        presetCombo.addActionListener(event -> applySelectedPreset());
        addWide(panel, gbc, 3, presetCombo);

        addLabel(panel, gbc, 4, "API 根地址或完整地址：");
        apiUrlField = new JBTextField();
        apiUrlField.setToolTipText(
                "可填写 https://api.deepseek.com、以 /v1 结尾的 Base URL，"
                        + "或完整 /chat/completions 地址；更换地址后需重新授权当前项目。");
        addWide(panel, gbc, 4, apiUrlField);

        addLabel(panel, gbc, 5, "模型：");
        modelField = new JBTextField();
        modelField.setToolTipText(
                "默认 glm-5.2；DeepSeek 官方当前可使用 deepseek-v4-flash"
                        + " 或 deepseek-v4-pro。");
        addWide(panel, gbc, 5, modelField);
        attachPresetSync(apiUrlField);
        attachPresetSync(modelField);

        addLabel(panel, gbc, 6, "教学级别：");
        levelCombo = new JComboBox<>(CodeMentorSettings.TeachingLevel.values());
        addWide(panel, gbc, 6, levelCombo);

        enableReviewCheckBox = new JBCheckBox("启用 CodeMentor 评审与学习功能");
        enableReviewCheckBox.addActionListener(event -> updateDependentControls());
        addWide(panel, gbc, 7, enableReviewCheckBox);
        enableSaveReviewCheckBox = new JBCheckBox(
                "保存 Java 文件时评审最后编辑的方法（默认关闭）");
        addWide(panel, gbc, 8, enableSaveReviewCheckBox);
        previewEveryRequestCheckBox = new JBCheckBox("每次手动评审或解释前显示完整脱敏预览");
        addWide(panel, gbc, 9, previewEveryRequestCheckBox);
        allowInsecureLocalEndpointCheckBox = new JBCheckBox(
                "允许 HTTP 本机回环端点（仅 localhost / 127.0.0.0/8 / ::1）");
        allowInsecureLocalEndpointCheckBox.setToolTipText(
                "仅用于本机模型或调试代理；非本机地址仍必须使用 HTTPS。");
        addWide(panel, gbc, 10, allowInsecureLocalEndpointCheckBox);

        addWide(panel, gbc, 11, new JBLabel("自定义排除模式（每行或分号分隔）："));
        excludedPatternsArea = new JBTextArea(5, 44);
        excludedPatternsArea.setLineWrap(false);
        excludedPatternsArea.setToolTipText(
                "示例：**/generated/**；**/secret/**；*Credential.java");
        gbc.gridx = 0;
        gbc.gridy = 12;
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        gbc.weighty = 1;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(new JScrollPane(excludedPatternsArea), gbc);

        JPanel actions = new JPanel();
        testConnectionButton = new JButton("测试连接");
        testConnectionButton.setToolTipText(
                "发送固定的合成导师 JSON 提示，不读取或发送项目代码");
        testConnectionButton.addActionListener(event -> testConnection());
        JButton revokeConsentButton = new JButton("撤销已打开项目的发送授权");
        revokeConsentButton.addActionListener(event -> revokeOpenProjectConsent());
        actions.add(testConnectionButton);
        actions.add(revokeConsentButton);
        gbc.gridy = 13;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(actions, gbc);

        connectionStatusLabel = new JBLabel(
                "测试结果会显示在这里；测试不读取或发送项目代码。");
        connectionStatusLabel.setForeground(JBColor.GRAY);
        connectionStatusLabel.getAccessibleContext().setAccessibleName("模型连接测试状态");
        gbc.gridy = 14;
        panel.add(connectionStatusLabel, gbc);

        JBLabel hint = new JBLabel(
                "<html><body style='width:520px'><hr>"
                        + "API Key 不会写入项目或普通设置文件。代码只会在项目授权后发送；"
                        + "更换端点会自动使旧授权失效。项目外文件和排除文件不会采集。"
                        + "</body></html>");
        gbc.gridy = 15;
        panel.add(hint, gbc);

        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        if (apiUrlField == null) return false;
        CodeMentorSettings settings = CodeMentorSettings.getInstance();
        return !apiUrlField.getText().trim().equals(settings.getApiBaseUrl())
                || !modelField.getText().trim().equals(settings.getModel())
                || levelCombo.getSelectedItem() != settings.getTeachingLevel()
                || enableReviewCheckBox.isSelected() != settings.isReviewEnabled()
                || enableSaveReviewCheckBox.isSelected() != settings.isSaveReviewEnabled()
                || previewEveryRequestCheckBox.isSelected()
                        != settings.isPreviewEveryManualRequest()
                || allowInsecureLocalEndpointCheckBox.isSelected()
                        != settings.isInsecureLocalEndpointAllowed()
                || !excludedPatternsArea.getText().trim()
                        .equals(settings.getExcludedFilePatterns())
                || apiKeyField.getPassword().length > 0
                || clearApiKeyCheckBox.isSelected();
    }

    @Override
    public void apply() throws ConfigurationException {
        String url = apiUrlField.getText().trim();
        boolean allowLocalHttp = allowInsecureLocalEndpointCheckBox.isSelected();
        ModelEndpointValidator.Result endpoint =
                ModelEndpointValidator.validate(url, allowLocalHttp);
        if (!endpoint.valid()) throw new ConfigurationException(endpoint.errorMessage());

        String model = modelField.getText().trim();
        String modelError = ModelEndpointValidator.validateModelForEndpoint(
                model, endpoint.normalizedEndpoint());
        if (!modelError.isEmpty()) throw new ConfigurationException(modelError);

        CodeMentorSettings settings = CodeMentorSettings.getInstance();
        String previousEndpoint = settings.getApiBaseUrl();
        String previousExclusions = settings.getExcludedFilePatterns();
        boolean reviewWasEnabled = settings.isReviewEnabled();
        settings.setApiBaseUrl(endpoint.normalizedEndpoint());
        settings.setModel(model);
        settings.setTeachingLevel(
                (CodeMentorSettings.TeachingLevel) levelCombo.getSelectedItem());
        settings.setReviewEnabled(enableReviewCheckBox.isSelected());
        settings.setSaveReviewEnabled(enableSaveReviewCheckBox.isSelected());
        settings.setPreviewEveryManualRequest(previewEveryRequestCheckBox.isSelected());
        settings.setInsecureLocalEndpointAllowed(allowLocalHttp);
        settings.setExcludedFilePatterns(excludedPatternsArea.getText());
        apiUrlField.setText(settings.getApiBaseUrl());
        modelField.setText(settings.getModel());
        syncPresetSelection();
        excludedPatternsArea.setText(settings.getExcludedFilePatterns());
        if (!previousEndpoint.equals(settings.getApiBaseUrl())
                || !previousExclusions.equals(settings.getExcludedFilePatterns())
                || (reviewWasEnabled && !settings.isReviewEnabled())) {
            cancelOpenProjectRequests();
        }

        char[] keyChars = apiKeyField.getPassword();
        boolean clearKey = clearApiKeyCheckBox.isSelected();
        if (keyChars.length > 0 || clearKey) {
            String newKey = clearKey ? null : new String(keyChars).trim();
            AtomicReference<RuntimeException> writeError = new AtomicReference<>();
            try {
                ProgressManager.getInstance().run(new Task.Modal(
                        null, "安全保存 CodeMentor API Key", false) {
                    @Override
                    public void run(@Nullable ProgressIndicator indicator) {
                        try {
                            ApiKeyStore.setApiKey(
                                    newKey == null || newKey.isEmpty() ? null : newKey);
                        } catch (RuntimeException error) {
                            writeError.set(error);
                        }
                    }
                });
            } finally {
                Arrays.fill(keyChars, '\0');
                apiKeyField.setText("");
                clearApiKeyCheckBox.setSelected(false);
                apiKeyField.setEnabled(true);
                testConnectionButton.setEnabled(true);
            }
            if (writeError.get() != null) {
                throw new ConfigurationException(
                        "API Key 无法写入 PasswordSafe，请检查 IDE 密码存储设置。");
            }
            cancelOpenProjectRequests();
        }
        refreshApiKeyStatus();
    }

    @Override
    public void reset() {
        if (apiUrlField == null) return;
        cancelConnectionTest();
        CodeMentorSettings settings = CodeMentorSettings.getInstance();
        apiUrlField.setText(settings.getApiBaseUrl());
        modelField.setText(settings.getModel());
        levelCombo.setSelectedItem(settings.getTeachingLevel());
        enableReviewCheckBox.setSelected(settings.isReviewEnabled());
        enableSaveReviewCheckBox.setSelected(settings.isSaveReviewEnabled());
        previewEveryRequestCheckBox.setSelected(settings.isPreviewEveryManualRequest());
        allowInsecureLocalEndpointCheckBox.setSelected(
                settings.isInsecureLocalEndpointAllowed());
        excludedPatternsArea.setText(settings.getExcludedFilePatterns());
        apiKeyField.setText("");
        clearApiKeyCheckBox.setSelected(false);
        apiKeyField.setEnabled(true);
        if (connectionStatusLabel != null) {
            connectionStatusLabel.setText("测试结果会显示在这里，不会打断当前设置流程。");
            connectionStatusLabel.setForeground(JBColor.GRAY);
            connectionStatusLabel.setToolTipText(null);
        }
        updateDependentControls();
        refreshApiKeyStatus();
    }

    @Override
    public void disposeUIResources() {
        disposed = true;
        apiKeyStatusGeneration.incrementAndGet();
        connectionGeneration.incrementAndGet();
        cancel(apiKeyStatusFuture);
        cancel(connectionTestFuture);
        if (apiKeyField != null) {
            char[] chars = apiKeyField.getPassword();
            Arrays.fill(chars, '\0');
            apiKeyField.setText("");
        }
        apiKeyField = null;
        apiKeyStatusLabel = null;
        clearApiKeyCheckBox = null;
        apiUrlField = null;
        modelField = null;
        presetCombo = null;
        levelCombo = null;
        enableReviewCheckBox = null;
        enableSaveReviewCheckBox = null;
        previewEveryRequestCheckBox = null;
        allowInsecureLocalEndpointCheckBox = null;
        excludedPatternsArea = null;
        testConnectionButton = null;
        connectionStatusLabel = null;
    }

    private void refreshApiKeyStatus() {
        cancel(apiKeyStatusFuture);
        long expectedGeneration = apiKeyStatusGeneration.incrementAndGet();
        if (apiKeyStatusLabel != null) apiKeyStatusLabel.setText("正在检查已保存的 API Key…");
        try {
            apiKeyStatusFuture = AppExecutorUtil.getAppExecutorService().submit(() -> {
                Boolean configured;
                try {
                    String storedKey = ApiKeyStore.getApiKey();
                    configured = storedKey != null && !storedKey.isBlank();
                } catch (RuntimeException error) {
                    configured = null;
                }
                final Boolean configuredStatus = configured;
                invokeOnSettingsUi(() -> {
                    if (disposed || apiKeyStatusGeneration.get() != expectedGeneration
                            || apiKeyStatusLabel == null) return;
                    apiKeyStatusFuture = null;
                    apiKeyStatusLabel.setText(configuredStatus == null
                            ? "无法读取 PasswordSafe 状态"
                            : configuredStatus
                                    ? "已在 PasswordSafe 中配置（不会回填显示）"
                                    : "尚未配置");
                });
            });
        } catch (RuntimeException error) {
            apiKeyStatusFuture = null;
            if (!disposed && apiKeyStatusGeneration.get() == expectedGeneration
                    && apiKeyStatusLabel != null) {
                apiKeyStatusLabel.setText("暂时无法检查 PasswordSafe 状态");
            }
        }
    }

    private void testConnection() {
        char[] keyChars = apiKeyField.getPassword();
        String enteredKey = new String(keyChars).trim();
        Arrays.fill(keyChars, '\0');
        String url = apiUrlField.getText().trim();
        String model = modelField.getText().trim();
        boolean allowLocalHttp = allowInsecureLocalEndpointCheckBox.isSelected();

        ModelEndpointValidator.Result endpoint =
                ModelEndpointValidator.validate(url, allowLocalHttp);
        if (!endpoint.valid()) {
            showConnectionStatus(endpoint.errorMessage(), false);
            return;
        }
        String modelError = ModelEndpointValidator.validateModelForEndpoint(
                model, endpoint.normalizedEndpoint());
        if (!modelError.isEmpty()) {
            showConnectionStatus(modelError, false);
            return;
        }

        cancelConnectionTest();
        testConnectionButton.setEnabled(false);
        testConnectionButton.setText("测试中…");
        showConnectionStatus(
                "正在连接：" + endpoint.normalizedEndpoint(), null);
        long expectedGeneration = connectionGeneration.incrementAndGet();
        try {
            connectionTestFuture = AppExecutorUtil.getAppExecutorService().submit(() -> {
                ConnectionTester.TestResult result;
                try {
                    String key = enteredKey.isEmpty() ? ApiKeyStore.getApiKey() : enteredKey;
                    result = ConnectionTester.testConnection(
                            endpoint.normalizedEndpoint(), key, model, allowLocalHttp);
                } catch (RuntimeException error) {
                    result = new ConnectionTester.TestResult(
                            false, "无法读取 PasswordSafe 或启动连接测试。");
                }
                final ConnectionTester.TestResult completedResult = result;
                invokeOnSettingsUi(() -> {
                    if (disposed || connectionGeneration.get() != expectedGeneration
                            || testConnectionButton == null) return;
                    connectionTestFuture = null;
                    restoreConnectionButton();
                    showConnectionStatus(completedResult.message, completedResult.success);
                });
            });
        } catch (RuntimeException error) {
            connectionTestFuture = null;
            if (!disposed && connectionGeneration.get() == expectedGeneration) {
                restoreConnectionButton();
                showConnectionStatus("暂时无法启动连接测试，请稍后重试。", false);
            }
        }
    }

    private static void revokeOpenProjectConsent() {
        int revoked = 0;
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (!project.isDisposed()) {
                CodeMentorProjectConsent.getInstance(project).revoke();
                ReviewCoordinator.getInstance(project).cancelAll();
                revoked++;
            }
        }
        Messages.showInfoMessage(
                "已撤销 " + revoked + " 个已打开项目的发送授权。"
                        + "下次手动评审或解释前会重新确认；其他模型操作会先被拒绝。",
                "CodeMentor");
    }

    private static void cancelOpenProjectRequests() {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (!project.isDisposed()) {
                ReviewCoordinator.getInstance(project).cancelAll();
            }
        }
    }

    private void cancelConnectionTest() {
        connectionGeneration.incrementAndGet();
        cancel(connectionTestFuture);
        connectionTestFuture = null;
        restoreConnectionButton();
    }

    private void restoreConnectionButton() {
        if (testConnectionButton != null) {
            testConnectionButton.setEnabled(true);
            testConnectionButton.setText("测试连接");
        }
    }

    private void applySelectedPreset() {
        if (syncingPreset || presetCombo == null) return;
        ModelProviderPreset preset = (ModelProviderPreset) presetCombo.getSelectedItem();
        if (preset == null || preset.isCustom()) return;
        syncingPreset = true;
        try {
            apiUrlField.setText(preset.endpoint());
            modelField.setText(preset.model());
            apiKeyField.setText("");
            clearApiKeyCheckBox.setSelected(false);
            apiKeyField.setEnabled(true);
            testConnectionButton.setEnabled(true);
        } finally {
            syncingPreset = false;
        }
        cancelConnectionTest();
        showConnectionStatus(
                "已载入 " + preset + "；请填写 API Key 后测试连接。", null);
    }

    private void attachPresetSync(JBTextField field) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                syncPresetSelection();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                syncPresetSelection();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                syncPresetSelection();
            }
        });
    }

    private void syncPresetSelection() {
        if (syncingPreset || presetCombo == null
                || apiUrlField == null || modelField == null) return;
        syncingPreset = true;
        try {
            presetCombo.setSelectedItem(ModelProviderPreset.matching(
                    apiUrlField.getText(), modelField.getText()));
        } finally {
            syncingPreset = false;
        }
    }

    /** Settings is modal; these callbacks only update guarded Swing components. */
    static void invokeOnSettingsUi(Runnable runnable) {
        ApplicationManager.getApplication().invokeLater(runnable, ModalityState.any());
    }

    private void updateDependentControls() {
        if (enableReviewCheckBox == null || enableSaveReviewCheckBox == null) return;
        boolean reviewEnabled = enableReviewCheckBox.isSelected();
        enableSaveReviewCheckBox.setEnabled(reviewEnabled);
        previewEveryRequestCheckBox.setEnabled(reviewEnabled);
        levelCombo.setEnabled(reviewEnabled);
    }

    private void showConnectionStatus(String message, Boolean success) {
        if (connectionStatusLabel == null) return;
        String detail = message == null || message.isBlank()
                ? "连接测试没有返回详细信息。"
                : message;
        // Prefixing keeps an endpoint-controlled message in JLabel plain-text mode.
        connectionStatusLabel.setText("连接状态：" + detail);
        connectionStatusLabel.setToolTipText("连接状态：" + detail);
        connectionStatusLabel.setForeground(success == null
                ? JBColor.GRAY
                : success ? new JBColor(new java.awt.Color(0x2E7D32),
                        new java.awt.Color(0x66BB6A)) : JBColor.RED);
    }

    private static GridBagConstraints constraints() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(4, 4, 4, 4);
        return gbc;
    }

    private static void addLabel(
            JPanel panel, GridBagConstraints gbc, int row, String text) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JBLabel(text), gbc);
    }

    private static void addWide(
            JPanel panel, GridBagConstraints gbc, int row, JComponent component) {
        gbc.gridx = component instanceof JBCheckBox || component instanceof JBLabel ? 0 : 1;
        gbc.gridy = row;
        gbc.gridwidth = component instanceof JBCheckBox || component instanceof JBLabel ? 2 : 1;
        gbc.weightx = 1;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(component, gbc);
    }

    private static void cancel(Future<?> future) {
        if (future != null) future.cancel(true);
    }
}
