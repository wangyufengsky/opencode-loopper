package io.opencode.loopper.service;

import io.opencode.loopper.domain.DocumentTemplateState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.persistence.DocumentTemplateMapper;
import io.opencode.loopper.persistence.DocumentTemplateRunRow;
import java.util.List;
import org.springframework.stereotype.Service;

/** Finishes recoverable input persistence before any model may read or design from it. */
@Service
public final class DocumentTemplatePreparation {
    private final DocumentTemplateMapper mapper;
    private final DocumentTemplateAdmission admission;
    private final DocumentTemplateStorage storage;
    private final DocumentCodeSnapshotService snapshots;
    private final DocumentSourceSnapshots sources;
    public DocumentTemplatePreparation(DocumentTemplateMapper mapper, DocumentTemplateAdmission admission,
            DocumentTemplateStorage storage, DocumentCodeSnapshotService snapshots, DocumentSourceSnapshots sources) {
        this.mapper = mapper; this.admission = admission; this.storage = storage; this.snapshots = snapshots; this.sources = sources;
    }
    public DocumentTemplateRunRow finish(DocumentTemplateRunRow row, List<DocumentTemplateStorage.Prepared> prepared) {
        if (!row.state().equals("PREPARING") && !(row.state().equals("WAITING_INPUT") && "PREPARING".equals(row.resumeState()))) return row;
        var stored = mapper.files(row.id());
        if (stored.size() != prepared.size()) throw new ConflictException("DOCUMENT_TEMPLATE_UPLOAD_INCOMPLETE", "文档清单不完整，请保留文件并检查任务记录");
        for (var file : stored) storage.save(file.relativePath(), prepared.get(file.ordinal()).bytes(), file.sha256());
        mapper.markUploadReady(row.id(), java.time.Instant.now().toString());
        return recover(row.id());
    }
    public DocumentTemplateRunRow recover(String id) {
        var row = admission.require(id);
        if (!row.state().equals("PREPARING")) return row;
        if (!mapper.uploadReady(id)) return row;
        if (mapper.supplementalUploadPending(id)) return row;
        for (var file : mapper.files(id)) storage.read(file.relativePath(), file.sha256());
        if (row.templateId().equals("REQUIREMENT_CODE_REVIEW")) snapshots.freeze(row);
        if (row.directDocuments()) {
            sources.freeze(id);
            boolean review = row.templateId().equals("REQUIREMENT_CODE_REVIEW");
            return admission.transition(admission.require(id), review ? DocumentTemplateState.ASSESSING : DocumentTemplateState.DESIGNING,
                    review ? LifecycleEvent.ASSESS_REQUIREMENT_CODE : LifecycleEvent.DESIGN_DOCUMENT_REQUIREMENTS, null, null);
        }
        return admission.transition(admission.require(id), DocumentTemplateState.ANALYZING,
                LifecycleEvent.ANALYZE_DOCUMENT_REQUIREMENTS, null, null);
    }
}
