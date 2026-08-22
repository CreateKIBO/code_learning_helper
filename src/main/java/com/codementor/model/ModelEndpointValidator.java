package com.codementor.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Validates model endpoints before any credential or learning context is sent. */
public final class ModelEndpointValidator {

    private static final int MAX_ENDPOINT_LENGTH = 2_048;
    private static final int MAX_MODEL_LENGTH = 128;

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
        if (parsed.getRawPath() == null || parsed.getRawPath().isBlank()) {
            return Result.invalid("API 地址必须包含请求路径");
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
            URI normalized = new URI(
                    normalizedScheme,
                    null,
                    normalizedHost,
                    parsed.getPort(),
                    parsed.getPath(),
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
