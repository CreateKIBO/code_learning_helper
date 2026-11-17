package com.codementor.project;

import com.codementor.context.ProjectFilePath;
import com.codementor.javaweb.JavaWebContextAnalyzer;
import com.codementor.javaweb.JavaWebRole;
import com.codementor.privacy.FileExclusionPolicy;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 读取项目内全部 Java 源文件的声明，生成 {@link ProjectProfile}。
 *
 * <p>约束：
 * <ul>
 *   <li>只在用户授予“读取整个项目”之后由 {@link ProjectKnowledgeService} 调用。</li>
 *   <li>只遍历项目内容根（已排除 build/target 等目录），并在读取 PSI 前后各应用一次
 *       {@link FileExclusionPolicy}，命中排除规则或位于项目目录外的文件不读取。</li>
 *   <li>只取顶层类型的声明信息，不读取方法体。</li>
 *   <li>必须在读锁内调用；内部定期 {@code checkCanceled}，可被写操作打断后重跑。</li>
 * </ul>
 */
public final class ProjectScanner {

    private static final Logger LOG = Logger.getInstance(ProjectScanner.class);

    /** 单次扫描最多读取的 Java 文件数；学习型项目远小于此值。 */
    static final int MAX_FILES = 2_000;
    /** 超过此大小的单个文件（多为生成代码）跳过。 */
    static final long MAX_FILE_BYTES = 300_000L;

    private ProjectScanner() {}

    public static ProjectProfile scan(Project project, FileExclusionPolicy policy) {
        VirtualFile projectDirectory = ProjectUtil.guessProjectDir(project);
        if (projectDirectory == null) {
            return ProjectProfile.empty();
        }
        ProjectFileIndex fileIndex = ProjectRootManager.getInstance(project).getFileIndex();
        List<VirtualFile> candidates = new ArrayList<>();
        int[] skipped = {0};
        boolean[] truncated = {false};
        fileIndex.iterateContent(file -> {
            ProgressManager.checkCanceled();
            if (file.isDirectory() || !"java".equalsIgnoreCase(file.getExtension())) {
                return true;
            }
            String relativePath = VfsUtilCore.getRelativePath(file, projectDirectory, '/');
            if (relativePath == null
                    || policy.evaluate(relativePath).excluded()
                    || file.getLength() > MAX_FILE_BYTES) {
                skipped[0]++;
                return true;
            }
            if (candidates.size() >= MAX_FILES) {
                truncated[0] = true;
                return false;
            }
            candidates.add(file);
            return true;
        });

        PsiManager psiManager = PsiManager.getInstance(project);
        List<ClassFacts> classes = new ArrayList<>();
        int scanned = 0;
        for (VirtualFile file : candidates) {
            ProgressManager.checkCanceled();
            if (!file.isValid()) continue;
            PsiFile psiFile = psiManager.findFile(file);
            if (!(psiFile instanceof PsiJavaFile javaFile)) continue;
            // 与单文件请求使用同一套路径解析：规范路径在项目目录外（如符号链接）的文件不读取。
            ProjectFilePath.Result path = ProjectFilePath.resolve(project, psiFile);
            if (!path.insideProject() || policy.evaluate(path.relativePath()).excluded()) {
                skipped[0]++;
                continue;
            }
            scanned++;
            boolean testSource = fileIndex.isInTestSourceContent(file);
            try {
                for (PsiClass psiClass : javaFile.getClasses()) {
                    ProgressManager.checkCanceled();
                    ClassFacts facts = factsOf(
                            javaFile.getPackageName(), path.relativePath(), testSource, psiClass);
                    if (facts != null) classes.add(facts);
                }
            } catch (ProcessCanceledException e) {
                throw e;
            } catch (RuntimeException e) {
                // 单个文件解析异常不影响其余文件；不记录路径和源码内容。
                LOG.warn("项目画像：跳过一个无法解析的 Java 文件");
                skipped[0]++;
            }
            if (classes.size() >= ProjectProfile.MAX_CLASSES) {
                truncated[0] = true;
                break;
            }
        }
        return new ProjectProfile(
                classes, scanned, skipped[0], truncated[0], System.currentTimeMillis());
    }

    private static ClassFacts factsOf(
            String packageName, String relativePath, boolean testSource, PsiClass psiClass) {
        String name = psiClass.getName();
        if (name == null || name.isBlank()) return null;

        List<String> annotations = annotationNames(psiClass.getModifierList());
        List<String> superTypes = new ArrayList<>();
        addTypeNames(superTypes, psiClass.getExtendsListTypes());
        addTypeNames(superTypes, psiClass.getImplementsListTypes());

        List<ClassFacts.FieldFact> fields = new ArrayList<>();
        for (PsiField field : psiClass.getFields()) {
            if (fields.size() >= ClassFacts.MAX_FIELDS) break;
            fields.add(new ClassFacts.FieldFact(
                    presentable(field.getType()),
                    field.getName(),
                    field.hasModifierProperty(PsiModifier.FINAL),
                    field.hasModifierProperty(PsiModifier.STATIC),
                    annotationNames(field.getModifierList())));
        }

        List<String> constructorTypes = List.of();
        for (PsiMethod constructor : psiClass.getConstructors()) {
            List<String> types = parameterTypes(constructor, true);
            if (types.size() > constructorTypes.size()) constructorTypes = types;
        }

        List<ClassFacts.MethodFact> methods = new ArrayList<>();
        for (PsiMethod method : psiClass.getMethods()) {
            if (methods.size() >= ClassFacts.MAX_METHODS) break;
            if (method.isConstructor()) continue;
            methods.add(new ClassFacts.MethodFact(
                    method.getName(),
                    presentable(method.getReturnType()),
                    parameterTypes(method, false),
                    annotationNames(method.getModifierList()),
                    method.hasModifierProperty(PsiModifier.PUBLIC)));
        }

        JavaWebRole role = JavaWebContextAnalyzer
                .classify(name, new LinkedHashSet<>(annotations), Set.of())
                .getRole();
        return new ClassFacts(
                relativePath,
                packageName,
                name,
                kindOf(psiClass),
                role,
                testSource,
                annotations,
                superTypes,
                fields,
                constructorTypes,
                methods);
    }

    private static ClassFacts.Kind kindOf(PsiClass psiClass) {
        if (psiClass.isAnnotationType()) return ClassFacts.Kind.ANNOTATION;
        if (psiClass.isEnum()) return ClassFacts.Kind.ENUM;
        if (psiClass.isRecord()) return ClassFacts.Kind.RECORD;
        if (psiClass.isInterface()) return ClassFacts.Kind.INTERFACE;
        return ClassFacts.Kind.CLASS;
    }

    /** 注解只取引用名，不做解析，避免为每个注解查询索引。 */
    private static List<String> annotationNames(PsiModifierList modifiers) {
        if (modifiers == null) return List.of();
        List<String> names = new ArrayList<>();
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            if (names.size() >= ClassFacts.MAX_ANNOTATIONS) break;
            PsiJavaCodeReferenceElement reference = annotation.getNameReferenceElement();
            String name = reference == null ? null : reference.getReferenceName();
            if (name != null && !name.isBlank()) names.add(name);
        }
        return names;
    }

    private static void addTypeNames(List<String> target, PsiClassType[] types) {
        for (PsiClassType type : types) {
            if (target.size() >= ClassFacts.MAX_SUPER_TYPES) return;
            String name = type.getClassName();
            if (name != null && !name.isBlank()) target.add(name);
        }
    }

    private static List<String> parameterTypes(PsiMethod method, boolean outerNameOnly) {
        List<String> types = new ArrayList<>();
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            if (types.size() >= ClassFacts.MAX_PARAMETERS) break;
            String type = presentable(parameter.getType());
            types.add(outerNameOnly ? ClassFacts.outerSimpleName(type) : type);
        }
        return types;
    }

    private static String presentable(PsiType type) {
        return type == null ? "" : type.getPresentableText();
    }
}
