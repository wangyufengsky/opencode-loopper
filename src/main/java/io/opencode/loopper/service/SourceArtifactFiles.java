package io.opencode.loopper.service;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.SourceTemplateParameters;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Preview, downloads and external files share the exact same immutable server bytes. */
@Service
public final class SourceArtifactFiles {
    private final SourceTemplateAdmission admission;
    private final SourceArtifactMapper artifacts;
    private final SourceSnapshotStorage storage;
    private final ObjectMapper json;
    public SourceArtifactFiles(SourceTemplateAdmission admission, SourceArtifactMapper artifacts,
            SourceSnapshotStorage storage, ObjectMapper json) {
        this.admission = admission; this.artifacts = artifacts; this.storage = storage; this.json = json;
    }
    public Path directory(String id) {
        var run = admission.require(id);
        var parameters = json.readValue(run.parametersJson(), SourceTemplateParameters.class);
        return TemplateDocumentPaths.bundleDirectory(parameters.documentPath(), "source-" + id, storage.directory(id));
    }
    public void materialize(String id) {
        if (!admission.require(id).state().equals("REPORTING")) throw SourceTemplateAdmission.conflict();
        Path root = directory(id);
        try {
            TemplateDocumentPaths.requireSafeDirectory(root); Files.createDirectories(root);
            for (var artifact : artifacts.all(id)) {
                verify(artifact);
                TemplateDocumentPaths.requireSafeDirectory(root);
                Path target = root.resolve(artifact.name());
                if (Files.isSymbolicLink(target)) throw invalid();
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                            || Files.size(target) != artifact.content().getBytes(StandardCharsets.UTF_8).length) throw changed();
                    try (var input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
                        if (!new String(input.readAllBytes(), StandardCharsets.UTF_8).equals(artifact.content())) throw changed();
                    }
                    continue;
                }
                Path pending = Files.createTempFile(root, ".source-", ".pending");
                try {
                    Files.writeString(pending, artifact.content(), StandardCharsets.UTF_8);
                    TemplateDocumentPaths.requireSafeDirectory(root);
                    try { Files.createLink(target, pending); }
                    catch (UnsupportedOperationException | FileSystemException unsupported) { Files.move(pending, target); }
                } finally { Files.deleteIfExists(pending); }
            }
        } catch (IOException unavailable) {
            throw new BadRequestException("SOURCE_ARTIFACT_WRITE_FAILED", "文档写入未完成，请检查目录权限和磁盘；恢复会继续写入相同制品");
        }
    }
    public CursorPage<SourceArtifactMapper.Metadata> list(String id, String after, int limit) {
        admission.require(id);
        int bounded = Math.max(1, Math.min(100, limit));
        var all = artifacts.list(id, after == null ? "" : after, bounded + 1);
        var page = all.stream().limit(bounded).toList();
        return new CursorPage<>(page, all.size() > bounded ? page.getLast().name() : null);
    }
    public SourceArtifactMapper.Artifact read(String id, String artifactId) {
        admission.require(id);
        var row = artifacts.find(id, artifactId).orElseThrow(() -> new NotFoundException("文档制品不存在"));
        verify(row); return row;
    }
    public byte[] bundle(String id) {
        admission.require(id);
        var rows = artifacts.all(id);
        if (rows.isEmpty()) throw new NotFoundException("完整文档包尚未生成");
        try (var bytes = new ByteArrayOutputStream(); var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var row : rows) {
                verify(row);
                var entry = new ZipEntry(row.name()); entry.setTime(0); zip.putNextEntry(entry);
                zip.write(row.content().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
            zip.finish(); return bytes.toByteArray();
        } catch (IOException failure) { throw invalid(); }
    }
    public SourceArtifactMapper.Artifact named(String id, String name) {
        admission.require(id);
        var row = artifacts.named(id, name).orElseThrow(() -> new NotFoundException("文档制品不存在"));
        verify(row); return row;
    }
    private static void verify(SourceArtifactMapper.Artifact row) {
        if (!row.name().matches("[A-Za-z0-9_-]+\\.md") || !DocumentModelStore.hash(row.content()).equals(row.sha256())) throw invalid();
    }
    private static ConflictException invalid() {
        return new ConflictException("SOURCE_ARTIFACT_INVALID", "文档制品路径或内容校验失败");
    }
    private static ConflictException changed() {
        return new ConflictException("SOURCE_ARTIFACT_FILE_CHANGED", "输出文件已被外部修改，请保留该文件；恢复不会覆盖已有内容");
    }
}
