package com.codementor.context;

import com.codementor.review.ReviewContext;
import com.codementor.review.ReviewMode;
import com.codementor.javaweb.JavaWebContext;
import com.codementor.javaweb.JavaWebContextAnalyzer;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReferenceList;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Arrays;

/**
 * PSI 上下文采集器 —— "读懂你正在写的代码"的核心（方案第 7、8 节）。
 *
 * <p>职责：拿到当前编辑器状态，判断评审目标，组装 {@link ReviewContext}。
 *
 * <p>规则（方案第 5.1 节）：
 * <ol>
 *   <li>选中代码时 → 评审选中区域。</li>
 *   <li>未选中时 → 评审光标所在的整个方法。</li>
 * </ol>
 *
 * <p>线程模型：本类所有方法都依赖 PSI 读取，必须在读锁内执行。
 * 调用方（ReviewCoordinator）用 {@link com.intellij.openapi.application.ReadAction} 包裹。
 */
public final class PsiContextExtractor {

    private PsiContextExtractor() {}

    /**
     * 从当前编辑器采集评审上下文。返回 null 表示没有可评审的代码（如非 Java 文件）。
     */
    @Nullable
    public static ReviewContext extract(Project project, Editor editor, PsiFile psiFile) {
        return extract(project, editor, psiFile, null);
    }

    /**
     * @param preferredOffset 非 null 时忽略选区和光标，定位保存前最后实际编辑的方法。
     */
    @Nullable
    public static ReviewContext extract(
            Project project, Editor editor, PsiFile psiFile, Integer preferredOffset) {
        return extract(project, ContextTarget.capture(editor, preferredOffset), psiFile);
    }

    /**
     * Extracts from an immutable target snapshot. Coordinators should prefer this overload so a
     * later caret/selection movement cannot silently change an already queued request.
     */
    @Nullable
    public static ReviewContext extract(
            Project project, ContextTarget target, PsiFile psiFile) {
        if (psiFile == null || !(psiFile instanceof PsiJavaFile)) {
            return null;
        }

        Document document = target.document();
        ProjectFilePath.Result projectFile = ProjectFilePath.resolve(project, psiFile);

        // 优先看是否有选中文本
        boolean hasSelection = target.hasSelection()
                && target.selectionStart() >= 0
                && target.selectionEnd() <= document.getTextLength();
        String selectedText = hasSelection
                ? document.getText(new TextRange(target.selectionStart(), target.selectionEnd()))
                : "";
        hasSelection = !selectedText.isBlank();

        String targetName;
        String targetIdentity;
        String code;
        PsiClass containingClass;
        PsiMethod targetMethod = null;
        PsiElement referenceScope;
        int analysisStart;
        int analysisEnd;
        int methodStart = -1;
        int methodEnd = -1;
        if (hasSelection) {
            targetName = "选中代码";
            targetIdentity = "";
            code = selectedText;
            int selectionStart = target.selectionStart();
            containingClass = PsiTreeUtil.getParentOfType(
                    psiFile.findElementAt(selectionStart), PsiClass.class, false);
            referenceScope = containingClass;
            analysisStart = selectionStart;
            analysisEnd = target.selectionEnd();
            // 选中模式：methodStart/End 保持 -1，过期检测时"任何改动都视为可能过期"
        } else {
            // 没选中 → 取光标所在的 PsiMethod
            int offset = target.effectiveOffset();
            PsiMethod method = findMethodAt(psiFile, offset);
            if (method == null) {
                return null; // 光标不在方法内；由 UI 引导用户选中代码或移动光标
            }
            containingClass = method.getContainingClass();
            targetMethod = method;
            referenceScope = method;
            String className = containingClass != null ? containingClass.getName() : "?";
            String parameterTypes = String.join(",", Arrays.stream(
                            method.getParameterList().getParameters())
                    .map(parameter -> parameter.getType().getCanonicalText())
                    .toList());
            targetName = className + "#" + method.getName()
                    + "(" + parameterTypes + ")";
            targetIdentity = methodIdentity(containingClass, method);
            code = method.getText();
            if (code == null || code.isBlank()) {
                return null;
            }
            // 记录方法范围，供过期检测使用（只有改到这个方法内才算过期）
            methodStart = method.getTextRange().getStartOffset();
            methodEnd = method.getTextRange().getEndOffset();
            analysisStart = methodStart;
            analysisEnd = methodEnd;
        }

        long stamp = document.getModificationStamp();
        String fingerprint = fingerprint(code);
        List<String> technologyTags = TechnologyTagDetector.detect(
                (PsiJavaFile) psiFile, containingClass, targetMethod);
        List<String> diagnostics = IdeaDiagnosticCollector.collect(
                project, document, analysisStart, analysisEnd);
        List<String> referencedFields = collectReferencedFields(
                containingClass, referenceScope, analysisStart, analysisEnd);
        ModelContextBuilder.Result boundedContext = buildModelContext(
                (PsiJavaFile) psiFile,
                containingClass,
                referencedFields,
                targetName,
                code,
                diagnostics,
                technologyTags);
        JavaWebContext javaWebContext = JavaWebContextAnalyzer.analyze(
                (PsiJavaFile) psiFile, containingClass, targetMethod);

        return new ReviewContext(
                projectFile.relativePath(), targetName, code,
                boundedContext.text(), boundedContext.truncated(),
                stamp, fingerprint,
                methodStart, methodEnd,
                javaWebContext, "",
                ReviewMode.REVIEW,
                technologyTags,
                diagnostics,
                projectFile.insideProject())
                .withTargetIdentity(targetIdentity);
    }

    private static String methodIdentity(PsiClass containingClass, PsiMethod method) {
        if (containingClass == null || method == null) return "";
        String owner = containingClass.getQualifiedName();
        if (owner == null || owner.isBlank()) owner = containingClass.getName();
        if (owner == null || owner.isBlank()) owner = "?";
        PsiMethod[] methods = containingClass.getMethods();
        for (int index = 0; index < methods.length; index++) {
            if (methods[index].isEquivalentTo(method)) {
                return owner + "#method-index-" + index;
            }
        }
        return owner + "#method-offset-" + method.getTextOffset();
    }

    private static PsiMethod findMethodAt(PsiFile psiFile, int requestedOffset) {
        int textLength = psiFile.getTextLength();
        if (textLength == 0) return null;
        int offset = Math.max(0, Math.min(requestedOffset, textLength - 1));
        PsiMethod method = PsiTreeUtil.getParentOfType(
                psiFile.findElementAt(offset), PsiMethod.class, false);
        if (method == null && offset > 0) {
            method = PsiTreeUtil.getParentOfType(
                    psiFile.findElementAt(offset - 1), PsiMethod.class, false);
        }
        return method;
    }

    /**
     * SHA-256 指纹截取前 16 字节；比 String.hashCode 更适合做一致性保护。
     */
    private static String fingerprint(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                value.append(String.format("%02x", digest[i]));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }

    private static ModelContextBuilder.Result buildModelContext(
            PsiJavaFile javaFile,
            PsiClass containingClass,
            List<String> referencedFields,
            String targetName,
            String targetCode,
            List<String> diagnostics,
            List<String> technologyTags) {
        String packageDeclaration = javaFile.getPackageName().isBlank()
                ? ""
                : "package " + javaFile.getPackageName() + ";";
        String imports = javaFile.getImportList() == null
                ? ""
                : javaFile.getImportList().getText();
        return ModelContextBuilder.build(new ModelContextBuilder.Input(
                packageDeclaration,
                imports,
                containingClass == null ? "" : classDeclaration(containingClass),
                referencedFields,
                targetName,
                targetCode,
                diagnostics,
                technologyTags));
    }

    private static List<String> collectReferencedFields(
            PsiClass containingClass,
            PsiElement referenceScope,
            int analysisStart,
            int analysisEnd) {
        if (containingClass == null || referenceScope == null) {
            return List.of();
        }
        Set<PsiField> referenced = new LinkedHashSet<>();
        for (PsiReferenceExpression expression :
                PsiTreeUtil.findChildrenOfType(
                        referenceScope, PsiReferenceExpression.class)) {
            ProgressManager.checkCanceled();
            int offset = expression.getTextRange().getStartOffset();
            if (offset < analysisStart || offset >= analysisEnd) continue;
            try {
                PsiElement resolved = expression.resolve();
                if (resolved instanceof PsiField field
                        && field.getContainingClass() == containingClass) {
                    referenced.add(field);
                }
            } catch (ProcessCanceledException e) {
                throw e;
            } catch (RuntimeException ignored) {
                // Dumb mode 或索引刷新时无法解析某个引用；其余上下文仍可使用。
            }
        }
        return Arrays.stream(containingClass.getFields())
                .filter(referenced::contains)
                .map(PsiField::getText)
                .toList();
    }

    private static String classDeclaration(PsiClass psiClass) {
        StringBuilder declaration = new StringBuilder();
        PsiModifierList modifiers = psiClass.getModifierList();
        if (modifiers != null && !modifiers.getText().isBlank()) {
            declaration.append(modifiers.getText()).append(" ");
        }
        declaration.append(psiClass.isInterface() ? "interface " : "class ")
                .append(psiClass.getName() == null ? "?" : psiClass.getName());
        appendReferenceList(declaration, psiClass.getExtendsList());
        appendReferenceList(declaration, psiClass.getImplementsList());
        return declaration.toString();
    }

    private static void appendReferenceList(StringBuilder declaration, PsiReferenceList list) {
        if (list != null && !list.getText().isBlank()) {
            declaration.append(" ").append(list.getText());
        }
    }

}
