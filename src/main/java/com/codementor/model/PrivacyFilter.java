package com.codementor.model;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 发送内容的尽力型敏感信息遮盖器。
 *
 * <p>过滤器只处理高置信场景：敏感名称的赋值、认证头、JWT、常见令牌前缀、
 * URI user-info 和私钥块。它不能代替发送预览或文件排除策略。
 */
public final class PrivacyFilter {

    private static final String MASK = "***";
    private static final String KEY_NAME = "[A-Za-z][A-Za-z0-9_.-]{0,127}";
    private static final Pattern KEY_SEPARATORS = Pattern.compile("[^A-Za-z0-9]");
    private static final Pattern NUMBER_LITERAL =
            Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");

    private static final Pattern PRIVATE_KEY_BLOCK = Pattern.compile(
            "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?"
                    + "-----END [A-Z ]*PRIVATE KEY-----",
            Pattern.CASE_INSENSITIVE);

    /** Java、JSON、properties 和 YAML 中带引号的键值赋值。 */
    private static final Pattern QUOTED_ASSIGNMENT = Pattern.compile(
            "(?m)([\\\"']?(" + KEY_NAME + ")[\\\"']?\\s*[:=]\\s*)"
                    + "(?:\\\"((?:\\\\.|[^\\\"\\\\\\r\\n])*)\\\""
                    + "|'((?:\\\\.|[^'\\\\\\r\\n])*)'"
                    + "|`((?:\\\\.|[^`\\\\\\r\\n])*)`)"
    );

    /** Map.of、setProperty 等 Java 调用中的 "敏感键", "字面量"。 */
    private static final Pattern QUOTED_KEY_ARGUMENT = Pattern.compile(
            "(?m)([\\\"'](" + KEY_NAME + ")[\\\"']\\s*,\\s*)"
                    + "(?:\\\"((?:\\\\.|[^\\\"\\\\\\r\\n])*)\\\""
                    + "|'((?:\\\\.|[^'\\\\\\r\\n])*)')"
    );

    /** properties、YAML、.env 等不带引号的简单字面量。 */
    private static final Pattern UNQUOTED_ASSIGNMENT = Pattern.compile(
            "(?m)([\\\"']?(" + KEY_NAME + ")[\\\"']?\\s*[:=]\\s*)"
                    + "([^\\s,;#&}\\]\\)\\(\\\"'`]+)"
    );

    /** URL 查询串里的敏感参数，例如 access_token=...。 */
    private static final Pattern QUERY_PARAMETER = Pattern.compile(
            "(?i)([?&](" + KEY_NAME + ")=)([^&\\s\\\"'<>#]+)"
    );

    /** https://user:password@host、redis://:password@host 等 URI user-info。 */
    private static final Pattern URI_USER_INFO = Pattern.compile(
            "(?i)(\\b[a-z][a-z0-9+.-]*://[^\\s/@:]*:)([^\\s/@]+)(@)"
    );

    private static final Pattern AUTH_SCHEME = Pattern.compile(
            "(?i)(\\b(?:Bearer|Basic)\\s+)([A-Za-z0-9._~+/=-]{8,})"
    );

    /** 三段式 JWT；限制每段最小长度，避免遮盖普通的 a.b.c 文本。 */
    private static final Pattern JWT = Pattern.compile(
            "(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{5,}"
                    + "\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}"
                    + "(?![A-Za-z0-9_-])"
    );

    /** 只匹配有稳定格式和合理最小长度的常见服务令牌。 */
    private static final Pattern KNOWN_TOKEN_PREFIX = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])(?:"
                    + "(?:AKIA|ASIA)[0-9A-Z]{16}"
                    + "|gh[pousr]_[A-Za-z0-9_]{20,}"
                    + "|github_pat_[A-Za-z0-9_]{20,}"
                    + "|glpat-[A-Za-z0-9_-]{20,}"
                    + "|xox[baprs]-[A-Za-z0-9-]{10,}"
                    + "|sk-(?:proj-)?[A-Za-z0-9_-]{16,}"
                    + "|(?:sk|rk)_(?:live|test)_[A-Za-z0-9]{16,}"
                    + "|AIza[A-Za-z0-9_-]{30,}"
                    + ")(?![A-Za-z0-9])"
    );

    private static final Set<String> NON_SECRET_LITERALS = Set.of(
            "true", "false", "null", "none", "nil", "undefined", MASK);

    private PrivacyFilter() {}

    /** 返回遮盖后的副本；null 和空字符串保持原样。 */
    public static String mask(String content) {
        if (content == null || content.isEmpty()) return content;

        String result = PRIVATE_KEY_BLOCK.matcher(content).replaceAll(MASK);
        result = URI_USER_INFO.matcher(result).replaceAll("$1" + MASK + "$3");
        result = AUTH_SCHEME.matcher(result).replaceAll("$1" + MASK);
        result = JWT.matcher(result).replaceAll(MASK);
        result = KNOWN_TOKEN_PREFIX.matcher(result).replaceAll(MASK);
        result = maskQuotedAssignments(result);
        result = maskQuotedKeyArguments(result);
        result = maskUnquotedAssignments(result);
        return maskQueryParameters(result);
    }

    private static String maskQuotedAssignments(String input) {
        Matcher matcher = QUOTED_ASSIGNMENT.matcher(input);
        StringBuffer result = new StringBuffer(input.length());
        while (matcher.find()) {
            String value = firstPresent(
                    matcher.group(3), matcher.group(4), matcher.group(5));
            if (!isSensitiveKey(matcher.group(2)) || !looksLikeLiteralSecret(value)) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String quote = matcher.group(3) != null
                    ? "\""
                    : matcher.group(4) != null ? "'" : "`";
            matcher.appendReplacement(
                    result,
                    Matcher.quoteReplacement(matcher.group(1) + quote + MASK + quote));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String maskQuotedKeyArguments(String input) {
        Matcher matcher = QUOTED_KEY_ARGUMENT.matcher(input);
        StringBuffer result = new StringBuffer(input.length());
        while (matcher.find()) {
            String value = firstPresent(matcher.group(3), matcher.group(4));
            if (!isSensitiveKey(matcher.group(2)) || !looksLikeLiteralSecret(value)) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String quote = matcher.group(3) != null ? "\"" : "'";
            matcher.appendReplacement(
                    result,
                    Matcher.quoteReplacement(matcher.group(1) + quote + MASK + quote));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String maskUnquotedAssignments(String input) {
        Matcher matcher = UNQUOTED_ASSIGNMENT.matcher(input);
        StringBuffer result = new StringBuffer(input.length());
        while (matcher.find()) {
            String value = matcher.group(3);
            if (!isSensitiveKey(matcher.group(2)) || !looksLikeLiteralSecret(value)) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            matcher.appendReplacement(
                    result,
                    Matcher.quoteReplacement(matcher.group(1) + MASK));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String maskQueryParameters(String input) {
        Matcher matcher = QUERY_PARAMETER.matcher(input);
        StringBuffer result = new StringBuffer(input.length());
        while (matcher.find()) {
            if (!isSensitiveKey(matcher.group(2))
                    || !looksLikeLiteralSecret(matcher.group(3))) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            matcher.appendReplacement(
                    result,
                    Matcher.quoteReplacement(matcher.group(1) + MASK));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String firstPresent(String... values) {
        for (String value : values) {
            if (value != null) return value;
        }
        return "";
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null || key.isBlank()) return false;
        String normalized = KEY_SEPARATORS.matcher(key).replaceAll("")
                .toLowerCase(Locale.ROOT);
        return normalized.endsWith("password")
                || normalized.endsWith("passwd")
                || normalized.endsWith("pwd")
                || normalized.endsWith("secret")
                || normalized.endsWith("secrets")
                || normalized.endsWith("token")
                || normalized.endsWith("apikey")
                || normalized.endsWith("accesskey")
                || normalized.endsWith("privatekey")
                || normalized.endsWith("secretkey")
                || normalized.endsWith("signingkey")
                || normalized.endsWith("encryptionkey")
                || normalized.endsWith("credential")
                || normalized.endsWith("credentials")
                || normalized.endsWith("authorization");
    }

    private static boolean looksLikeLiteralSecret(String value) {
        if (value == null || value.isBlank()) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (NON_SECRET_LITERALS.contains(normalized)
                || NUMBER_LITERAL.matcher(normalized).matches()
                || normalized.startsWith("${")
                || normalized.startsWith("{{")) {
            return false;
        }
        return true;
    }
}
