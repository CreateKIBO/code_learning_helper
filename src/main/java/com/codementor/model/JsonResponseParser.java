package com.codementor.model;

import com.codementor.learning.KnowledgePointNormalizer;
import com.codementor.practice.Exercise;
import com.codementor.review.GuideStep;
import com.codementor.review.ReviewMode;
import com.codementor.review.ReviewResult;
import com.codementor.review.Suggestion;
import com.codementor.review.VerificationResult;
import com.codementor.review.VerificationStatus;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 把不可信模型文本转换为有界、经过协议校验的领域对象。 */
public final class JsonResponseParser {

    private static final Logger LOG = Logger.getInstance(JsonResponseParser.class);

    /** 在 Gson 建树前执行的总字符硬上限。 */
    static final int MAX_RESPONSE_CHARS = 128_000;
    /** 讲解、证据、示例、任务等长文本字段上限。 */
    static final int MAX_FIELD_CHARS = 8_000;
    /** 标题、知识点和枚举等短文本字段上限。 */
    static final int MAX_SHORT_FIELD_CHARS = 512;
    /** 小练习每个数组最多保留的条目数。 */
    static final int MAX_LIST_ITEMS = 20;
    /** 小练习单个数组条目的字符上限。 */
    static final int MAX_LIST_ITEM_CHARS = 2_000;

    private static final int MAX_ENUM_CHARS = 64;
    private static final int MAX_SUMMARY_CHARS = 2_000;
    private static final int MAX_EVIDENCE_CHARS = 512;
    private static final int MAX_GUIDANCE_CHARS = 2_000;
    private static final int MAX_FOLLOW_UP_CHARS = 8_000;
    private static final int MAX_SUGGESTIONS = 3;
    /** 逐段讲解 / 整体思路最多保留的步数。 */
    static final int MAX_GUIDE_STEPS = 8;
    private static final int MAX_SUGGESTION_ITEMS_TO_INSPECT = 20;
    private static final Pattern UNSUPPORTED_EXECUTION_CLAIM = Pattern.compile(
            "(?iu)(?:"
                    + "(?:所有|全部)?\\s*(?:单元)?测试\\s*(?:(?:均|都|全部|全|已经|已)\\s*)*(?:成功\\s*)?通过"
                    + "|(?:代码)?\\s*(?:已经|已)?编译\\s*(?:成功|通过)"
                    + "|(?:代码)\\s*(?:已经|已|可以|能够)?\\s*(?:正常|成功|正确)?\\s*运行"
                    + "|(?:代码)?\\s*(?:已经|已)?运行\\s*(?:完全)?(?:成功|正确|正常)"
                    + "|all\\s+tests?\\s+(?:have\\s+)?passed"
                    + "|tests?\\s+(?:have\\s+)?passed"
                    + "|compiles?\\s+(?:successfully|cleanly|without\\s+errors)"
                    + "|runs?\\s+(?:successfully|correctly|without\\s+errors)"
                    + ")");
    private static final Pattern NON_ASSERTIVE_PREFIX = Pattern.compile(
            "(?iu)(?:请|需要|需|应该|应当|建议|确保|验证|检查|确认|如果|若|是否|能否|尚未|未曾|无法|不能|不得|不代表|没有|待"
                    + "|please|should|need\\s+to|must|ensure|verify|check|if|whether|cannot|not)"
                    + ".{0,24}$");

    private JsonResponseParser() {}

    /** 解析失败时抛此异常；消息不包含模型原文，可安全展示或记录。 */
    public static class ParseException extends Exception {
        public ParseException(String message) { super(message); }
        public ParseException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * 解析评审建议。显式空数组表示没有建议，是合法结果；非空数组若没有任何
     * 合法建议则视为协议错误，避免把坏响应误报成“代码没有问题”。
     */
    public static ReviewResult parseReview(String content) throws ParseException {
        return parseReview(parseRootObject(content, "模型返回"));
    }

    private static ReviewResult parseReview(JsonObject root) throws ParseException {
        String summary = requiredString(root, "summary", MAX_SUMMARY_CHARS);
        rejectUnsupportedExecutionClaim(summary, "summary");
        return new ReviewResult(
                summary,
                parseSuggestions(root));
    }

    public static ReviewResult parseReview(String content, ReviewMode mode)
            throws ParseException {
        JsonObject root = parseRootObject(content, "模型返回");
        ReviewResult result = parseReview(root).withSteps(parseSteps(root, mode));
        if (mode == ReviewMode.NEXT_STEP) {
            if (result.suggestions().size() != 1) {
                throw new ParseException("编码指导模式需要恰好一个最小编码任务");
            }
            if (result.suggestions().get(0).getCertainty() != Suggestion.Certainty.MEDIUM) {
                throw new ParseException("编码指导模式的 certainty 必须为 MEDIUM");
            }
            if (!result.suggestions().get(0).getExample().isBlank()) {
                throw new ParseException("编码指导模式只提供任务，example 必须为空");
            }
            return result;
        }
        if (mode != ReviewMode.EXPLAIN) return result;
        if (result.suggestions().isEmpty()) {
            throw new ParseException("解释模式至少需要一个理解要点");
        }
        if (result.suggestions().stream().anyMatch(
                suggestion -> suggestion.getCertainty() != Suggestion.Certainty.MEDIUM)) {
            throw new ParseException("解释模式的 certainty 必须为 MEDIUM");
        }
        return result;
    }

    /**
     * 解析可选的结果级讲解：解释模式读 {@code walkthrough}（逐段讲解），
     * 下一步指导模式读 {@code roadmap}（整体思路）。
     *
     * <p>这是对主结果的增强而不是协议必填项：缺失、类型不对或单步不合格时只丢弃对应内容，
     * 不让整次请求失败。逐段讲解的代码片段是否真实存在，由证据校验在后面再过滤一次。
     */
    private static List<GuideStep> parseSteps(JsonObject root, ReviewMode mode) {
        if (mode != ReviewMode.EXPLAIN && mode != ReviewMode.NEXT_STEP) return List.of();
        boolean walkthrough = mode == ReviewMode.EXPLAIN;
        JsonElement element = root.get(walkthrough ? "walkthrough" : "roadmap");
        if (element == null || !element.isJsonArray()) return List.of();

        List<GuideStep> steps = new ArrayList<>();
        int inspected = 0;
        for (JsonElement item : element.getAsJsonArray()) {
            if (steps.size() >= MAX_GUIDE_STEPS
                    || inspected >= MAX_SUGGESTION_ITEMS_TO_INSPECT) {
                break;
            }
            inspected++;
            if (!item.isJsonObject()) continue;
            JsonObject object = item.getAsJsonObject();
            String code = walkthrough ? enrichment(object, "code", MAX_EVIDENCE_CHARS) : "";
            String what = enrichment(object, walkthrough ? "what" : "step", MAX_GUIDANCE_CHARS);
            String why = enrichment(object, "why", MAX_GUIDANCE_CHARS);
            if (what.isBlank() || (walkthrough && code.isBlank())) continue;
            steps.add(new GuideStep(code, what, why));
        }
        return List.copyOf(steps);
    }

    /**
     * 读取一个可选的增强字段。与 {@link #optionalString} 不同，任何不合格（类型不对、超长、
     * 声称执行过代码）都降级为空串：增强字段不应该拖垮必填内容。
     */
    private static String enrichment(JsonObject object, String key, int maxChars) {
        if (object == null || !object.has(key)) return "";
        JsonElement element = object.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) return "";
        String value = element.getAsString().trim();
        if (value.length() > maxChars) return "";
        try {
            rejectUnsupportedExecutionClaim(value, key);
        } catch (ParseException e) {
            return "";
        }
        return value;
    }

    /** Backward-compatible suggestion-only parser used by focused tests and callers. */
    public static List<Suggestion> parse(String content) throws ParseException {
        return parseSuggestions(parseRootObject(content, "模型返回"));
    }

    private static List<Suggestion> parseSuggestions(JsonObject root) throws ParseException {
        JsonElement suggestionsElement = root.get("suggestions");
        if (suggestionsElement == null || suggestionsElement.isJsonNull()) {
            throw new ParseException("响应缺少 suggestions 字段");
        }
        if (!suggestionsElement.isJsonArray()) {
            throw new ParseException("suggestions 必须是数组");
        }

        JsonArray suggestions = suggestionsElement.getAsJsonArray();
        if (suggestions.isEmpty()) return List.of();

        List<Suggestion> result = new ArrayList<>();
        int inspected = 0;
        for (JsonElement element : suggestions) {
            if (result.size() >= MAX_SUGGESTIONS
                    || inspected >= MAX_SUGGESTION_ITEMS_TO_INSPECT) {
                LOG.info("模型建议数量超过处理上限，已忽略其余条目");
                break;
            }
            inspected++;
            if (!element.isJsonObject()) {
                LOG.warn("单条建议不是 JSON 对象，已跳过");
                continue;
            }
            try {
                result.add(parseSuggestion(
                        element.getAsJsonObject(), result.size() + 1));
            } catch (ParseException | RuntimeException ignored) {
                // 不记录异常或模型字段，避免外部内容进入 IDE 日志。
                LOG.warn("单条建议字段无效，已跳过");
            }
        }
        if (result.isEmpty()) {
            throw new ParseException("suggestions 非空但没有可用建议");
        }
        return List.copyOf(result);
    }

    private static Suggestion parseSuggestion(JsonObject object, int sequence)
            throws ParseException {
        Suggestion suggestion = new Suggestion.Builder()
                .id("S" + sequence)
                .category(parseCategory(optionalString(
                        object, "category", MAX_ENUM_CHARS)))
                .certainty(parseCertainty(optionalString(
                        object, "certainty", MAX_ENUM_CHARS)))
                .title(requiredString(
                        object, "title", MAX_SHORT_FIELD_CHARS))
                .evidence(requiredString(
                        object, "evidence", MAX_EVIDENCE_CHARS))
                .hint(requiredString(
                        object, "hint", MAX_FIELD_CHARS))
                .explanation(requiredString(
                        object, "explanation", MAX_FIELD_CHARS))
                .example(optionalString(
                        object, "example", MAX_FIELD_CHARS))
                .knowledgePoint(requiredString(
                        object, "knowledgePoint", MAX_SHORT_FIELD_CHARS))
                .verificationCriteria(requiredString(
                        object, "verificationCriteria", MAX_FIELD_CHARS))
                .nextStep(requiredString(
                        object, "nextStep", MAX_GUIDANCE_CHARS))
                .selfCheckQuestion(requiredString(
                        object, "selfCheckQuestion", MAX_GUIDANCE_CHARS))
                .rationale(enrichment(object, "rationale", MAX_GUIDANCE_CHARS))
                .alternatives(enrichment(object, "alternatives", MAX_GUIDANCE_CHARS))
                .pitfall(enrichment(object, "pitfall", MAX_GUIDANCE_CHARS))
                .build();
        rejectUnsupportedExecutionClaim(suggestion.getTitle(), "title");
        rejectUnsupportedExecutionClaim(suggestion.getHint(), "hint");
        rejectUnsupportedExecutionClaim(suggestion.getExplanation(), "explanation");
        rejectUnsupportedExecutionClaim(suggestion.getNextStep(), "nextStep");
        return suggestion;
    }

    /** 解析修改验证响应。 */
    public static VerificationResult parseVerification(String content)
            throws ParseException {
        JsonObject root = parseRootObject(content, "修改验证返回");
        String rawStatus = requiredString(root, "status", MAX_ENUM_CHARS);
        VerificationStatus status;
        try {
            status = VerificationStatus.valueOf(
                    rawStatus.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ParseException("未知的修改验证状态");
        }
        if (status != VerificationStatus.RESOLVED
                && status != VerificationStatus.PARTIALLY_RESOLVED
                && status != VerificationStatus.UNRESOLVED) {
            throw new ParseException("模型返回了不允许的内部验证状态");
        }
        String nextHint = optionalString(root, "nextHint", MAX_FIELD_CHARS);
        if (status != VerificationStatus.RESOLVED && nextHint.isBlank()) {
            throw new ParseException("未完全解决时必须返回 nextHint");
        }
        if (status != VerificationStatus.RESOLVED) {
            rejectUnsupportedExecutionClaim(nextHint, "nextHint");
        }
        return new VerificationResult(
                status,
                requiredString(root, "evidence", MAX_EVIDENCE_CHARS),
                status == VerificationStatus.RESOLVED ? "" : nextHint);
    }

    /** 解析单知识点小练习。 */
    public static Exercise parseExercise(String content) throws ParseException {
        JsonObject root = parseRootObject(content, "小练习返回");
        List<String> hints = stringList(root, "hints");
        List<String> acceptanceCriteria = stringList(root, "acceptanceCriteria");
        if (hints.size() < 2) {
            throw new ParseException("小练习至少需要两条由浅入深的提示");
        }
        if (hints.stream().map(KnowledgePointNormalizer::key).distinct().count() < 2) {
            throw new ParseException("小练习的渐进提示不能重复");
        }
        if (acceptanceCriteria.isEmpty()) {
            throw new ParseException("小练习至少需要一条完成标准");
        }
        return new Exercise(
                requiredString(root, "title", MAX_SHORT_FIELD_CHARS),
                requiredString(root, "knowledgePoint", MAX_SHORT_FIELD_CHARS),
                requiredString(root, "task", MAX_FIELD_CHARS),
                optionalString(root, "starterCode", MAX_FIELD_CHARS),
                stringList(root, "constraints"),
                hints,
                acceptanceCriteria);
    }

    public static Exercise parseExercise(String content, String expectedKnowledgePoint)
            throws ParseException {
        Exercise parsed = parseExercise(content);
        String expectedKey = KnowledgePointNormalizer.key(expectedKnowledgePoint);
        if (expectedKey.isBlank()
                || !expectedKey.equals(
                        KnowledgePointNormalizer.key(parsed.getKnowledgePoint()))) {
            throw new ParseException("小练习偏离了指定知识点");
        }
        return new Exercise(
                parsed.getTitle(),
                expectedKnowledgePoint.trim(),
                parsed.getTask(),
                parsed.getStarterCode(),
                parsed.getConstraints(),
                parsed.getHints(),
                parsed.getAcceptanceCriteria());
    }

    /** Validates the plain-text teaching contract used by a follow-up answer. */
    public static String parseFollowUp(String content) throws ParseException {
        if (content == null || content.isBlank()) {
            throw new ParseException("追问回答不能为空");
        }
        String answer = content.trim();
        if (answer.length() > MAX_FOLLOW_UP_CHARS) {
            throw new ParseException("追问回答超过长度限制");
        }
        rejectUnsupportedExecutionClaim(answer, "追问回答");
        if (!endsWithQuestion(answer)) {
            throw new ParseException("追问回答末尾缺少自测问题");
        }
        return answer;
    }

    private static boolean endsWithQuestion(String answer) {
        int index = answer.length() - 1;
        String closers = "\"'”’）)]}*_`";
        while (index >= 0 && (Character.isWhitespace(answer.charAt(index))
                || closers.indexOf(answer.charAt(index)) >= 0)) {
            index--;
        }
        return index >= 0 && (answer.charAt(index) == '?' || answer.charAt(index) == '？');
    }

    private static void rejectUnsupportedExecutionClaim(String value, String field)
            throws ParseException {
        var matcher = UNSUPPORTED_EXECUTION_CLAIM.matcher(value);
        while (matcher.find()) {
            int sentenceStart = Math.max(
                    value.lastIndexOf('。', matcher.start()),
                    Math.max(value.lastIndexOf('！', matcher.start()),
                            Math.max(value.lastIndexOf('!', matcher.start()),
                                    value.lastIndexOf('\n', matcher.start()))));
            String prefix = value.substring(sentenceStart + 1, matcher.start()).trim();
            int sentenceEnd = value.indexOf('。', matcher.end());
            int chineseQuestion = value.indexOf('？', matcher.end());
            int asciiQuestion = value.indexOf('?', matcher.end());
            int nextQuestion = chineseQuestion < 0
                    ? asciiQuestion
                    : asciiQuestion < 0
                            ? chineseQuestion
                            : Math.min(chineseQuestion, asciiQuestion);
            if (nextQuestion >= 0 && (sentenceEnd < 0 || nextQuestion < sentenceEnd)) continue;
            if (NON_ASSERTIVE_PREFIX.matcher(prefix).find()) continue;
            throw new ParseException(field + " 声称了插件未执行的编译、运行或测试结果");
        }
    }

    private static JsonObject parseRootObject(String content, String label)
            throws ParseException {
        validateResponseText(content);
        try {
            JsonElement parsed = JsonParser.parseString(stripMarkdownFence(content));
            if (!parsed.isJsonObject()) {
                throw new ParseException(label + "不是合法 JSON 对象");
            }
            return parsed.getAsJsonObject();
        } catch (ParseException e) {
            throw e;
        } catch (RuntimeException e) {
            // Gson 的异常消息可能包含模型原文，不向上游透传。
            throw new ParseException(label + " JSON 解析失败", e);
        }
    }

    private static void validateResponseText(String content) throws ParseException {
        if (content == null || content.isBlank()) {
            throw new ParseException("模型返回内容为空");
        }
        if (content.length() > MAX_RESPONSE_CHARS) {
            throw new ParseException("模型返回内容超过长度上限");
        }
    }

    private static Suggestion.Category parseCategory(String raw) throws ParseException {
        if (raw.isBlank()) throw new ParseException("响应缺少 category 字段");
        try {
            return Suggestion.Category.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ParseException("响应包含未知 category 值");
        }
    }

    private static Suggestion.Certainty parseCertainty(String raw) throws ParseException {
        if (raw.isBlank()) throw new ParseException("响应缺少 certainty 字段");
        try {
            return Suggestion.Certainty.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ParseException("响应包含未知 certainty 值");
        }
    }

    private static String requiredString(
            JsonObject object, String key, int maxChars) throws ParseException {
        String value = optionalString(object, key, maxChars);
        if (value.isBlank()) {
            throw new ParseException("响应缺少必填字段 " + key);
        }
        return value;
    }

    private static String optionalString(
            JsonObject object, String key, int maxChars) throws ParseException {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        JsonElement element = object.get(key);
        if (!element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new ParseException("字段 " + key + " 必须是字符串");
        }
        String value = element.getAsString();
        if (value.length() > maxChars) {
            throw new ParseException("字段 " + key + " 超过长度上限");
        }
        return value;
    }

    private static List<String> stringList(JsonObject object, String key)
            throws ParseException {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return List.of();
        }
        JsonElement element = object.get(key);
        if (!element.isJsonArray()) {
            throw new ParseException("字段 " + key + " 必须是数组");
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() > MAX_LIST_ITEMS) {
            throw new ParseException("字段 " + key + " 的条目数超过上限");
        }

        List<String> values = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new ParseException("字段 " + key + " 包含非字符串条目");
            }
            String value = item.getAsString().trim();
            if (value.length() > MAX_LIST_ITEM_CHARS) {
                throw new ParseException("字段 " + key + " 的条目超过长度上限");
            }
            if (!value.isBlank()) values.add(value);
        }
        return List.copyOf(values);
    }

    /** 剥离 ```json ... ``` 或 ``` ... ``` 包裹。 */
    private static String stripMarkdownFence(String content) {
        String value = content.trim();
        if (!value.startsWith("```")) return value;

        int firstNewline = value.indexOf('\n');
        if (firstNewline > 0) {
            value = value.substring(firstNewline + 1);
        }
        int lastFence = value.lastIndexOf("```");
        if (lastFence >= 0) {
            value = value.substring(0, lastFence);
        }
        return value.trim();
    }
}
