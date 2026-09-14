package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Freezes parsed source identity without invoking a model or creating semantic requirements. */
@Service
public class DocumentSourceSnapshots {
    private final DocumentTemplateMapper documents;
    private final ObjectMapper json;
    public DocumentSourceSnapshots(DocumentTemplateMapper documents, ObjectMapper json) {
        this.documents = documents; this.json = json;
    }
    @Transactional
    public DocumentTemplateRunRow freeze(String id) {
        var run = documents.find(id).orElseThrow();
        if (!run.directDocuments() || !documents.uploadReady(id) || documents.supplementalUploadPending(id)) throw changed();
        var files = documents.files(id);
        if (files.isEmpty()) throw changed();
        String content = json.writeValueAsString(Map.of("files", files.stream().map(file -> Map.of(
                "id", file.id(), "sha256", file.sha256(), "representationSha256", file.representationSha256(),
                "parserVersion", file.parserVersion(), "sections", file.sectionCount())).toList()));
        String hash = DocumentModelStore.hash(content);
        if (run.sourceRevision() > 0 && documents.basis(id, run.sourceRevision()).orElseThrow().manifestSha256().equals(hash)) return run;
        int next = run.sourceRevision() + 1; String now = Instant.now().toString();
        if (documents.insertBasis(new DocumentSourceMapper.Basis(id, next, "DOCUMENT_SOURCE", hash, content, now)) != 1
                || documents.bindSource(id, run.version(), run.sourceRevision(), next, now) != 1) throw changed();
        return documents.find(id).orElseThrow();
    }
    private static ConflictException changed() {
        return new ConflictException("DOCUMENT_SOURCE_NOT_READY", "原文尚未完整保存或版本已变化，请恢复本次上传");
    }
}
