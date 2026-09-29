package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import java.io.IOException;
import java.nio.file.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Fetches exactly one frozen commit into a bare repository; no checkout, log, build or project script. */
@Service
public class DocumentCodeSnapshotService {
    private final DocumentTemplateStorage storage;
    private final ProjectService projects;
    private final DocumentCodeSnapshotStore store;
    private final GitCommitSnapshots snapshots;
    private final ObjectMapper json;
    public DocumentCodeSnapshotService(DocumentTemplateStorage storage, ProjectService projects,
            DocumentCodeSnapshotStore store, GitCommitSnapshots snapshots, ObjectMapper json) {
        this.storage = storage; this.projects = projects; this.store = store; this.snapshots = snapshots; this.json = json;
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DocumentCodeSnapshotStore.Snapshot freeze(DocumentTemplateRunRow run) {
        var source = Path.of(projects.get(run.projectId()).rootPath());
        var branch = json.readValue(run.branchJson(), ProjectBranchService.Branch.class);
        var existing = run.snapshotJson() == null ? null : json.readValue(run.snapshotJson(), DocumentCodeSnapshotStore.Snapshot.class);
        if (existing != null && existing.ready()) return existing;
        var selected = snapshots.source(source, branch);
        String sha = existing == null ? snapshots.resolve(selected) : existing.sha();
        var intent = existing == null ? store.plan(run, sha) : existing;
        var captured = snapshots.capture(selected, repository(run.id()), intent.sha());
        var manifest = captured.files().stream().map(file -> new DocumentCodeMapper.File(run.id(),
                file.path(), file.blobSha(), file.mode(), file.sizeBytes(), file.limitation())).toList();
        return store.finish(run, new DocumentCodeSnapshotStore.Snapshot(captured.commitSha(), captured.treeSha(), true, manifest.size()), manifest);
    }
    public Path repository(String runId) {
        Path parent = storage.runtimeDirectory(runId), repository = parent.resolve("code.git");
        if (Files.isSymbolicLink(repository)) throw failure("DOCUMENT_CODE_PATH_INVALID", "冻结代码目录不能是符号链接");
        try {
            if (Files.exists(repository) && !repository.toRealPath().startsWith(parent)) throw new IOException("containment");
        } catch (IOException invalid) { throw failure("DOCUMENT_CODE_PATH_INVALID", "冻结代码目录不可用"); }
        return repository;
    }
    private static BadRequestException failure(String code, String message) { return new BadRequestException(code, message); }
}
