package com.codementor.model;

import java.util.regex.Pattern;

/**
 * 敏感信息遮盖器（方案第 11 节）。
 *
 * <p>发送代码给模型前，对常见的密钥、密码、连接串、私钥块做遮盖，避免误上传真实凭据。
 *
 * <p>注意：正则只能降低风险，无法保证识别所有秘密。因此设置页还有"发送前预览"作为
 * 主要保护措施（方案第 11 节明确）。
 */
public final class PrivacyFilter {

    private PrivacyFilter() {}

    /**
     * 匹配 key = "value" / password: "value" 这类赋值，覆盖常见命名。
     * 大小写不敏感；值用引号或反引号包裹。
     */
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(\\b(?:password|passwd|pwd|secret|api[-_]?key|access[-_]?key|token|authorization|credential|private[-_]?key)\\b\\s*[:=]\\s*)[\"'`]([^\"'`]+)[\"'`]",
            Pattern.CASE_INSENSITIVE);

    /**
     * 匹配 JDBC / 数据库连接串里的密码段，如 jdbc:mysql://...?password=xxx。
     */
    private static final Pattern CONN_STRING_PWD = Pattern.compile(
            "(?i)(password=)[^&\\s\"'<>]+");

    /**
     * 匹配 -----BEGIN ... PRIVATE KEY----- ... -----END ... PRIVATE KEY----- 整块。
     */
    private static final Pattern PRIVATE_KEY_BLOCK = Pattern.compile(
            "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----");

    /** 遮盖后的占位符。 */
    private static final String MASK = "***";

    /**
     * 对代码做遮盖，返回遮盖后的内容（原字符串不被修改）。
     */
    public static String mask(String code) {
        if (code == null || code.isEmpty()) return code;
        String result = code;
        result = PRIVATE_KEY_BLOCK.matcher(result).replaceAll(MASK);
        result = SECRET_ASSIGNMENT.matcher(result).replaceAll("$1" + MASK);
        result = CONN_STRING_PWD.matcher(result).replaceAll("$1" + MASK);
        return result;
    }
}
