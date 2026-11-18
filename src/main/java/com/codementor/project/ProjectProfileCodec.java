package com.codementor.project;

import com.codementor.javaweb.JavaWebRole;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 项目画像与 JSON 之间的显式转换。
 *
 * <p>本机文件也按不可信输入处理：字段逐个读取并经 {@link ClassFacts} 的构造校验截断，
 * 版本不符或结构损坏时返回 null，调用方重新扫描即可，不会把坏数据带进提示词。
 */
public final class ProjectProfileCodec {

    static final int SCHEMA_VERSION = 1;
    /** 读取时的硬上限，防止异常大的文件在解析前耗尽内存。 */
    public static final int MAX_JSON_CHARS = 8_000_000;

    private static final Gson GSON = new Gson();

    private ProjectProfileCodec() {}

    public static String toJson(ProjectProfile profile) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("scannedAt", profile.scannedAtEpochMillis());
        root.addProperty("scannedFiles", profile.scannedFiles());
        root.addProperty("skippedFiles", profile.skippedFiles());
        root.addProperty("truncated", profile.truncated());
        JsonArray classes = new JsonArray();
        for (ClassFacts facts : profile.classes()) {
            classes.add(classToJson(facts));
        }
        root.add("classes", classes);
        return GSON.toJson(root);
    }

    /** 返回 null 表示文件不可用（版本不符、过大或损坏）。 */
    public static ProjectProfile fromJson(String json) {
        if (json == null || json.isBlank() || json.length() > MAX_JSON_CHARS) return null;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) return null;
            JsonObject root = parsed.getAsJsonObject();
            if (intValue(root, "schemaVersion") != SCHEMA_VERSION) return null;
            JsonElement classesElement = root.get("classes");
            if (classesElement == null || !classesElement.isJsonArray()) return null;
            List<ClassFacts> classes = new ArrayList<>();
            for (JsonElement element : classesElement.getAsJsonArray()) {
                if (classes.size() >= ProjectProfile.MAX_CLASSES) break;
                if (!element.isJsonObject()) continue;
                ClassFacts facts = classFromJson(element.getAsJsonObject());
                if (facts != null) classes.add(facts);
            }
            return new ProjectProfile(
                    classes,
                    intValue(root, "scannedFiles"),
                    intValue(root, "skippedFiles"),
                    booleanValue(root, "truncated"),
                    longValue(root, "scannedAt"));
        } catch (JsonParseException | IllegalStateException
                | UnsupportedOperationException | NumberFormatException e) {
            return null;
        }
    }

    private static JsonObject classToJson(ClassFacts facts) {
        JsonObject object = new JsonObject();
        object.addProperty("path", facts.relativePath());
        object.addProperty("package", facts.packageName());
        object.addProperty("name", facts.simpleName());
        object.addProperty("kind", facts.kind().name());
        object.addProperty("role", facts.role().name());
        object.addProperty("test", facts.testSource());
        object.add("annotations", strings(facts.annotations()));
        object.add("superTypes", strings(facts.superTypes()));
        object.add("constructorTypes", strings(facts.constructorParameterTypes()));
        JsonArray fields = new JsonArray();
        for (ClassFacts.FieldFact field : facts.fields()) {
            JsonObject item = new JsonObject();
            item.addProperty("type", field.typeName());
            item.addProperty("name", field.name());
            item.addProperty("final", field.isFinal());
            item.addProperty("static", field.isStatic());
            item.add("annotations", strings(field.annotations()));
            fields.add(item);
        }
        object.add("fields", fields);
        JsonArray methods = new JsonArray();
        for (ClassFacts.MethodFact method : facts.methods()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", method.name());
            item.addProperty("returns", method.returnType());
            item.addProperty("public", method.isPublic());
            item.add("parameters", strings(method.parameterTypes()));
            item.add("annotations", strings(method.annotations()));
            methods.add(item);
        }
        object.add("methods", methods);
        return object;
    }

    private static ClassFacts classFromJson(JsonObject object) {
        String name = stringValue(object, "name");
        if (name.isBlank()) return null;
        List<ClassFacts.FieldFact> fields = new ArrayList<>();
        for (JsonObject item : objects(object, "fields", ClassFacts.MAX_FIELDS)) {
            fields.add(new ClassFacts.FieldFact(
                    stringValue(item, "type"),
                    stringValue(item, "name"),
                    booleanValue(item, "final"),
                    booleanValue(item, "static"),
                    stringList(item, "annotations")));
        }
        List<ClassFacts.MethodFact> methods = new ArrayList<>();
        for (JsonObject item : objects(object, "methods", ClassFacts.MAX_METHODS)) {
            methods.add(new ClassFacts.MethodFact(
                    stringValue(item, "name"),
                    stringValue(item, "returns"),
                    stringList(item, "parameters"),
                    stringList(item, "annotations"),
                    booleanValue(item, "public")));
        }
        return new ClassFacts(
                stringValue(object, "path"),
                stringValue(object, "package"),
                name,
                enumValue(ClassFacts.Kind.class, stringValue(object, "kind"), ClassFacts.Kind.CLASS),
                enumValue(JavaWebRole.class, stringValue(object, "role"), JavaWebRole.UNKNOWN),
                booleanValue(object, "test"),
                stringList(object, "annotations"),
                stringList(object, "superTypes"),
                fields,
                stringList(object, "constructorTypes"),
                methods);
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        return array;
    }

    private static List<JsonObject> objects(JsonObject parent, String key, int limit) {
        JsonElement element = parent.get(key);
        if (element == null || !element.isJsonArray()) return List.of();
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (result.size() >= limit) break;
            if (item.isJsonObject()) result.add(item.getAsJsonObject());
        }
        return result;
    }

    private static List<String> stringList(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        if (element == null || !element.isJsonArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (result.size() >= ClassFacts.MAX_METHODS) break;
            if (item.isJsonPrimitive()) result.add(item.getAsString());
        }
        return result;
    }

    private static String stringValue(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static boolean booleanValue(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isBoolean()
                && element.getAsBoolean();
    }

    private static int intValue(JsonObject parent, String key) {
        long value = longValue(parent, key);
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, value);
    }

    private static long longValue(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isNumber()
                ? element.getAsLong()
                : 0L;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
