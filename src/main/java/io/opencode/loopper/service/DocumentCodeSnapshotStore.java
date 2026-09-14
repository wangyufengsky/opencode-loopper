package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Snapshot intent precedes fetch; the complete file manifest and ready marker commit atomically. */
@Service
public class DocumentCodeSnapshotStore {
    private final DocumentTemplateMapper runs;
    private final DocumentCodeMapper files;
    private final ObjectMapper json;
    public DocumentCodeSnapshotStore(DocumentTemplateMapper runs, DocumentCodeMapper files, ObjectMapper json) {
        this.runs = runs; this.files = files; this.json = json;
    }
    @Transactional
    public Snapshot plan(DocumentTemplateRunRow run, String sha) {
        if (run.snapshotJson() != null) return json.readValue(run.snapshotJson(), Snapshot.class);
        var snapshot = new Snapshot(sha, null, false, 0);
        if (runs.bindSnapshot(run.id(), run.version(), json.writeValueAsString(snapshot), Instant.now().toString()) != 1)
            throw conflict();
        return snapshot;
    }
    @Transactional
    public Snapshot finish(DocumentTemplateRunRow run, Snapshot snapshot, List<DocumentCodeMapper.File> manifest) {
        var current = runs.find(run.id()).orElseThrow(DocumentCodeSnapshotStore::conflict);
        if (current.snapshotJson() == null) throw conflict();
        var frozen = json.readValue(current.snapshotJson(), Snapshot.class);
        if (!frozen.sha().equals(snapshot.sha())) throw conflict();
        if (frozen.ready()) return frozen;
        for (var file : manifest) if (files.insertFile(file) != 1) throw conflict();
        if (runs.finishSnapshot(current.id(), current.version(), current.snapshotJson(),
                json.writeValueAsString(snapshot), Instant.now().toString()) != 1) throw conflict();
        return snapshot;
    }
    public record Snapshot(String sha, String treeSha, boolean ready, int files) { }
    private static ConflictException conflict() { return new ConflictException("DOCUMENT_CODE_SNAPSHOT_CHANGED", "冻结代码快照发生变化"); }
}
