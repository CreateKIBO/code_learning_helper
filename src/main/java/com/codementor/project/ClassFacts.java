package com.codementor.project;

import com.codementor.javaweb.JavaWebRole;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个 Java 类型的声明级事实：类名、分层角色、注解、父类型、字段类型与方法签名。
 *
 * <p>刻意不包含方法体、字段初始值、注释或字符串字面量——项目画像只需要“结构和写法”，
 * 不需要“实现细节”，这样本机保存和随请求发送的内容都保持最小。
 */
public record ClassFacts(
        String relativePath,
        String packageName,
        String simpleName,
        Kind kind,
        JavaWebRole role,
        boolean testSource,
        List<String> annotations,
        List<String> superTypes,
        List<FieldFact> fields,
        List<String> constructorParameterTypes,
        List<MethodFact> methods) {

    public static final int MAX_NAME_CHARS = 120;
    public static final int MAX_PATH_CHARS = 400;
    public static final int MAX_ANNOTATIONS = 12;
    public static final int MAX_SUPER_TYPES = 8;
    public static final int MAX_FIELDS = 30;
    public static final int MAX_METHODS = 40;
    public static final int MAX_PARAMETERS = 10;

    public enum Kind {
        CLASS("class"),
        INTERFACE("interface"),
        ENUM("enum"),
        RECORD("record"),
        ANNOTATION("@interface");

        private final String keyword;

        Kind(String keyword) {
            this.keyword = keyword;
        }

        public String keyword() {
            return keyword;
        }
    }

    /** 字段声明摘要：只有类型、名称、修饰与注解的简单名。 */
    public record FieldFact(
            String typeName,
            String name,
            boolean isFinal,
            boolean isStatic,
            List<String> annotations) {

        public FieldFact {
            typeName = clean(typeName, MAX_NAME_CHARS);
            name = clean(name, MAX_NAME_CHARS);
            annotations = cleanList(annotations, MAX_ANNOTATIONS);
        }
    }

    /** 方法签名摘要：不含方法体。 */
    public record MethodFact(
            String name,
            String returnType,
            List<String> parameterTypes,
            List<String> annotations,
            boolean isPublic) {

        public MethodFact {
            name = clean(name, MAX_NAME_CHARS);
            returnType = clean(returnType, MAX_NAME_CHARS);
            parameterTypes = cleanList(parameterTypes, MAX_PARAMETERS);
            annotations = cleanList(annotations, MAX_ANNOTATIONS);
        }

        /** 例如 {@code User findById(Long)}。 */
        public String signature() {
            return (returnType.isEmpty() ? "" : returnType + " ")
                    + name + "(" + String.join(", ", parameterTypes) + ")";
        }
    }

    public ClassFacts {
        relativePath = clean(relativePath, MAX_PATH_CHARS);
        packageName = clean(packageName, MAX_PATH_CHARS);
        simpleName = clean(simpleName, MAX_NAME_CHARS);
        kind = kind == null ? Kind.CLASS : kind;
        role = role == null ? JavaWebRole.UNKNOWN : role;
        annotations = cleanList(annotations, MAX_ANNOTATIONS);
        superTypes = cleanList(superTypes, MAX_SUPER_TYPES);
        fields = fields == null
                ? List.of()
                : List.copyOf(fields.subList(0, Math.min(fields.size(), MAX_FIELDS)));
        constructorParameterTypes = cleanList(constructorParameterTypes, MAX_PARAMETERS);
        methods = methods == null
                ? List.of()
                : List.copyOf(methods.subList(0, Math.min(methods.size(), MAX_METHODS)));
    }

    public String qualifiedName() {
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    public boolean hasAnnotation(String simpleAnnotationName) {
        return annotations.contains(simpleAnnotationName);
    }

    /**
     * 把任意类型文本归一成不带包名和泛型参数的“外层简单名”，
     * 例如 {@code java.util.List<com.demo.User>} → {@code List}。
     */
    public static String outerSimpleName(String typeText) {
        if (typeText == null) return "";
        String text = typeText.trim();
        int generic = text.indexOf('<');
        if (generic >= 0) text = text.substring(0, generic);
        int array = text.indexOf('[');
        if (array >= 0) text = text.substring(0, array);
        int dot = text.lastIndexOf('.');
        if (dot >= 0) text = text.substring(dot + 1);
        return text.trim();
    }

    /** 去掉控制字符和换行并限制长度，声明摘要永远是单行。 */
    static String clean(String value, int maxChars) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder(Math.min(value.length(), maxChars));
        for (int i = 0; i < value.length() && result.length() < maxChars; i++) {
            char ch = value.charAt(i);
            result.append(Character.isISOControl(ch) ? ' ' : ch);
        }
        return result.toString().trim();
    }

    private static List<String> cleanList(List<String> values, int maxItems) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String cleaned = clean(value, MAX_NAME_CHARS);
            if (!cleaned.isEmpty()) result.add(cleaned);
            if (result.size() >= maxItems) break;
        }
        return List.copyOf(result);
    }
}
