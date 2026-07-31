package com.codementor.privacy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 在任何上下文采集和模型调用前判断相对文件路径是否允许分析。 */
public final class FileExclusionPolicy {

    public static final List<String> BUILT_IN_PATTERNS = List.of(
            ".env",
            ".env.*",
            "*.pem",
            "*.key",
            "*.p12",
            "*.pfx",
            "id_rsa",
            "id_ed25519",
            "**/.git/**",
            "**/.idea/**",
            "**/build/**",
            "**/target/**",
            "**/out/**",
            "**/node_modules/**");

    private final List<CompiledPattern> patterns;

    public FileExclusionPolicy(String customPatterns) {
        List<String> allPatterns = new ArrayList<>(BUILT_IN_PATTERNS);
        allPatterns.addAll(parseCustomPatterns(customPatterns));
        patterns = allPatterns.stream()
                .map(CompiledPattern::new)
                .toList();
    }

    public Decision evaluate(String relativePath) {
        String path = normalize(relativePath);
        for (CompiledPattern pattern : patterns) {
            if (pattern.matches(path)) {
                return new Decision(true, pattern.source);
            }
        }
        return new Decision(false, "");
    }

    public static List<String> parseCustomPatterns(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String line : value.split("[;\\r\\n]+")) {
            String pattern = line.trim();
            if (!pattern.isEmpty() && !pattern.startsWith("#")) {
                result.add(pattern);
            }
        }
        return List.copyOf(result);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String path = value.replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        return path;
    }

    private static String globToRegex(String source) {
        String glob = normalize(source);
        boolean basenameOnly = !glob.contains("/");
        boolean anyLeadingDirectories = glob.startsWith("**/");
        if (anyLeadingDirectories) {
            glob = glob.substring(3);
        }

        StringBuilder regex = new StringBuilder();
        if (basenameOnly || anyLeadingDirectories) {
            regex.append("^(?:.*/)?");
        } else {
            regex.append('^');
        }
        for (int i = 0; i < glob.length(); i++) {
            char ch = glob.charAt(i);
            if (ch == '*') {
                boolean doubleStar =
                        i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                regex.append(doubleStar ? ".*" : "[^/]*");
                if (doubleStar) i++;
            } else if (ch == '?') {
                regex.append("[^/]");
            } else {
                if ("\\.[]{}()+-^$|".indexOf(ch) >= 0) {
                    regex.append('\\');
                }
                regex.append(ch);
            }
        }
        return regex.append('$').toString();
    }

    public record Decision(boolean excluded, String matchedPattern) {}

    private static final class CompiledPattern {
        private final String source;
        private final Pattern regex;

        private CompiledPattern(String source) {
            this.source = normalize(source);
            this.regex = Pattern.compile(
                    globToRegex(this.source),
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }

        private boolean matches(String path) {
            return regex.matcher(path).matches();
        }
    }
}
