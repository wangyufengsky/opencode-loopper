package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.SourceArtifactMapper;
import io.opencode.loopper.runtime.ImmutableContentStore;
import io.opencode.loopper.service.workflow.WorkflowEncoding;
import io.opencode.loopper.workflow.WorkFileManifest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Bridges the existing SQLite document ledger into exact, scoped file deliveries. */
@Service
public final class SourceWorkDeliveries {
    private static final int LIMIT = 64 * 1024 * 1024;
    private final SourceTemplateAdmission admission;
    private final SourceArtifactMapper artifacts;
    private final WorkflowEncoding encoding;
    private final ImmutableContentStore content;
    public SourceWorkDeliveries(SourceTemplateAdmission admission, SourceArtifactMapper artifacts,
            WorkflowEncoding encoding, LoopperProperties properties) {
        this.admission = admission; this.artifacts = artifacts; this.encoding = encoding;
        content = new ImmutableContentStore(properties.getDataDir().resolve("work-files"));
    }

    /** The source publisher has already committed every expected byte identity to SQLite. */
    public void freeze(String runId) {
        if (!admission.require(runId).state().equals("REPORTING")) throw SourceTemplateAdmission.conflict();
        var rows = artifacts.all(runId);
        if (rows.isEmpty()) throw invalid();
        long total = 0;
        for (var row : rows) {
            byte[] bytes = bytes(row); total += bytes.length;
            if (total > LIMIT) throw invalid();
            write(row.runId(), row.sha256(), bytes);
        }
    }

    /** Metadata query excludes document bodies. Partial publication never releases a dependency. */
    public WorkFileManifest manifest(String projectId, String runId) {
        requireCompleted(projectId, runId);
        List<WorkFileManifest.Entry> files = artifacts.manifest(runId).stream()
                .map(row -> new WorkFileManifest.Entry(row.id(), row.name(), "text/markdown", row.sha256(), row.sizeBytes())).toList();
        if (files.isEmpty() || files.size() > 4096 || files.stream().mapToLong(WorkFileManifest.Entry::sizeBytes).sum() > LIMIT)
            throw invalid();
        for (var file : files) if (!file.name().matches("[A-Za-z0-9_-]+\\.md") || !file.sha256().matches("[0-9a-f]{64}")
                || file.sizeBytes() < 0 || file.sizeBytes() > LIMIT) throw invalid();
        String hash = WorkflowEncoding.hash(encoding.encode(Map.of("version", 1, "projectId", projectId,
                "producerType", "SOURCE_TEMPLATE_RUN", "producerId", runId, "files", files)));
        return new WorkFileManifest(1, projectId, "SOURCE_TEMPLATE_RUN", runId, hash, files);
    }

    public byte[] read(String projectId, String runId, String manifestSha256, String artifactId) {
        var manifest = manifest(projectId, runId);
        if (!manifest.sha256().equals(manifestSha256)) throw invalid();
        var file = manifest.files().stream().filter(item -> item.id().equals(artifactId)).findFirst()
                .orElseThrow(() -> new NotFoundException("交付清单中不存在该文件"));
        var row = artifacts.find(runId, artifactId).orElseThrow(SourceWorkDeliveries::invalid);
        byte[] bytes = bytes(row);
        if (!file.sha256().equals(row.sha256()) || file.sizeBytes() != bytes.length) throw invalid();
        // Historical runs have an authoritative SQLite body but no managed copy. Recreate identical bytes only.
        write(runId, row.sha256(), bytes);
        try { return content.read(runId, file.sha256(), file.sizeBytes(), LIMIT); }
        catch (ImmutableContentStore.StorageFailure failure) { throw invalid(); }
    }

    private void requireCompleted(String projectId, String runId) {
        var run = admission.require(runId);
        if (!run.projectId().equals(projectId)) throw new NotFoundException("当前项目中不存在该交付物");
        if (!run.state().equals("COMPLETED"))
            throw new ConflictException("WORK_DELIVERY_NOT_READY", "工作尚未完成，不能把部分文件作为后续节点输入");
    }
    private void write(String runId, String sha256, byte[] bytes) {
        try { content.write(runId, sha256, bytes, LIMIT); }
        catch (ImmutableContentStore.StorageFailure failure) { throw invalid(); }
    }
    private byte[] bytes(SourceArtifactMapper.Artifact row) {
        if (row.content() == null || !row.name().matches("[A-Za-z0-9_-]+\\.md")) throw invalid();
        byte[] bytes = row.content().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LIMIT || !ImmutableContentStore.hash(bytes).equals(row.sha256())) throw invalid();
        return bytes;
    }
    private static ConflictException invalid() {
        return new ConflictException("WORK_FILE_DELIVERY_INVALID", "文件交付物与冻结清单不一致，已保留现有文件，请检查后恢复");
    }
}
