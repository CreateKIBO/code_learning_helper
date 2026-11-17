package com.codementor.project;

import com.codementor.javaweb.JavaWebRole;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从项目画像里归纳“这个项目通常怎么写”：分层规模、依赖注入方式、Controller 返回值、
 * 异常处理、持久层、Lombok、事务和校验等。
 *
 * <p>全部是基于声明的计数统计，不读取方法体，也不做任何好坏评价——
 * 评价留给导师结合当前代码去讲。
 */
public record ProjectConventions(String basePackage, List<String> layers, List<String> habits) {

    private static final List<JavaWebRole> LAYER_ORDER = List.of(
            JavaWebRole.CONTROLLER,
            JavaWebRole.SERVICE,
            JavaWebRole.REPOSITORY,
            JavaWebRole.DTO,
            JavaWebRole.DOMAIN,
            JavaWebRole.CONFIGURATION,
            JavaWebRole.COMPONENT);
    private static final Set<JavaWebRole> BEAN_ROLES = Set.of(
            JavaWebRole.CONTROLLER,
            JavaWebRole.SERVICE,
            JavaWebRole.CONFIGURATION,
            JavaWebRole.COMPONENT);
    private static final Set<String> FIELD_INJECTION = Set.of("Autowired", "Resource", "Inject");
    private static final Set<String> LOMBOK_CONSTRUCTORS =
            Set.of("RequiredArgsConstructor", "AllArgsConstructor");
    private static final Set<String> MAPPINGS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping",
            "PutMapping", "DeleteMapping", "PatchMapping");
    private static final Set<String> ADVICE = Set.of("RestControllerAdvice", "ControllerAdvice");
    private static final Set<String> JPA_REPOSITORIES = Set.of(
            "JpaRepository", "CrudRepository", "PagingAndSortingRepository");
    private static final Set<String> ENTITY_ANNOTATIONS = Set.of("Entity", "Table", "TableName");
    private static final List<String> LOMBOK_ANNOTATIONS =
            List.of("Data", "Getter", "Setter", "Builder", "Slf4j");
    private static final Set<String> CONSTRAINTS = Set.of(
            "NotNull", "NotBlank", "NotEmpty", "Size", "Min", "Max", "Email", "Pattern",
            "Positive", "PositiveOrZero", "Valid");
    private static final int MAX_LAYER_SAMPLES = 3;

    public ProjectConventions {
        basePackage = basePackage == null ? "" : basePackage;
        layers = layers == null ? List.of() : List.copyOf(layers);
        habits = habits == null ? List.of() : List.copyOf(habits);
    }

    public static ProjectConventions analyze(ProjectProfile profile) {
        if (profile == null || profile.isEmpty()) {
            return new ProjectConventions("", List.of(), List.of());
        }
        List<ClassFacts> production = profile.classes().stream()
                .filter(facts -> !facts.testSource())
                .toList();
        List<ClassFacts> tests = profile.classes().stream()
                .filter(ClassFacts::testSource)
                .toList();

        List<String> habits = new ArrayList<>();
        addIfPresent(habits, injectionHabit(production));
        addIfPresent(habits, controllerReturnHabit(production));
        addIfPresent(habits, exceptionHabit(production));
        addIfPresent(habits, persistenceHabit(production));
        addIfPresent(habits, serviceNamingHabit(production));
        addIfPresent(habits, transactionHabit(production));
        addIfPresent(habits, validationHabit(production));
        addIfPresent(habits, lombokHabit(production));
        addIfPresent(habits, testHabit(tests));
        return new ProjectConventions(basePackage(production), layers(production), habits);
    }

    private static String basePackage(List<ClassFacts> classes) {
        String[] common = null;
        int commonLength = 0;
        for (ClassFacts facts : classes) {
            if (facts.packageName().isEmpty()) continue;
            String[] segments = facts.packageName().split("\\.");
            if (common == null) {
                common = segments;
                commonLength = segments.length;
                continue;
            }
            int matched = 0;
            while (matched < commonLength
                    && matched < segments.length
                    && common[matched].equals(segments[matched])) {
                matched++;
            }
            commonLength = matched;
            if (commonLength == 0) return "";
        }
        if (common == null || commonLength == 0) return "";
        return String.join(".", List.of(common).subList(0, commonLength));
    }

    private static List<String> layers(List<ClassFacts> classes) {
        Map<JavaWebRole, List<String>> names = new EnumMap<>(JavaWebRole.class);
        for (ClassFacts facts : classes) {
            names.computeIfAbsent(facts.role(), role -> new ArrayList<>()).add(facts.simpleName());
        }
        List<String> result = new ArrayList<>();
        for (JavaWebRole role : LAYER_ORDER) {
            List<String> members = names.get(role);
            if (members == null || members.isEmpty()) continue;
            List<String> samples = members.subList(0, Math.min(members.size(), MAX_LAYER_SAMPLES));
            result.add(role.getLabel() + " " + members.size() + " 个（"
                    + String.join("、", samples)
                    + (members.size() > samples.size() ? " 等" : "") + "）");
        }
        return result;
    }

    private static String injectionHabit(List<ClassFacts> classes) {
        int constructor = 0;
        int lombok = 0;
        int field = 0;
        for (ClassFacts facts : classes) {
            if (!BEAN_ROLES.contains(facts.role())) continue;
            boolean hasFinalDependency = false;
            boolean usesFieldInjection = false;
            boolean constructorAssignsField = false;
            for (ClassFacts.FieldFact fieldFact : facts.fields()) {
                if (fieldFact.isStatic()) continue;
                if (fieldFact.isFinal()) {
                    hasFinalDependency = true;
                    if (facts.constructorParameterTypes().contains(
                            ClassFacts.outerSimpleName(fieldFact.typeName()))) {
                        constructorAssignsField = true;
                    }
                }
                if (fieldFact.annotations().stream().anyMatch(FIELD_INJECTION::contains)) {
                    usesFieldInjection = true;
                }
            }
            if (constructorAssignsField) constructor++;
            if (hasFinalDependency
                    && facts.annotations().stream().anyMatch(LOMBOK_CONSTRUCTORS::contains)) {
                lombok++;
            }
            if (usesFieldInjection) field++;
        }
        if (constructor + lombok + field == 0) return "";
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("显式构造器注入", constructor);
        counts.put("Lombok 生成构造器注入（@RequiredArgsConstructor 等）", lombok);
        counts.put("字段注入（@Autowired/@Resource）", field);
        return "依赖注入：" + describeCounts(counts, " 个类", true);
    }

    private static String controllerReturnHabit(List<ClassFacts> classes) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (ClassFacts facts : classes) {
            if (facts.role() != JavaWebRole.CONTROLLER) continue;
            for (ClassFacts.MethodFact method : facts.methods()) {
                if (method.annotations().stream().noneMatch(MAPPINGS::contains)) continue;
                String type = ClassFacts.outerSimpleName(method.returnType());
                if (type.isEmpty()) continue;
                counts.merge(type, 1, Integer::sum);
                total++;
            }
        }
        if (total < 2) return "";
        Map.Entry<String, Integer> top = counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElse(null);
        if (top == null || top.getValue() * 2 < total) {
            return "Controller 接口返回值：没有统一的包装类型（" + total + " 个接口方法）";
        }
        return "Controller 接口返回值：多数返回 " + top.getKey()
                + "（" + top.getValue() + "/" + total + " 个接口方法）";
    }

    private static String exceptionHabit(List<ClassFacts> classes) {
        List<String> handlers = new ArrayList<>();
        List<String> custom = new ArrayList<>();
        for (ClassFacts facts : classes) {
            if (facts.annotations().stream().anyMatch(ADVICE::contains)) {
                handlers.add(facts.simpleName());
            } else if (facts.simpleName().endsWith("Exception")
                    && facts.superTypes().stream().anyMatch(type ->
                            type.endsWith("Exception") || type.equals("Throwable"))) {
                custom.add(facts.simpleName());
            }
        }
        if (handlers.isEmpty() && custom.isEmpty()) return "";
        StringBuilder text = new StringBuilder("异常处理：");
        if (!handlers.isEmpty()) {
            text.append("全局异常处理类 ").append(sample(handlers, 2));
        }
        if (!custom.isEmpty()) {
            if (!handlers.isEmpty()) text.append("；");
            text.append("自定义异常 ").append(sample(custom, 3));
        }
        return text.toString();
    }

    private static String persistenceHabit(List<ClassFacts> classes) {
        int mybatis = 0;
        int jpa = 0;
        int entities = 0;
        for (ClassFacts facts : classes) {
            if (facts.hasAnnotation("Mapper") || facts.superTypes().contains("BaseMapper")) {
                mybatis++;
            }
            if (facts.superTypes().stream().anyMatch(JPA_REPOSITORIES::contains)) jpa++;
            if (facts.annotations().stream().anyMatch(ENTITY_ANNOTATIONS::contains)) entities++;
        }
        if (mybatis + jpa + entities == 0) return "";
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("MyBatis Mapper 接口", mybatis);
        counts.put("Spring Data Repository", jpa);
        counts.put("实体类（@Entity/@TableName 等）", entities);
        return "持久层：" + describeCounts(counts, " 个", false);
    }

    private static String serviceNamingHabit(List<ClassFacts> classes) {
        int pairs = 0;
        for (ClassFacts facts : classes) {
            if (facts.simpleName().endsWith("ServiceImpl")
                    && facts.superTypes().stream().anyMatch(type -> type.endsWith("Service"))) {
                pairs++;
            }
        }
        return pairs == 0 ? "" : "Service 命名：采用“接口 + ServiceImpl 实现类”（" + pairs + " 组）";
    }

    private static String transactionHabit(List<ClassFacts> classes) {
        Map<JavaWebRole, Integer> byRole = new EnumMap<>(JavaWebRole.class);
        int total = 0;
        for (ClassFacts facts : classes) {
            int count = facts.hasAnnotation("Transactional") ? 1 : 0;
            for (ClassFacts.MethodFact method : facts.methods()) {
                if (method.annotations().contains("Transactional")) count++;
            }
            if (count > 0) {
                byRole.merge(facts.role(), count, Integer::sum);
                total += count;
            }
        }
        if (total == 0) return "";
        Map.Entry<JavaWebRole, Integer> top = byRole.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow();
        if (top.getValue() * 2 >= total && top.getKey() != JavaWebRole.UNKNOWN) {
            return "事务：@Transactional 主要出现在 " + top.getKey().getLabel()
                    + " 层（" + top.getValue() + "/" + total + " 处）";
        }
        return "事务：@Transactional 共 " + total + " 处，分布在多个层";
    }

    private static String validationHabit(List<ClassFacts> classes) {
        int constrainedFields = 0;
        for (ClassFacts facts : classes) {
            for (ClassFacts.FieldFact field : facts.fields()) {
                if (field.annotations().stream().anyMatch(CONSTRAINTS::contains)) {
                    constrainedFields++;
                }
            }
        }
        return constrainedFields == 0
                ? ""
                : "参数校验：" + constrainedFields + " 个字段使用了 Bean Validation 约束注解";
    }

    private static String lombokHabit(List<ClassFacts> classes) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String annotation : LOMBOK_ANNOTATIONS) {
            int count = 0;
            for (ClassFacts facts : classes) {
                if (facts.hasAnnotation(annotation)) count++;
            }
            counts.put("@" + annotation, count);
        }
        if (counts.values().stream().allMatch(count -> count == 0)) return "";
        return "Lombok：" + describeCounts(counts, " 个类", false);
    }

    private static String testHabit(List<ClassFacts> tests) {
        if (tests.isEmpty()) return "";
        long suffixed = tests.stream()
                .filter(facts -> facts.simpleName().endsWith("Test")
                        || facts.simpleName().endsWith("Tests"))
                .count();
        return "测试：" + tests.size() + " 个测试类"
                + (suffixed * 2 >= tests.size() ? "（多以 Test 结尾命名）" : "");
    }

    /** 只输出非零项；{@code markDominant} 为 true 时给数量最多且过半的一项加“为主”。 */
    private static String describeCounts(
            Map<String, Integer> counts, String unit, boolean markDominant) {
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        List<Map.Entry<String, Integer>> present = counts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .toList();
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < present.size(); i++) {
            Map.Entry<String, Integer> entry = present.get(i);
            boolean dominant = markDominant
                    && i == 0
                    && present.size() > 1
                    && entry.getValue() * 2 > total;
            parts.add(entry.getKey() + " " + entry.getValue() + unit + (dominant ? "（为主）" : ""));
        }
        return String.join("、", parts);
    }

    private static String sample(List<String> names, int limit) {
        List<String> shown = names.subList(0, Math.min(names.size(), limit));
        return String.join("、", shown) + (names.size() > shown.size() ? " 等" : "");
    }

    private static void addIfPresent(List<String> target, String value) {
        if (value != null && !value.isBlank()) target.add(value);
    }
}
