package com.codementor.context;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 从 imports 和声明注解识别少量稳定的 Java / JavaWeb 技术标签。 */
public final class TechnologyTagDetector {

    private TechnologyTagDetector() {}

    public static List<String> detect(
            PsiJavaFile javaFile, PsiClass containingClass, PsiMethod targetMethod) {
        StringBuilder evidence = new StringBuilder();
        if (javaFile.getImportList() != null) {
            evidence.append(javaFile.getImportList().getText()).append('\n');
        }
        appendModifiers(evidence, containingClass == null
                ? null
                : containingClass.getModifierList());
        appendModifiers(evidence, targetMethod == null
                ? null
                : targetMethod.getModifierList());
        return detect(evidence.toString());
    }

    /** 纯文本入口只接收 imports / 注解摘要，便于无 IDE fixture 的单元测试。 */
    public static List<String> detect(String declarationEvidence) {
        String text = declarationEvidence == null
                ? ""
                : declarationEvidence.toLowerCase(Locale.ROOT);
        List<String> tags = new ArrayList<>();
        addIf(tags, text, "Spring MVC",
                "springframework.web", "@restcontroller", "@controller",
                "@requestmapping", "@getmapping", "@postmapping");
        addIf(tags, text, "Bean Validation",
                "jakarta.validation", "javax.validation", "@valid", "@validated");
        addIf(tags, text, "Spring Transaction",
                "springframework.transaction", "@transactional");
        addIf(tags, text, "JPA",
                "jakarta.persistence", "javax.persistence", "jparepository");
        addIf(tags, text, "MyBatis",
                "org.apache.ibatis", "mybatis", "@mapper");
        addIf(tags, text, "JUnit",
                "org.junit", "@test", "@parameterizedtest");
        addIf(tags, text, "Java IO",
                "java.io.", "java.nio.file");
        addIf(tags, text, "Java Concurrency",
                "java.util.concurrent", "synchronized", "volatile");
        return List.copyOf(tags);
    }

    private static void appendModifiers(
            StringBuilder evidence, PsiModifierList modifiers) {
        if (modifiers != null) {
            evidence.append(modifiers.getText()).append('\n');
        }
    }

    private static void addIf(
            List<String> tags, String text, String label, String... markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                tags.add(label);
                return;
            }
        }
    }
}
