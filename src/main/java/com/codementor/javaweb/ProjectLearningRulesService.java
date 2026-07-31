package com.codementor.javaweb;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.CompletableFuture;

/** 后台读取项目根目录下可选的 .codementor/rules.md，并按修改时间缓存。 */
@Service(Service.Level.PROJECT)
public final class ProjectLearningRulesService {

    private static final Logger LOG = Logger.getInstance(ProjectLearningRulesService.class);
    private static final int MAX_RULE_CHARS = 8_000;

    private final Project project;
    private FileTime cachedModifiedTime;
    private String cachedRules = "";

    public ProjectLearningRulesService(Project project) {
        this.project = project;
    }

    public static ProjectLearningRulesService getInstance(Project project) {
        return project.getService(ProjectLearningRulesService.class);
    }

    public CompletableFuture<String> loadRules() {
        return CompletableFuture.supplyAsync(
                this::loadRulesSafely,
                AppExecutorUtil.getAppExecutorService());
    }

    private synchronized String loadRulesSafely() {
        String basePath = project.getBasePath();
        if (basePath == null) return "";
        Path projectRoot = Path.of(basePath).toAbsolutePath().normalize();
        Path rulesFile = projectRoot.resolve(".codementor").resolve("rules.md").normalize();
        if (!rulesFile.startsWith(projectRoot) || !Files.isRegularFile(rulesFile)) {
            cachedModifiedTime = null;
            cachedRules = "";
            return "";
        }
        try {
            Path realProjectRoot = projectRoot.toRealPath();
            Path realRulesFile = rulesFile.toRealPath();
            if (!realRulesFile.startsWith(realProjectRoot)) {
                LOG.warn("忽略指向项目目录外部的 .codementor/rules.md");
                cachedModifiedTime = null;
                cachedRules = "";
                return "";
            }
            FileTime modifiedTime = Files.getLastModifiedTime(realRulesFile);
            if (modifiedTime.equals(cachedModifiedTime)) {
                return cachedRules;
            }
            cachedRules = readBounded(realRulesFile);
            cachedModifiedTime = modifiedTime;
            return cachedRules;
        } catch (IOException e) {
            LOG.warn("读取 .codementor/rules.md 失败", e);
            return "";
        }
    }

    private static String readBounded(Path rulesFile) throws IOException {
        char[] buffer = new char[MAX_RULE_CHARS + 1];
        int total = 0;
        try (Reader reader = Files.newBufferedReader(rulesFile, StandardCharsets.UTF_8)) {
            while (total < buffer.length) {
                int count = reader.read(buffer, total, buffer.length - total);
                if (count < 0) break;
                total += count;
            }
        }
        boolean truncated = total > MAX_RULE_CHARS;
        String content = new String(buffer, 0, Math.min(total, MAX_RULE_CHARS)).trim();
        return truncated ? content + "\n…（项目约定已截断）" : content;
    }
}
