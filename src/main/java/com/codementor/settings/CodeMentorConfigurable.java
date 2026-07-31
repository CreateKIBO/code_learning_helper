package com.codementor.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.JBTextArea;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CompletableFuture;

/**
 * CodeMentor 设置页（方案第 12 节）。
 *
 * <p>在 IDE 设置 → Tools → CodeMentor 注册（plugin.xml 的 applicationConfigurable）。
 * 配置项：
 * <ul>
 *   <li>API Key：密文输入框，存 {@link ApiKeyStore}（PasswordSafe），不进 State 明文文件</li>
 *   <li>API 地址：默认智谱，可改成自建代理或其他兼容服务</li>
 *   <li>模型：预设下拉（glm-4-plus 等），也可自定义</li>
 *   <li>教学级别：初学 / 进阶 / JavaWeb（方案第 10 节）</li>
 *   <li>评审开关</li>
 *   <li>「测试连接」按钮：发个最小请求验证配置</li>
 * </ul>
 *
 * <p>实现 {@link Configurable}：createComponent 建面板，isModified 判断是否改动，
 * apply 保存。API Key 的读写因走 PasswordSafe（阻塞）必须放后台线程，用
 * CompletableFuture 异步化，避免卡 EDT。
 */
public class CodeMentorConfigurable implements Configurable {

    private JPasswordField apiKeyField;
    private JBTextField apiUrlField;
    private JBTextField modelField;
    private JComboBox<CodeMentorSettings.TeachingLevel> levelCombo;
    private JBCheckBox enableReviewCheckBox;
    private JBCheckBox enableSaveReviewCheckBox;
    private JBCheckBox previewEveryRequestCheckBox;
    private JBTextArea excludedPatternsArea;
    private JButton testConnectionButton;

    // 记录打开设置页时从 PasswordSafe 读到的 Key（用于 isModified 比较）
    private volatile String originalApiKey;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "CodeMentor";
    }

    @Override
    public @Nullable JComponent createComponent() {
        JPanel panel = new JBPanel<>(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.NONE;

        // ---- API Key ----
        panel.add(new JBLabel("API Key："), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        apiKeyField = new JPasswordField();
        apiKeyField.setToolTipText("从智谱开放平台 bigmodel.cn 获取。安全存储于系统钥匙串，不明文落盘。");
        panel.add(apiKeyField, gbc);

        // ---- API 地址 ----
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        panel.add(new JBLabel("API 地址："), gbc);
        gbc.gridx = 1; gbc.weightx = 1; gbc.fill = GridBagConstraints.HORIZONTAL;
        apiUrlField = new JBTextField();
        apiUrlField.setToolTipText("默认智谱官方端点。可改为自建代理。");
        panel.add(apiUrlField, gbc);

        // ---- 模型 ----
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        panel.add(new JBLabel("模型："), gbc);
        gbc.gridx = 1; gbc.weightx = 1; gbc.fill = GridBagConstraints.HORIZONTAL;
        modelField = new JBTextField();
        modelField.setToolTipText("如 glm-4-plus、glm-4-flash。可手动输入其他兼容模型名。");
        panel.add(modelField, gbc);

        // ---- 教学级别 ----
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        panel.add(new JBLabel("教学级别："), gbc);
        gbc.gridx = 1; gbc.weightx = 1; gbc.fill = GridBagConstraints.HORIZONTAL;
        levelCombo = new JComboBox<>(CodeMentorSettings.TeachingLevel.values());
        panel.add(levelCombo, gbc);

        // ---- 开关 ----
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2; gbc.weightx = 0;
        enableReviewCheckBox = new JBCheckBox("启用导师评审（取消勾选则 Alt+M 不触发评审）");
        panel.add(enableReviewCheckBox, gbc);

        gbc.gridy = 5;
        enableSaveReviewCheckBox = new JBCheckBox(
                "保存 Java 文件时评审最后编辑的方法（可选，默认关闭）");
        enableSaveReviewCheckBox.setToolTipText(
                "仅在已同意发送代码后生效；同一代码指纹不会重复调用模型。");
        panel.add(enableSaveReviewCheckBox, gbc);

        gbc.gridy = 6;
        previewEveryRequestCheckBox = new JBCheckBox(
                "每次手动发送前显示脱敏预览");
        previewEveryRequestCheckBox.setToolTipText(
                "只影响 Alt+M / Alt+Shift+M 手动请求；保存触发仍按默认关闭和首次同意保护。");
        panel.add(previewEveryRequestCheckBox, gbc);

        gbc.gridy = 7;
        panel.add(new JBLabel("自定义排除模式（每行或分号分隔）："), gbc);
        gbc.gridy = 8;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 1;
        excludedPatternsArea = new JBTextArea(5, 42);
        excludedPatternsArea.setLineWrap(false);
        excludedPatternsArea.setToolTipText(
                "示例：**/generated/**；**/secret/**；*Credential.java");
        panel.add(new JScrollPane(excludedPatternsArea), gbc);

        // ---- 测试连接按钮 ----
        gbc.gridx = 0; gbc.gridy = 9; gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weighty = 0;
        testConnectionButton = new JButton("测试连接");
        testConnectionButton.setToolTipText("用当前填写的配置发一个最小请求，验证是否可用。");
        testConnectionButton.addActionListener(e -> testConnection());
        panel.add(testConnectionButton, gbc);

        // 提示
        gbc.gridy = 10;
        JBLabel hint = new JBLabel("<html><body style='width:400px'><hr>"
                + "API Key 通过系统钥匙串（Windows DPAPI / macOS Keychain）安全存储，不会写入项目文件或 Git。<br>"
                + "首次评审代码时会弹出发送预览，确认后才会发送。<br>"
                + "内置排除：密钥/证书、.env、.git、.idea、build、target、out、node_modules。"
                + "</body></html>");
        hint.setForeground(new java.awt.Color(120, 120, 120));
        panel.add(hint, gbc);

        // 初始加载
        reset();

        return panel;
    }

    @Override
    public boolean isModified() {
        CodeMentorSettings s = CodeMentorSettings.getInstance();
        if (!safeEquals(apiUrlField.getText(), s.getApiBaseUrl())) return true;
        if (!safeEquals(modelField.getText(), s.getModel())) return true;
        if (levelCombo.getSelectedItem() != s.getTeachingLevel()) return true;
        if (enableReviewCheckBox.isSelected() != s.isReviewEnabled()) return true;
        if (enableSaveReviewCheckBox.isSelected() != s.isSaveReviewEnabled()) return true;
        if (previewEveryRequestCheckBox.isSelected()
                != s.isPreviewEveryManualRequest()) return true;
        if (!safeEquals(
                excludedPatternsArea.getText(), s.getExcludedFilePatterns())) return true;
        // API Key 走 PasswordSafe，单独比对
        if (!safeEquals(apiKeyText(), originalApiKey)) return true;
        return false;
    }

    @Override
    public void apply() throws ConfigurationException {
        CodeMentorSettings s = CodeMentorSettings.getInstance();
        s.setApiBaseUrl(apiUrlField.getText().trim());
        s.setModel(modelField.getText().trim());
        s.setTeachingLevel((CodeMentorSettings.TeachingLevel) levelCombo.getSelectedItem());
        s.setReviewEnabled(enableReviewCheckBox.isSelected());
        s.setSaveReviewEnabled(enableSaveReviewCheckBox.isSelected());
        s.setPreviewEveryManualRequest(previewEveryRequestCheckBox.isSelected());
        s.setExcludedFilePatterns(excludedPatternsArea.getText().trim());
        // PasswordSafe 阻塞，必须后台线程
        String newKey = apiKeyText();
        CompletableFuture.runAsync(() -> ApiKeyStore.setApiKey(newKey == null || newKey.isEmpty() ? null : newKey.trim()));
        originalApiKey = newKey;
    }

    @Override
    public void reset() {
        CodeMentorSettings s = CodeMentorSettings.getInstance();
        apiUrlField.setText(s.getApiBaseUrl());
        modelField.setText(s.getModel());
        levelCombo.setSelectedItem(s.getTeachingLevel());
        enableReviewCheckBox.setSelected(s.isReviewEnabled());
        enableSaveReviewCheckBox.setSelected(s.isSaveReviewEnabled());
        previewEveryRequestCheckBox.setSelected(s.isPreviewEveryManualRequest());
        excludedPatternsArea.setText(s.getExcludedFilePatterns());
        // PasswordSafe 阻塞，异步加载，先置空
        apiKeyField.setText("");
        originalApiKey = "";
        CompletableFuture.runAsync(() -> {
            String key = ApiKeyStore.getApiKey();
            originalApiKey = key == null ? "" : key;
            ApplicationManager.getApplication().invokeLater(() -> apiKeyField.setText(originalApiKey));
        });
    }

    /** 「测试连接」：用当前填写的配置发一个最小请求。PasswordSafe 阻塞，走后台。 */
    private void testConnection() {
        String key = apiKeyText().trim();
        if (key.isEmpty()) {
            Messages.showWarningDialog("请先填写 API Key 再测试连接。", "CodeMentor");
            return;
        }
        testConnectionButton.setEnabled(false);
        testConnectionButton.setText("测试中…");
        String url = apiUrlField.getText().trim();
        String model = modelField.getText().trim();
        // 用临时 Key（还没 apply），后台发最小请求
        CompletableFuture.supplyAsync(() ->
                com.codementor.model.ZhiPuModelClient.testConnection(url, key, model))
                .thenAccept(result -> {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        testConnectionButton.setEnabled(true);
                        testConnectionButton.setText("测试连接");
                        if (result.success) {
                            Messages.showInfoMessage("连接成功！" + (result.message == null ? "" : "\n" + result.message), "CodeMentor");
                        } else {
                            Messages.showErrorDialog("连接失败：" + result.message, "CodeMentor");
                        }
                    });
                });
    }

    private static boolean safeEquals(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.equals(b);
    }

    private String apiKeyText() {
        return apiKeyField == null ? "" : new String(apiKeyField.getPassword());
    }
}
