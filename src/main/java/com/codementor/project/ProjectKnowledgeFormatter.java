package com.codementor.project;

import com.codementor.javaweb.JavaWebRole;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把项目画像渲染成两种有上限的文本：
 * <ul>
 *   <li>{@link #format}：随一次导师请求发送的摘要——项目常见写法、与当前代码相关的项目类
 *       及其方法签名、同层可对照的类。</li>
 *   <li>{@link #overview}：给用户本人在“查看项目画像”里核对的完整一些的版本。</li>
 * </ul>
 *
 * <p>相关类的查找是纯文本的：从目标代码和已采集上下文里取出大写开头的标识符，
 * 到画像里按简单名匹配，因此在索引未就绪（dumb mode）时也能工作。
 */
public final class ProjectKnowledgeFormatter {

    /** 随请求发送的画像摘要上限；与 16,000 字符的代码上下文预算相互独立。 */
    public static final int MAX_KNOWLEDGE_CHARS = 3_000;
    /** 用户查看画像时的展示上限。 */
    public static final int MAX_OVERVIEW_CHARS = 8_000;

    private static final int MAX_RELATED_CLASSES = 5;
    private static final int MAX_MEMBERS_PER_CLASS = 8;
    private static final int MAX_IMPLEMENTORS = 3;
    private static final int MAX_DEPENDENCIES = 6;
    private static final int MAX_PEERS = 3;
    private static final int MAX_IDENTIFIERS = 400;
    private static final int MAX_OVERVIEW_NAMES_PER_LAYER = 12;
    private static final Pattern TYPE_IDENTIFIER = Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b");
    private static final Pattern ACCESSOR = Pattern.compile("^(get|set|is)[A-Z].*");
    private static final Set<String> OBJECT_METHODS =
            Set.of("equals", "hashCode", "toString", "main", "canEqual");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private ProjectKnowledgeFormatter() {}

    /** 一次请求关心的位置：当前文件、分层角色，以及用来查找相关类的代码文本。 */
    public record Focus(String filePath, JavaWebRole role, String targetCode, String modelContext) {
        public Focus {
            filePath = filePath == null ? "" : filePath;
            role = role == null ? JavaWebRole.UNKNOWN : role;
            targetCode = targetCode == null ? "" : targetCode;
            modelContext = modelContext == null ? "" : modelContext;
        }
    }

    /** 生成随请求发送的画像摘要；画像为空时返回空串（等同于未开启项目画像）。 */
    public static String format(ProjectProfile profile, Focus focus) {
        if (profile == null || profile.isEmpty() || focus == null) return "";
        ClassFacts current = currentClass(profile, focus.filePath());
        Bounded out = new Bounded(MAX_KNOWLEDGE_CHARS);
        out.line("【项目画像】本机扫描 " + profile.scannedFiles()
                + " 个 Java 文件得到的声明级摘要（只有类名、注解、字段类型和方法签名，不含方法体）");
        appendConventions(out, ProjectConventions.analyze(profile));

        List<ClassFacts> related = relatedClasses(profile, focus, current);
        if (!related.isEmpty()) {
            out.line("【与当前代码相关的项目类】");
            for (ClassFacts facts : related) {
                appendRelated(out, profile, facts);
            }
        }
        List<String> peers = peers(profile, focus.role(), current, related);
        if (!peers.isEmpty()) {
            out.line("【同层可对照的类】" + String.join("、", peers)
                    + "（与当前类同属 " + focus.role().getLabel() + " 层，可对照它们的写法）");
        }
        return out.toString();
    }

    /** 给用户本人核对“导师记住了什么”的概览，不随请求发送。 */
    public static String overview(ProjectProfile profile) {
        if (profile == null || profile.isEmpty()) {
            return "还没有项目画像。";
        }
        Bounded out = new Bounded(MAX_OVERVIEW_CHARS);
        out.line("扫描时间：" + TIME.format(Instant.ofEpochMilli(profile.scannedAtEpochMillis())));
        out.line("已读取 " + profile.scannedFiles() + " 个 Java 文件，记住 "
                + profile.classes().size() + " 个类型"
                + (profile.skippedFiles() > 0
                        ? "；按排除规则或大小限制跳过 " + profile.skippedFiles() + " 个文件"
                        : "")
                + (profile.truncated() ? "；项目较大，超出上限的部分未读取" : "") + "。");
        out.line("保存内容只有类名、注解、父类型、字段类型与名称、方法签名；不保存方法体、注释和字符串。");
        out.line("");
        appendConventions(out, ProjectConventions.analyze(profile));
        out.line("");
        out.line("【各层的类】");
        for (JavaWebRole role : JavaWebRole.values()) {
            List<String> names = new ArrayList<>();
            int total = 0;
            for (ClassFacts facts : profile.classes()) {
                if (facts.role() != role || facts.testSource()) continue;
                total++;
                if (names.size() < MAX_OVERVIEW_NAMES_PER_LAYER) names.add(facts.simpleName());
            }
            if (total == 0) continue;
            out.line(role.getLabel() + "（" + total + "）：" + String.join("、", names)
                    + (total > names.size() ? " 等" : ""));
        }
        return out.toString();
    }

    private static void appendConventions(Bounded out, ProjectConventions conventions) {
        if (!conventions.basePackage().isEmpty()) {
            out.line("基础包：" + conventions.basePackage());
        }
        if (!conventions.layers().isEmpty()) {
            out.line("分层：" + String.join("；", conventions.layers()));
        }
        if (!conventions.habits().isEmpty()) {
            out.line("【本项目的常见写法】");
            for (String habit : conventions.habits()) {
                out.line("- " + habit);
            }
        }
    }

    private static void appendRelated(Bounded out, ProjectProfile profile, ClassFacts facts) {
        out.line("- " + facts.simpleName() + "（" + facts.kind().keyword()
                + (facts.role() == JavaWebRole.UNKNOWN ? "" : " · " + facts.role().getLabel())
                + (facts.packageName().isEmpty() ? "" : " · 包 " + facts.packageName())
                + "）");
        if (showsFields(facts)) {
            List<String> fields = new ArrayList<>();
            int omitted = 0;
            for (ClassFacts.FieldFact field : facts.fields()) {
                if (field.isStatic()) continue;
                if (fields.size() < MAX_MEMBERS_PER_CLASS) {
                    fields.add(field.typeName() + " " + field.name());
                } else {
                    omitted++;
                }
            }
            if (!fields.isEmpty()) {
                out.line("  字段：" + String.join("；", fields) + omittedSuffix(omitted));
            }
        } else {
            List<String> signatures = new ArrayList<>();
            int omitted = 0;
            for (ClassFacts.MethodFact method : facts.methods()) {
                if (!isTeachingRelevant(facts, method)) continue;
                if (signatures.size() < MAX_MEMBERS_PER_CLASS) {
                    signatures.add(method.signature());
                } else {
                    omitted++;
                }
            }
            if (!signatures.isEmpty()) {
                out.line("  方法：" + String.join("；", signatures) + omittedSuffix(omitted));
            }
        }
        List<String> dependencies = dependencies(profile, facts);
        if (!dependencies.isEmpty()) {
            out.line("  依赖：" + String.join("、", dependencies));
        }
        if (facts.kind() == ClassFacts.Kind.INTERFACE) {
            List<String> implementors = new ArrayList<>();
            for (ClassFacts implementor
                    : profile.implementorsOf(facts.simpleName(), MAX_IMPLEMENTORS)) {
                List<String> inner = dependencies(profile, implementor);
                implementors.add(implementor.simpleName()
                        + (inner.isEmpty() ? "" : "（依赖：" + String.join("、", inner) + "）"));
            }
            if (!implementors.isEmpty()) {
                out.line("  实现类：" + String.join("、", implementors));
            }
        }
    }

    private static String omittedSuffix(int omitted) {
        return omitted > 0 ? "；（其余 " + omitted + " 个省略）" : "";
    }

    /** 数据载体（DTO、实体、record、枚举）看字段更有用；有行为的类看方法签名。 */
    private static boolean showsFields(ClassFacts facts) {
        return facts.role() == JavaWebRole.DTO
                || facts.role() == JavaWebRole.DOMAIN
                || facts.kind() == ClassFacts.Kind.RECORD
                || facts.kind() == ClassFacts.Kind.ENUM;
    }

    private static boolean isTeachingRelevant(ClassFacts owner, ClassFacts.MethodFact method) {
        if (OBJECT_METHODS.contains(method.name())) return false;
        if (owner.kind() == ClassFacts.Kind.INTERFACE) return true;
        if (!method.isPublic()) return false;
        return !(ACCESSOR.matcher(method.name()).matches() && method.parameterTypes().size() <= 1);
    }

    /** 该类字段类型里出现的其他项目类，也就是它在调用链上的下一环。 */
    private static List<String> dependencies(ProjectProfile profile, ClassFacts facts) {
        Set<String> result = new LinkedHashSet<>();
        for (ClassFacts.FieldFact field : facts.fields()) {
            if (field.isStatic()) continue;
            Matcher matcher = TYPE_IDENTIFIER.matcher(field.typeName());
            while (matcher.find() && result.size() < MAX_DEPENDENCIES) {
                String name = matcher.group();
                if (!name.equals(facts.simpleName()) && profile.containsSimpleName(name)) {
                    result.add(name);
                }
            }
            if (result.size() >= MAX_DEPENDENCIES) break;
        }
        return List.copyOf(result);
    }

    private static List<ClassFacts> relatedClasses(
            ProjectProfile profile, Focus focus, ClassFacts current) {
        Set<String> identifiers = new LinkedHashSet<>();
        collectIdentifiers(focus.targetCode(), identifiers);
        collectIdentifiers(focus.modelContext(), identifiers);

        List<ClassFacts> result = new ArrayList<>();
        for (String identifier : identifiers) {
            if (result.size() >= MAX_RELATED_CLASSES) break;
            ClassFacts match = resolve(profile, identifier, focus, current);
            if (match == null || match == current || result.contains(match)) continue;
            result.add(match);
        }
        return result;
    }

    private static void collectIdentifiers(String text, Set<String> target) {
        Matcher matcher = TYPE_IDENTIFIER.matcher(text);
        while (matcher.find() && target.size() < MAX_IDENTIFIERS) {
            target.add(matcher.group());
        }
    }

    /** 同名类有多个时：优先已 import 的，其次同包的，再其次非测试代码里的。 */
    private static ClassFacts resolve(
            ProjectProfile profile, String simpleName, Focus focus, ClassFacts current) {
        List<ClassFacts> candidates = profile.findBySimpleName(simpleName);
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);
        for (ClassFacts candidate : candidates) {
            if (focus.modelContext().contains("import " + candidate.qualifiedName() + ";")) {
                return candidate;
            }
        }
        if (current != null) {
            for (ClassFacts candidate : candidates) {
                if (candidate.packageName().equals(current.packageName())) return candidate;
            }
        }
        for (ClassFacts candidate : candidates) {
            if (!candidate.testSource()) return candidate;
        }
        return candidates.get(0);
    }

    private static List<String> peers(
            ProjectProfile profile,
            JavaWebRole role,
            ClassFacts current,
            List<ClassFacts> related) {
        if (role == JavaWebRole.UNKNOWN) return List.of();
        List<String> samePackage = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (ClassFacts facts : profile.classes()) {
            if (facts.role() != role || facts.testSource()
                    || facts == current || related.contains(facts)) {
                continue;
            }
            if (current != null && facts.packageName().equals(current.packageName())) {
                samePackage.add(facts.simpleName());
            } else {
                others.add(facts.simpleName());
            }
        }
        List<String> result = new ArrayList<>(samePackage);
        result.addAll(others);
        return List.copyOf(result.subList(0, Math.min(result.size(), MAX_PEERS)));
    }

    private static ClassFacts currentClass(ProjectProfile profile, String filePath) {
        if (filePath.isEmpty()) return null;
        for (ClassFacts facts : profile.classes()) {
            if (facts.relativePath().equals(filePath)) return facts;
        }
        return null;
    }

    /** 按整行追加；放不下的行整行丢弃并只标记一次截断，避免把签名截成半句。 */
    private static final class Bounded {
        private static final String TRUNCATED = "…（画像摘要已按长度上限截断）";
        private final StringBuilder text = new StringBuilder();
        private final int limit;
        private boolean truncated;

        private Bounded(int limit) {
            this.limit = limit - TRUNCATED.length() - 1;
        }

        private void line(String value) {
            if (truncated) return;
            if (text.length() + value.length() + 1 > limit) {
                truncated = true;
                text.append(TRUNCATED);
                return;
            }
            text.append(value).append('\n');
        }

        @Override
        public String toString() {
            return text.toString().stripTrailing();
        }
    }
}
