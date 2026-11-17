package com.codementor.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目画像：一次本机扫描得到的全部 {@link ClassFacts}，以及按简单名建立的查找索引。
 *
 * <p>不可变；重新扫描会生成新的实例整体替换旧画像。
 */
public final class ProjectProfile {

    /** 单个项目最多记住的类型数量，防止超大仓库拖慢 IDE 或占用过多磁盘。 */
    public static final int MAX_CLASSES = 3_000;

    private static final ProjectProfile EMPTY =
            new ProjectProfile(List.of(), 0, 0, false, 0L);

    private final List<ClassFacts> classes;
    private final int scannedFiles;
    private final int skippedFiles;
    private final boolean truncated;
    private final long scannedAtEpochMillis;
    private final Map<String, List<ClassFacts>> bySimpleName;

    public ProjectProfile(
            List<ClassFacts> classes,
            int scannedFiles,
            int skippedFiles,
            boolean truncated,
            long scannedAtEpochMillis) {
        List<ClassFacts> source = classes == null ? List.of() : classes;
        boolean overflow = source.size() > MAX_CLASSES;
        this.classes = List.copyOf(overflow ? source.subList(0, MAX_CLASSES) : source);
        this.scannedFiles = Math.max(0, scannedFiles);
        this.skippedFiles = Math.max(0, skippedFiles);
        this.truncated = truncated || overflow;
        this.scannedAtEpochMillis = Math.max(0L, scannedAtEpochMillis);

        Map<String, List<ClassFacts>> index = new LinkedHashMap<>();
        for (ClassFacts facts : this.classes) {
            if (facts.simpleName().isEmpty()) continue;
            index.computeIfAbsent(facts.simpleName(), key -> new ArrayList<>()).add(facts);
        }
        index.replaceAll((key, value) -> List.copyOf(value));
        this.bySimpleName = Map.copyOf(index);
    }

    public static ProjectProfile empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return classes.isEmpty();
    }

    public List<ClassFacts> classes() {
        return classes;
    }

    public int scannedFiles() {
        return scannedFiles;
    }

    public int skippedFiles() {
        return skippedFiles;
    }

    public boolean truncated() {
        return truncated;
    }

    public long scannedAtEpochMillis() {
        return scannedAtEpochMillis;
    }

    /** 同名类型可能有多个（不同包）；调用方负责消歧。 */
    public List<ClassFacts> findBySimpleName(String simpleName) {
        if (simpleName == null) return List.of();
        return bySimpleName.getOrDefault(simpleName, List.of());
    }

    public boolean containsSimpleName(String simpleName) {
        return simpleName != null && bySimpleName.containsKey(simpleName);
    }

    /** 直接继承或实现了给定简单名的项目类型，按扫描顺序返回。 */
    public List<ClassFacts> implementorsOf(String simpleName, int limit) {
        if (simpleName == null || simpleName.isEmpty() || limit < 1) return List.of();
        List<ClassFacts> result = new ArrayList<>();
        for (ClassFacts facts : classes) {
            if (facts.superTypes().contains(simpleName)) {
                result.add(facts);
                if (result.size() >= limit) break;
            }
        }
        return List.copyOf(result);
    }
}
