package com.codementor.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 项目画像的本机持久化：每个项目一个 JSON 文件，位于 IDE 系统目录下，
 * 不写入项目目录，因此不会被提交到版本库或被同步工具带走。
 *
 * <p>文件名是项目标识的 SHA-256 摘要，不暴露项目路径。
 */
public final class ProjectProfileStore {

    private final Path directory;

    public ProjectProfileStore(Path directory) {
        this.directory = directory;
    }

    /** 没有可用画像（不存在、过大、版本不符或损坏）时返回 null。 */
    public synchronized ProjectProfile load(String projectKey) throws IOException {
        Path file = fileFor(projectKey);
        if (!Files.isRegularFile(file)
                || Files.size(file) > ProjectProfileCodec.MAX_JSON_CHARS) {
            return null;
        }
        return ProjectProfileCodec.fromJson(Files.readString(file, StandardCharsets.UTF_8));
    }

    public synchronized void save(String projectKey, ProjectProfile profile) throws IOException {
        String json = ProjectProfileCodec.toJson(profile);
        if (json.length() > ProjectProfileCodec.MAX_JSON_CHARS) {
            // 写了也读不回来（load 会拒绝过大的文件）；不保存，并清掉可能过期的旧文件。
            delete(projectKey);
            return;
        }
        Files.createDirectories(directory);
        Path file = fileFor(projectKey);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, json, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException replacementFailure) {
                replacementFailure.addSuppressed(atomicFailure);
                Files.deleteIfExists(temp);
                throw replacementFailure;
            }
        }
    }

    public synchronized void delete(String projectKey) throws IOException {
        Path file = fileFor(projectKey);
        Files.deleteIfExists(file);
        Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".tmp"));
    }

    Path fileFor(String projectKey) {
        return directory.resolve(digest(projectKey == null ? "" : projectKey) + ".json");
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}
