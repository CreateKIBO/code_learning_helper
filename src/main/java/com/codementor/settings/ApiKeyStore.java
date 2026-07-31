package com.codementor.settings;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import org.jetbrains.annotations.Nullable;

/**
 * API Key 安全存储 —— 封装 IntelliJ {@link PasswordSafe}（方案第 11、18 节）。
 *
 * <p>为什么不用 {@link CodeMentorSettings} 的 State 存：State 会序列化到明文 XML 文件，
 * API Key 落盘明文是安全事故。PasswordSafe 会按平台选择安全存储后端
 * （Windows 用 DPAPI、macOS 用 Keychain、Linux 用加密），不会明文落盘。
 *
 * <p>线程注意（方案第 18 节）：PasswordSafe 的读写是阻塞操作，<b>不能在 EDT 调用</b>。
 * 调用方（ZhiPuModelClient）保证在后台线程调用。
 */
public final class ApiKeyStore {

    private static final String SERVICE_NAME = "CodeMentor";
    private static final String USER_NAME = "apiKey";

    private ApiKeyStore() {}

    /** 读取 API Key，未配置返回 null。必须在后台线程调用。 */
    @Nullable
    public static String getApiKey() {
        PasswordSafe ps = PasswordSafe.getInstance();
        Credentials credentials = ps.get(createAttributes());
        return credentials != null ? credentials.getPasswordAsString() : null;
    }

    /** 保存 API Key（传 null 则清除）。必须在后台线程调用。 */
    public static void setApiKey(@Nullable String apiKey) {
        PasswordSafe ps = PasswordSafe.getInstance();
        if (apiKey == null || apiKey.isEmpty()) {
            ps.set(createAttributes(), null);
        } else {
            ps.set(createAttributes(), new Credentials(USER_NAME, apiKey));
        }
    }

    private static CredentialAttributes createAttributes() {
        return new CredentialAttributes(SERVICE_NAME, USER_NAME, ApiKeyStore.class, false);
    }
}
