package com.codementor.context;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Resolves a source file without ever exposing an absolute local path. */
public final class ProjectFilePath {

    private ProjectFilePath() {}

    public static Result resolve(Project project, PsiFile psiFile) {
        if (psiFile == null) {
            return new Result("unknown.java", false);
        }
        VirtualFile virtualFile = psiFile.getVirtualFile();
        if (virtualFile == null || !virtualFile.isInLocalFileSystem()) {
            return new Result(safeName(psiFile.getName()), false);
        }
        VirtualFile projectDirectory = ProjectUtil.guessProjectDir(project);
        String basePath = projectDirectory == null
                ? null
                : projectDirectory.getCanonicalPath();
        String filePath = virtualFile.getCanonicalPath();
        if (basePath == null || basePath.isBlank()
                || filePath == null || filePath.isBlank()) {
            return new Result(safeName(virtualFile.getName()), false);
        }
        try {
            Path root = Path.of(basePath).toAbsolutePath().normalize();
            Path file = Path.of(filePath).toAbsolutePath().normalize();
            if (!file.startsWith(root) || file.equals(root)) {
                return new Result(safeName(virtualFile.getName()), false);
            }
            String relative = root.relativize(file).toString().replace('\\', '/');
            if (relative.isBlank() || relative.startsWith("../")) {
                return new Result(safeName(virtualFile.getName()), false);
            }
            return new Result(relative, true);
        } catch (InvalidPathException e) {
            return new Result(safeName(virtualFile.getName()), false);
        }
    }

    private static String safeName(String value) {
        if (value == null || value.isBlank()) return "unknown.java";
        return value.replace('/', '_').replace('\\', '_');
    }

    public record Result(String relativePath, boolean insideProject) {}
}
