package com.codementor.javaweb;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierList;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 从 Java PSI 的声明信息识别常见 JavaWeb 分层角色和关注点。
 *
 * <p>这里不解析方法体文本，避免把注释或字符串中的单词误当成框架注解。
 */
public final class JavaWebContextAnalyzer {

    private JavaWebContextAnalyzer() {}

    public static JavaWebContext analyze(
            PsiJavaFile javaFile, PsiClass containingClass, PsiMethod targetMethod) {
        if (containingClass == null) {
            return JavaWebContext.unknown();
        }
        Set<String> annotations = new LinkedHashSet<>();
        collectAnnotations(containingClass.getModifierList(), annotations);
        if (targetMethod != null) {
            collectAnnotations(targetMethod.getModifierList(), annotations);
        }
        Set<String> imports = new LinkedHashSet<>();
        if (javaFile.getImportList() != null) {
            String importText = javaFile.getImportList().getText();
            for (String line : importText.split("\\R")) {
                String normalized = line.trim()
                        .replace("import static ", "")
                        .replace("import ", "")
                        .replace(";", "");
                if (!normalized.isBlank()) {
                    imports.add(normalized);
                }
            }
        }
        return classify(containingClass.getName(), annotations, imports);
    }

    /** 纯数据分类入口，便于脱离 IDE fixture 做单元测试。 */
    public static JavaWebContext classify(
            String className, Set<String> annotations, Set<String> imports) {
        Set<String> annotationTokens = normalizedTokens(annotations, Set.of());
        Set<String> tokens = normalizedTokens(annotations, imports);
        JavaWebRole role = detectRole(className, annotationTokens);
        LinkedHashSet<String> concerns = new LinkedHashSet<>();
        addConcern(tokens, concerns, "参数校验",
                "valid", "validated", "notnull", "notblank", "size", "min", "max");
        addConcern(tokens, concerns, "HTTP / REST",
                "requestmapping", "getmapping", "postmapping", "putmapping",
                "patchmapping", "deletemapping", "requestbody", "pathvariable");
        addConcern(tokens, concerns, "事务边界", "transactional");
        addConcern(tokens, concerns, "异常映射",
                "exceptionhandler", "controlleradvice", "restcontrolleradvice");
        addConcern(tokens, concerns, "鉴权边界",
                "preauthorize", "secured", "rolesallowed", "authentication", "principal");
        addConcern(tokens, concerns, "持久化",
                "entity", "repository", "jparepository", "mapper", "select", "insert", "update");

        return new JavaWebContext(role, concerns.stream().toList(), focusFor(role));
    }

    private static JavaWebRole detectRole(String className, Set<String> tokens) {
        String name = className == null ? "" : className.toLowerCase(Locale.ROOT);
        if (containsAny(tokens, "restcontroller", "controller")
                || name.endsWith("controller")) {
            return JavaWebRole.CONTROLLER;
        }
        if (containsAny(tokens, "service") || name.endsWith("service")
                || name.endsWith("serviceimpl")) {
            return JavaWebRole.SERVICE;
        }
        if (containsAny(tokens, "repository", "jparepository", "mapper")
                || name.endsWith("repository") || name.endsWith("mapper")
                || name.endsWith("dao")) {
            return JavaWebRole.REPOSITORY;
        }
        if (name.endsWith("dto") || name.endsWith("request")
                || name.endsWith("response") || name.endsWith("command")
                || name.endsWith("query")) {
            return JavaWebRole.DTO;
        }
        if (containsAny(tokens, "configuration") || name.endsWith("configuration")
                || name.endsWith("config")) {
            return JavaWebRole.CONFIGURATION;
        }
        if (containsAny(tokens, "entity", "document")
                || name.endsWith("entity") || name.endsWith("aggregate")) {
            return JavaWebRole.DOMAIN;
        }
        if (containsAny(tokens, "component")) {
            return JavaWebRole.COMPONENT;
        }
        return JavaWebRole.UNKNOWN;
    }

    private static String focusFor(JavaWebRole role) {
        return switch (role) {
            case CONTROLLER ->
                    "关注 HTTP 语义、外部输入校验、权限边界、异常到响应的映射，以及 Controller 是否保持轻薄。";
            case SERVICE ->
                    "关注业务规则、事务边界、异常语义与依赖协作；不要为了分层而制造无意义抽象。";
            case REPOSITORY ->
                    "关注查询正确性、参数绑定、返回值语义、批量访问和潜在 N+1；业务规则不应下沉到持久化层。";
            case DTO ->
                    "关注输入约束、序列化兼容、字段暴露和领域对象隔离；校验规则应与接口契约一致。";
            case CONFIGURATION ->
                    "关注 Bean 生命周期、条件配置、密钥外置与默认值安全，不讨论无关的业务分层。";
            case DOMAIN ->
                    "关注不变量、实体身份和值对象边界，避免把 HTTP 或数据库细节混入核心模型。";
            case COMPONENT ->
                    "关注职责边界、依赖方向和可测试性，并根据真实复杂度决定是否需要抽象。";
            case UNKNOWN ->
                    "按普通 Java 方法评审，不强行套用 Web 分层规则。";
        };
    }

    private static void collectAnnotations(PsiModifierList modifiers, Set<String> target) {
        if (modifiers == null) return;
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            String qualifiedName = annotation.getQualifiedName();
            if (qualifiedName != null) {
                target.add(qualifiedName);
            } else {
                String text = annotation.getText();
                int parameters = text.indexOf('(');
                target.add(text.substring(1, parameters > 0 ? parameters : text.length()));
            }
        }
    }

    private static Set<String> normalizedTokens(Set<String> annotations, Set<String> imports) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        annotations.forEach(value -> addToken(value, result));
        imports.forEach(value -> addToken(value, result));
        return result;
    }

    private static void addToken(String value, Set<String> target) {
        if (value == null || value.isBlank()) return;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        target.add(normalized);
        int lastDot = normalized.lastIndexOf('.');
        if (lastDot >= 0 && lastDot + 1 < normalized.length()) {
            target.add(normalized.substring(lastDot + 1));
        }
    }

    private static void addConcern(Set<String> tokens,
                                   Set<String> concerns,
                                   String concern,
                                   String... candidates) {
        if (containsAny(tokens, candidates)) {
            concerns.add(concern);
        }
    }

    private static boolean containsAny(Set<String> values, String... candidates) {
        for (String candidate : candidates) {
            for (String value : values) {
                if (value.equals(candidate) || value.endsWith("." + candidate)) {
                    return true;
                }
            }
        }
        return false;
    }
}
