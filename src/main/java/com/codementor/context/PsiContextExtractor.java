package com.codementor.context;

import com.codementor.review.ReviewContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nullable;

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
 * 调用方（ReviewAction）用 {@link com.intellij.openapi.application.ReadAction} 包裹。
 *
 * <p>阶段 0 只采集：文件路径、目标名、代码文本、文档 stamp、指纹。
 * 阶段 1 会补充 imports、类签名、字段、IDEA 诊断摘要（方案第 8 节）。
 */
public final class PsiContextExtractor {

    private PsiContextExtractor() {}

    /**
     * 从当前编辑器采集评审上下文。返回 null 表示没有可评审的代码（如非 Java 文件）。
     */
    @Nullable
    public static ReviewContext extract(Project project, Editor editor, PsiFile psiFile) {
        if (psiFile == null || !(psiFile instanceof PsiJavaFile)) {
            return null;
        }

        Document document = editor.getDocument();
        String filePath = resolveRelativePath(psiFile);

        // 优先看是否有选中文本
        String selectedText = editor.getSelectionModel().getSelectedText();
        boolean hasSelection = selectedText != null && !selectedText.isBlank();

        String targetName;
        String code;
        int methodStart = -1;
        int methodEnd = -1;
        if (hasSelection) {
            targetName = "选中代码";
            code = selectedText;
            // 选中模式：methodStart/End 保持 -1，过期检测时"任何改动都视为可能过期"
        } else {
            // 没选中 → 取光标所在的 PsiMethod
            int offset = editor.getCaretModel().getOffset();
            PsiMethod method = PsiTreeUtil.getParentOfType(
                    psiFile.findElementAt(offset), PsiMethod.class, false);
            if (method == null) {
                return null; // 光标不在任何方法内（比如在类体外部），阶段 0 暂不处理
            }
            PsiClass containingClass = method.getContainingClass();
            String className = containingClass != null ? containingClass.getName() : "?";
            String methodName = method.getName();
            targetName = className + "#" + methodName;
            code = method.getText();
            if (code == null || code.isBlank()) {
                return null;
            }
            // 记录方法范围，供过期检测使用（只有改到这个方法内才算过期）
            methodStart = method.getTextRange().getStartOffset();
            methodEnd = method.getTextRange().getEndOffset();
        }

        long stamp = document.getModificationStamp();
        String fingerprint = fingerprint(code);

        return new ReviewContext(filePath, targetName, code, stamp, fingerprint,
                methodStart, methodEnd);
    }

    /** 取相对项目根的文件路径，没有则取文件名。 */
    private static String resolveRelativePath(PsiFile psiFile) {
        VirtualFile vFile = psiFile.getVirtualFile();
        if (vFile == null) return psiFile.getName();
        return vFile.getPath();
    }

    /**
     * 代码指纹。阶段 0 用 String.hashCode 的十六进制，够用且快。
     * 阶段 1 会换 SHA-256 截断，避免 hashCode 碰撞导致过期误判。
     */
    private static String fingerprint(String code) {
        return Integer.toHexString(code.hashCode());
    }
}
