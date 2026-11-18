package com.codementor.project;

import com.codementor.context.ProjectFilePath;
import com.codementor.privacy.OutboundRequestGuard;
import com.codementor.settings.CodeMentorProjectConsent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaRecursiveElementWalkingVisitor;
import com.intellij.psi.JavaResolveResult;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Live declarations of methods actually resolved from calls in the analysis target. */
public record ProjectCalls(List<String> signatures, List<SourceVersion> sources) {
    public static final ProjectCalls EMPTY = new ProjectCalls(List.of(), List.of());
    private static final int MAX_SIGNATURES = 5;
    private static final int MAX_SIGNATURE_CHARS = 240;
    private static final int MAX_CALLS_TO_RESOLVE = 48;

    public ProjectCalls {
        signatures = List.copyOf(signatures);
        sources = List.copyOf(sources);
    }

    /** Runs under the same committed PSI read action as target extraction. */
    public static ProjectCalls collect(Project project, PsiFile file, TextRange target) {
        if (!CodeMentorProjectConsent.getInstance(project).isProjectScanGranted()
                || DumbService.isDumb(project) || target.isEmpty()) return EMPTY;
        Set<String> signatures = new LinkedHashSet<>();
        Map<VirtualFile, SourceVersion> sources = new LinkedHashMap<>();
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        OutboundRequestGuard guard = OutboundRequestGuard.getInstance(project);
        try {
            file.accept(new JavaRecursiveElementWalkingVisitor() {
                private int inspectedCalls;

                @Override
                public void visitElement(@NotNull PsiElement element) {
                    ProgressManager.checkCanceled();
                    TextRange range = element.getTextRange();
                    if (!target.intersectsStrict(range)) return;
                    if (element instanceof PsiMethodCallExpression call && target.contains(range)) {
                        if (++inspectedCalls > MAX_CALLS_TO_RESOLVE) {
                            stopWalking();
                            return;
                        }
                        JavaResolveResult resolved = call.resolveMethodGenerics();
                        if (resolved.isValidResult() && resolved.getElement() instanceof PsiMethod method) {
                            PsiFile declaration = method.getContainingFile();
                            VirtualFile source = declaration == null ? null : declaration.getVirtualFile();
                            if (source != null && !source.equals(file.getVirtualFile())
                                    && source.getLength() <= ProjectScanner.MAX_FILE_BYTES
                                    && index.isInContent(source)
                                    && guard.evaluateFile(ProjectFilePath.resolve(project, declaration)).allowed()) {
                                String signature = signature(method);
                                if (!signature.isEmpty() && signatures.add(signature)) {
                                    sources.computeIfAbsent(source, SourceVersion::capture);
                                }
                            }
                        }
                        if (signatures.size() == MAX_SIGNATURES) {
                            stopWalking();
                            return;
                        }
                    }
                    super.visitElement(element);
                }
            });
        } catch (IndexNotReadyException ignored) {
            return EMPTY;
        }
        return new ProjectCalls(List.copyOf(signatures), List.copyOf(sources.values()));
    }

    private static String signature(PsiMethod method) {
        PsiClass owner = method.getContainingClass();
        PsiType returnType = method.getReturnType();
        if (owner == null || owner.getQualifiedName() == null || returnType == null) return "";
        List<String> parameters = new ArrayList<>();
        PsiParameter[] declaredParameters = method.getParameterList().getParameters();
        if (declaredParameters.length > ClassFacts.MAX_PARAMETERS) return "";
        for (PsiParameter parameter : declaredParameters) {
            parameters.add(parameter.getType().getPresentableText());
        }
        String signature = returnType.getPresentableText() + " " + owner.getQualifiedName()
                + "#" + method.getName() + "(" + String.join(", ", parameters) + ")";
        return signature.length() <= MAX_SIGNATURE_CHARS ? signature : "";
    }

    public boolean isCurrent() {
        return sources.stream().allMatch(SourceVersion::isCurrent);
    }

    public boolean contains(VirtualFile file) {
        return sources.stream().anyMatch(source -> source.file().equals(file));
    }

    /** Local-only snapshot used for expiry. Paths and stamps are never sent. */
    public record SourceVersion(VirtualFile file, String url, long fileStamp, Long documentStamp) {
        static SourceVersion capture(VirtualFile file) {
            Document document = FileDocumentManager.getInstance().getCachedDocument(file);
            return new SourceVersion(file, file.getUrl(), file.getModificationStamp(),
                    document == null ? null : document.getModificationStamp());
        }

        boolean isCurrent() {
            if (!file.isValid() || !url.equals(file.getUrl()) || file.getModificationStamp() != fileStamp) {
                return false;
            }
            FileDocumentManager manager = FileDocumentManager.getInstance();
            Document document = manager.getCachedDocument(file);
            if (document == null) return true;
            return documentStamp == null ? !manager.isDocumentUnsaved(document)
                    : document.getModificationStamp() == documentStamp;
        }
    }
}
