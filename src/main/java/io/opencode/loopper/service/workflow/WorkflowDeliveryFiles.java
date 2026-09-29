package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.persistence.WorkflowExecutionRows.Attempt;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.workflow.*;
import org.springframework.stereotype.Component;

/** Program-owned file artifacts must exist and match their immutable manifest before acceptance. */
@Component
public final class WorkflowDeliveryFiles {
    private final WorkflowCodeMapper code;
    private final WorkflowSourceMapper sources;
    private final WorkflowDocumentMapper documents;
    private final WorkflowEncoding encoding;
    private final WorkflowRepositoryMapper repositories;
    private final WorkflowHistoryMapper histories;
    private final WorkflowReviewMapper reviews;
    public WorkflowDeliveryFiles(WorkflowCodeMapper code, WorkflowSourceMapper sources,
            WorkflowDocumentMapper documents, WorkflowEncoding encoding,WorkflowRepositoryMapper repositories,WorkflowHistoryMapper histories,WorkflowReviewMapper reviews) {
        this.code=code; this.sources=sources; this.documents=documents; this.encoding=encoding;this.repositories=repositories;this.histories=histories;this.reviews=reviews;
    }
    public void require(Attempt attempt, WorkflowDelivery.Value value) {
        if (value.kind() == WorkflowGraph.DataKind.CODE) requireCode(attempt, value);
        if (value.kind() == WorkflowGraph.DataKind.DOCUMENT) {
            if (java.util.Set.of(WorkflowDocument.ADAPTER,WorkflowDocument.ASSESSMENT_ADAPTER,WorkflowHistoryReport.ADAPTER,WorkflowSnapshotReport.ADAPTER).contains(attempt.adapterKey())) requireDocument(attempt, value);
            else if(attempt.adapterKey().equals(WorkflowCommandVerification.ADAPTER)){
                if(WorkflowReviewSource.TYPE.equals(value.content().path("type").asString()))requireReviewSource(attempt,value);else if(WorkflowHistorySnapshot.TYPE.equals(value.content().path("type").asString()))requireHistory(attempt,value);else requireRepository(attempt,value);
            }
            else requireSource(attempt, value);
        }
    }
    private void requireCode(Attempt attempt, WorkflowDelivery.Value value) {
        if (value.content().size() != 3) throw corrupt();
        WorkflowCodeSnapshot.Reference reference;
        try { reference = encoding.decode(encoding.encode(value.content()), WorkflowCodeSnapshot.Reference.class); }
        catch (RuntimeException invalid) { throw corrupt(); }
        if (!encoding.encode(value.content()).equals(encoding.encode(reference))) throw corrupt();
        var snapshot = code.forAttempt(attempt.id()).orElseThrow(WorkflowDeliveryFiles::corrupt);
        var manifest = code.manifest(snapshot.id()).orElseThrow(WorkflowDeliveryFiles::corrupt);
        if (reference.version() != 1 || !snapshot.id().equals(reference.snapshotId()) || !manifest.sha256().equals(reference.sha256())
                || !WorkflowEncoding.hash(manifest.contentJson()).equals(manifest.sha256())) throw corrupt();
    }
    private void requireSource(Attempt attempt,WorkflowDelivery.Value value) {
        if(!attempt.adapterKey().equals(WorkflowSourceSnapshot.ADAPTER)||value.content().size()!=3)throw corrupt();
        WorkflowSourceSnapshot.Reference reference;
        try{reference=encoding.decode(encoding.encode(value.content()),WorkflowSourceSnapshot.Reference.class);}
        catch(RuntimeException invalid){throw corrupt();}
        var row=sources.find(attempt.nodeRunId()).orElseThrow(WorkflowDeliveryFiles::corrupt);
        if(row.readyAt()==null||reference.version()!=1||!row.nodeRunId().equals(reference.snapshotId())
                ||!row.manifestSha256().equals(reference.sha256())||!WorkflowEncoding.hash(row.manifestJson()).equals(reference.sha256())
                ||!encoding.encode(value.content()).equals(encoding.encode(reference)))throw corrupt();
    }
    private void requireRepository(Attempt attempt,WorkflowDelivery.Value value) {
        WorkflowRepositorySnapshot.Reference reference;
        try{reference=encoding.decode(encoding.encode(value.content()),WorkflowRepositorySnapshot.Reference.class);}catch(RuntimeException invalid){throw corrupt();}
        var row=repositories.find(attempt.nodeRunId()).orElseThrow(WorkflowDeliveryFiles::corrupt);
        if(reference.version()!=1||!WorkflowRepositorySnapshot.TYPE.equals(reference.type())||!reference.snapshotId().equals(attempt.nodeRunId())
                ||!reference.sha256().equals(row.manifestSha256())||!encoding.encode(value.content()).equals(encoding.encode(reference)))throw corrupt();
        WorkflowRepositoryRecords.decode(row,encoding);
    }
    private void requireHistory(Attempt attempt,WorkflowDelivery.Value value) {
        WorkflowHistorySnapshot.Reference reference;
        try{reference=encoding.decode(encoding.encode(value.content()),WorkflowHistorySnapshot.Reference.class);}catch(RuntimeException invalid){throw corrupt();}
        var row=histories.find(attempt.nodeRunId()).orElseThrow(WorkflowDeliveryFiles::corrupt);
        if(reference.version()!=1||!WorkflowHistorySnapshot.TYPE.equals(reference.type())||!reference.snapshotId().equals(attempt.nodeRunId())
                ||!reference.sha256().equals(row.manifestSha256())||!encoding.encode(value.content()).equals(encoding.encode(reference)))throw corrupt();
        WorkflowHistoryRecords.decode(row,encoding);
    }
    private void requireReviewSource(Attempt attempt,WorkflowDelivery.Value value) {
        WorkflowReviewSource.Reference reference;
        try{reference=encoding.decode(encoding.encode(value.content()),WorkflowReviewSource.Reference.class);}catch(RuntimeException invalid){throw corrupt();}
        var row=reviews.find(attempt.nodeRunId()).orElseThrow(WorkflowDeliveryFiles::corrupt);
        if(reference.version()!=1||!WorkflowReviewSource.TYPE.equals(reference.type())||!reference.snapshotId().equals(attempt.nodeRunId())
                ||!reference.sha256().equals(row.manifestSha256())||!encoding.encode(value.content()).equals(encoding.encode(reference)))throw corrupt();
        WorkflowReviewRecords.decode(row,encoding);
    }
    private void requireDocument(Attempt attempt,WorkflowDelivery.Value value) {
        WorkflowDocument.Reference reference;
        try{reference=encoding.decode(encoding.encode(value.content()),WorkflowDocument.Reference.class);}catch(RuntimeException invalid){throw corrupt();}
        var row=documents.find(attempt.id()).orElseThrow(WorkflowDeliveryFiles::corrupt);
        var manifest=encoding.decode(row.manifestJson(),WorkflowDocument.Manifest.class);
        if(reference.version()!=1||!WorkflowDocument.type(reference.type())||!attempt.adapterKey().equals(WorkflowDocument.adapterForType(reference.type()))||!attempt.id().equals(reference.attemptId())
                ||!row.sha256().equals(reference.sha256())||!WorkflowEncoding.hash(row.manifestJson()).equals(row.sha256())
                ||manifest.version()!=1||!reference.type().equals(manifest.type())||manifest.files().isEmpty()||documents.count(attempt.id())!=manifest.files().size()
                ||!encoding.encode(value.content()).equals(encoding.encode(reference)))throw corrupt();
    }
    private static ConflictException corrupt() {
        return new ConflictException("WORKFLOW_EXECUTION_INCONSISTENT", "节点执行快照或交付物不一致，已保留原记录");
    }
}
