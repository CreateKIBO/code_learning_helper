package com.codementor.model;

import com.codementor.review.Suggestion;
import com.codementor.practice.Exercise;
import com.codementor.review.VerificationResult;
import com.codementor.review.VerificationStatus;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型响应解析器（方案第 9 节）。
 *
 * <p>把模型返回的 content 字段解析成 {@link Suggestion} 列表。
 *
 * <p>健壮性处理：
 * <ul>
 *   <li>模型可能把 JSON 包在 markdown ``` 代码块里，先剥离再解析。</li>
 *   <li>category / certainty 字符串若不在枚举内，降级到安全值 + 记日志，不抛异常。</li>
 *   <li>最多保留 3 条（方案约束）。</li>
 *   <li>解析失败抛 {@link ParseException}，由上层（ZhiPuModelClient）重试/降级。</li>
 * </ul>
 */
public final class JsonResponseParser {

    private static final Logger LOG = Logger.getInstance(JsonResponseParser.class);
    private static final int MAX_SUGGESTIONS = 3;

    private JsonResponseParser() {}

    /** 解析失败时抛此异常，message 可直接展示给用户。 */
    public static final class ParseException extends Exception {
        public ParseException(String message) { super(message); }
        public ParseException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * 解析模型返回的 content（chat/completions 响应里 choices[0].message.content 的值）。
     *
     * @return 建议列表（可能为空，表示代码没明显问题）；解析失败抛 ParseException。
     */
    public static List<Suggestion> parse(String content) throws ParseException {
        if (content == null || content.isBlank()) {
            throw new ParseException("模型返回内容为空");
        }
        String json = stripMarkdownFence(content);
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed == null || !parsed.isJsonObject()) {
                throw new ParseException("模型返回不是合法 JSON 对象");
            }
            root = parsed.getAsJsonObject();
        } catch (ParseException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseException("JSON 解析失败：" + e.getMessage(), e);
        }

        JsonArray arr = root.getAsJsonArray("suggestions");
        if (arr == null) {
            throw new ParseException("响应缺少 suggestions 字段");
        }

        List<Suggestion> result = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            if (result.size() >= MAX_SUGGESTIONS) {
                LOG.info("建议超过 3 条，已截断（方案约束）");
                break;
            }
            try {
                result.add(parseSuggestion(el.getAsJsonObject(), result.size() + 1));
            } catch (Exception e) {
                // 单条解析失败跳过，不影响其他条
                LOG.warn("单条建议解析失败，已跳过", e);
            }
        }
        return result;
    }

    /** 解析单条建议。category/certainty 未知值降级处理。 */
    private static Suggestion parseSuggestion(JsonObject obj, int sequence) {
        Suggestion.Builder b = new Suggestion.Builder()
                .id("S" + sequence)
                .category(parseCategory(optStr(obj, "category")))
                .certainty(parseCertainty(optStr(obj, "certainty")))
                .title(optStr(obj, "title"))
                .evidence(optStr(obj, "evidence"))
                .hint(optStr(obj, "hint"))
                .explanation(optStr(obj, "explanation"))
                .example(optStr(obj, "example"))
                .knowledgePoint(optStr(obj, "knowledgePoint"))
                .verificationCriteria(optStr(obj, "verificationCriteria"));
        return b.build();
    }

    /** 解析修改验证响应。 */
    public static VerificationResult parseVerification(String content) throws ParseException {
        if (content == null || content.isBlank()) {
            throw new ParseException("模型返回内容为空");
        }
        try {
            JsonElement parsed = JsonParser.parseString(stripMarkdownFence(content));
            if (!parsed.isJsonObject()) {
                throw new ParseException("修改验证返回不是合法 JSON 对象");
            }
            JsonObject root = parsed.getAsJsonObject();
            String rawStatus = optStr(root, "status");
            VerificationStatus status;
            try {
                status = VerificationStatus.valueOf(rawStatus.trim().toUpperCase());
            } catch (Exception e) {
                throw new ParseException("未知的修改验证状态：" + rawStatus);
            }
            if (status != VerificationStatus.RESOLVED
                    && status != VerificationStatus.PARTIALLY_RESOLVED
                    && status != VerificationStatus.UNRESOLVED) {
                throw new ParseException("模型不能返回内部验证状态：" + rawStatus);
            }
            String evidence = optStr(root, "evidence");
            if (evidence.isBlank()) {
                throw new ParseException("修改验证缺少 evidence");
            }
            return new VerificationResult(status, evidence, optStr(root, "nextHint"));
        } catch (ParseException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseException("修改验证 JSON 解析失败：" + e.getMessage(), e);
        }
    }

    /** 解析单知识点小练习；列表字段缺失时按空列表处理。 */
    public static Exercise parseExercise(String content) throws ParseException {
        if (content == null || content.isBlank()) {
            throw new ParseException("模型返回内容为空");
        }
        try {
            JsonElement parsed = JsonParser.parseString(stripMarkdownFence(content));
            if (!parsed.isJsonObject()) {
                throw new ParseException("小练习返回不是合法 JSON 对象");
            }
            JsonObject root = parsed.getAsJsonObject();
            String title = optStr(root, "title");
            String knowledgePoint = optStr(root, "knowledgePoint");
            String task = optStr(root, "task");
            if (title.isBlank() || knowledgePoint.isBlank() || task.isBlank()) {
                throw new ParseException("小练习缺少 title、knowledgePoint 或 task");
            }
            return new Exercise(
                    title,
                    knowledgePoint,
                    task,
                    optStr(root, "starterCode"),
                    stringList(root, "constraints"),
                    stringList(root, "hints"),
                    stringList(root, "acceptanceCriteria"));
        } catch (ParseException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseException("小练习 JSON 解析失败：" + e.getMessage(), e);
        }
    }

    private static Suggestion.Category parseCategory(String raw) {
        if (raw == null || raw.isBlank()) return Suggestion.Category.JAVA_USAGE;
        try {
            return Suggestion.Category.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            LOG.warn("未知 category 值「" + raw + "」，降级为 JAVA_USAGE");
            return Suggestion.Category.JAVA_USAGE;
        }
    }

    private static Suggestion.Certainty parseCertainty(String raw) {
        if (raw == null || raw.isBlank()) return Suggestion.Certainty.MEDIUM;
        try {
            return Suggestion.Certainty.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            LOG.warn("未知 certainty 值「" + raw + "」，降级为 MEDIUM");
            return Suggestion.Certainty.MEDIUM;
        }
    }

    /** 安全取字符串字段，缺失返回空串（满足 Builder 的默认值语义）。 */
    private static String optStr(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return "";
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : el.toString();
    }

    private static List<String> stringList(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement element : obj.getAsJsonArray(key)) {
            if (element.isJsonPrimitive()) {
                String value = element.getAsString().trim();
                if (!value.isBlank()) values.add(value);
            }
        }
        return values;
    }

    /** 剥离 markdown 代码块包裹（```json ... ``` 或 ``` ... ```），只保留纯 JSON。 */
    private static String stripMarkdownFence(String content) {
        String s = content.trim();
        if (s.startsWith("```")) {
            // 去掉首行 ``` 或 ```json
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                s = s.substring(firstNewline + 1);
            }
            // 去掉结尾的 ```
            int lastFence = s.lastIndexOf("```");
            if (lastFence >= 0) {
                s = s.substring(0, lastFence);
            }
            s = s.trim();
        }
        return s;
    }
}
