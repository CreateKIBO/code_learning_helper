package com.codementor.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Validates model endpoints before any credential or learning context is sent. */
public final class ModelEndpointValidator {

    private static final int MAX_ENDPOINT_LENGTH = 2_048;
    private static final int MAX_MODEL_LENGTH = 128;
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String DEEPSEEK_HOST = "api.deepseek.com";

    private ModelEndpointValidator() {}

    public static Result validate(String rawEndpoint, boolean allowInsecureLocalEndpoint) {
        String endpoint = rawEndpoint == null ? "" : rawEndpoint.trim();
        if (endpoint.isEmpty()) {
            return Result.invalid("API 地址不能为空");
        }
        if (endpoint.length() > MAX_ENDPOINT_LENGTH || containsControlCharacter(endpoint)) {
            return Result.invalid("API 地址过长或包含非法控制字符");
        }

        final URI parsed;
        try {
            parsed = new URI(endpoint);
        } catch (URISyntaxException e) {
            return Result.invalid("API 地址格式不正确");
        }

        String scheme = parsed.getScheme();
        String host = parsed.getHost();
        if (scheme == null || host == null || host.isBlank()) {
            return Result.invalid("API 地址必须包含协议和主机名");
        }
        if (parsed.getRawUserInfo() != null) {
            return Result.invalid("API 地址不能包含用户名或密码");
        }
        if (parsed.getRawFragment() != null) {
            return Result.invalid("API 地址不能包含 # 片段");
        }
        if (parsed.getRawQuery() != null) {
            return Result.invalid("API 地址不能包含查询参数");
        }
        if (parsed.getPort() == 0 || parsed.getPort() < -1 || parsed.getPort() > 65_535) {
            return Result.invalid("API 地址端口无效");
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.startsWith("[") && normalizedHost.endsWith("]")) {
            normalizedHost = normalizedHost.substring(1, normalizedHost.length() - 1);
        }
        boolean https = "https".equals(normalizedScheme);
        boolean permittedLocalHttp = "http".equals(normalizedScheme)
                && allowInsecureLocalEndpoint
                && isLoopbackHost(normalizedHost);
        if (!https && !permittedLocalHttp) {
            return Result.invalid(
                    "API 地址必须使用 HTTPS；仅在显式启用后允许 HTTP 本机回环地址");
        }

        try {
            String requestPath = resolveChatCompletionsPath(
                    normalizedHost, parsed.getPath());
            URI normalized = new URI(
                    normalizedScheme,
                    null,
                    normalizedHost,
                    parsed.getPort(),
                    requestPath,
                    null,
                    null).normalize();
            return Result.valid(normalized.toASCIIString());
        } catch (URISyntaxException e) {
            return Result.invalid("API 地址无法规范化");
        }
    }

    public static String validateModel(String rawModel) {
        String model = rawModel == null ? "" : rawModel.trim();
        if (model.isEmpty()) {
            return "模型名称不能为空";
        }
        if (model.length() > MAX_MODEL_LENGTH || containsControlCharacter(model)) {
            return "模型名称过长或包含非法控制字符";
        }
        if (!model.matches("[A-Za-z0-9][A-Za-z0-9._:/@-]*")) {
            return "模型名称只能包含字母、数字、点、横线、下划线、冒号、斜杠或 @";
        }
        return "";
    }

    /** Adds the standard Chat Completions route only when the input is clearly a base URL. */
    private static String resolveChatCompletionsPath(String host, String rawPath) {
        String path = rawPath == null ? "" : rawPath;
        String withoutTrailingSlash = stripTrailingSlashes(path);
        if (withoutTrailingSlash.isEmpty()) {
            return CHAT_COMPLETIONS_PATH;
        }
        if (withoutTrailingSlash.endsWith(CHAT_COMPLETIONS_PATH)) {
            return withoutTrailingSlash;
        }
        if (withoutTrailingSlash.endsWith("/v1")
                || (DEEPSEEK_HOST.equals(host)
                        && withoutTrailingSlash.endsWith("/beta"))) {
            return withoutTrailingSlash + CHAT_COMPLETIONS_PATH;
        }
        return path;
    }

    private static String stripTrailingSlashes(String path) {
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') end--;
        return path.substring(0, end);
    }

    public static String validateModelForEndpoint(
            String rawModel, String normalizedEndpoint) {
        String modelError = validateModel(rawModel);
        if (!modelError.isEmpty()) return modelError;
        if (isOfficialDeepSeekEndpoint(normalizedEndpoint)
                && ("deepseek-chat".equalsIgnoreCase(rawModel.trim())
                        || "deepseek-reasoner".equalsIgnoreCase(rawModel.trim()))) {
            return "DeepSeek 官方已停用该旧模型名；请改用 deepseek-v4-flash"
                    + " 或 deepseek-v4-pro";
        }
        return "";
    }

    static boolean isOfficialDeepSeekEndpoint(String endpoint) {
        try {
            return DEEPSEEK_HOST.equalsIgnoreCase(URI.create(endpoint).getHost());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isLoopbackHost(String host) {
        if ("localhost".equals(host)
                || "::1".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host)) {
            return true;
        }
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4 || !"127".equals(octets[0])) {
            return false;
        }
        for (String octet : octets) {
            try {
                int value = Integer.parseInt(octet);
                if (value < 0 || value > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(ch -> Character.isISOControl(ch));
    }

    public record Result(boolean valid, String normalizedEndpoint, String errorMessage) {
        private static Result valid(String endpoint) {
            return new Result(true, endpoint, "");
        }

        private static Result invalid(String message) {
            return new Result(false, "", message);
        }
    }
}
