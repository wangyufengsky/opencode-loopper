package io.opencode.loopper.service.workflow;

import io.opencode.loopper.service.*;
import io.opencode.loopper.template.SourceManifest;
import io.opencode.loopper.workflow.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Accepted source references resolve only within the producing requirement and node attempt. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowSourceFiles {
    private final WorkflowHistoryRecords histories;
    private final WorkflowHistoryContent historyContent;
    private final WorkflowSourceRecords records;
    private final WorkflowSourceContent content;
    private final WorkflowRepositoryRecords repositories;
    private final GitSnapshotJobs jobs;
    private final io.opencode.loopper.runtime.GitEvidenceProcess git;
    private final WorkflowReviewRecords reviews;
    private final WorkflowReviewContent reviewContent;
    public WorkflowSourceFiles(WorkflowSourceRecords records,WorkflowSourceContent content,WorkflowRepositoryRecords repositories,GitSnapshotJobs jobs,io.opencode.loopper.runtime.GitEvidenceProcess git,WorkflowHistoryRecords histories,WorkflowHistoryContent historyContent,WorkflowReviewRecords reviews,WorkflowReviewContent reviewContent) {
        this.records=records;this.content=content;this.repositories=repositories;this.jobs=jobs;this.git=git;this.histories=histories;this.historyContent=historyContent;this.reviews=reviews;this.reviewContent=reviewContent;
    }
    public WorkflowReviewSource.Manifest manifest(String project,String requirement,String producer,WorkflowReviewSource.Reference reference) {
        return reviews.manifest(project,requirement,producer,reference);
    }
    public byte[] read(String project,String requirement,String producer,WorkflowReviewSource.Reference reference,String path) {
        var file=manifest(project,requirement,producer,reference).files().stream().filter(f->f.path().equals(path)).findFirst().orElseThrow(()->new NotFoundException("版本审查清单中不存在此文件"));
        return reviewContent.read(reference.snapshotId(),file);
    }
    public WorkflowHistorySnapshot.Manifest manifest(String project,String requirement,String producer,WorkflowHistorySnapshot.Reference reference) {
        return histories.manifest(project,requirement,producer,reference);
    }
    public byte[] read(String project,String requirement,String producer,WorkflowHistorySnapshot.Reference reference,String path) {
        var file=manifest(project,requirement,producer,reference).files().stream().filter(f->f.path().equals(path)).findFirst().orElseThrow(()->new NotFoundException("历史证据清单中不存在此文件"));
        return historyContent.read(reference.snapshotId(),file);
    }
    public SourceManifest manifest(String project,String requirement,String producer,WorkflowSourceSnapshot.Reference reference) {
        return records.manifest(project,requirement,producer,reference);
    }
    public byte[] read(String project,String requirement,String producer,WorkflowSourceSnapshot.Reference reference,String path) {
        var file=manifest(project,requirement,producer,reference).files().stream().filter(value->value.path().equals(path)).findFirst()
                .orElseThrow(()->new NotFoundException("冻结源码清单中不存在此文件"));
        return content.read(reference.snapshotId(),file);
    }
    public WorkflowRepositorySnapshot.Manifest manifest(String project,String requirement,String producer,WorkflowRepositorySnapshot.Reference reference) {
        return repositories.manifest(project,requirement,producer,reference);
    }
    public byte[] read(String project,String requirement,String producer,WorkflowRepositorySnapshot.Reference reference,String path) {
        var manifest=manifest(project,requirement,producer,reference);
        var file=manifest.files().stream().filter(value->value.path().equals(path)).findFirst().orElseThrow(()->new NotFoundException("固定分支清单中不存在此文件"));
        if(file.limitation()!=null||GitSnapshotInventory.protectedPath(path)||file.sizeBytes()>2_000_000)
            throw new BadRequestException("WORKFLOW_REPOSITORY_FILE_UNAVAILABLE","该文件存在明确读取限制，请查看采集清单。");
        GitSnapshotInventory.objectId(file.blobSha());
        var repository=jobs.repository(reference.snapshotId());
        try{io.opencode.loopper.runtime.DurableCommandProtocol.check(repository);}catch(java.io.IOException unsafe){throw new ConflictException("WORKFLOW_REPOSITORY_EVIDENCE_INVALID","冻结对象目录不可用");}
        var result=git.bytes(repository,java.time.Duration.ofSeconds(30),java.util.List.of("cat-file","blob",file.blobSha()));
        result.requireSuccess(java.util.List.of("cat-file"));byte[] bytes=result.output();
        if(bytes.length!=file.sizeBytes()||!GitCodeSnapshots.blobId(bytes,file.blobSha().length()).equals(file.blobSha()))
            throw new ConflictException("WORKFLOW_REPOSITORY_EVIDENCE_INVALID","冻结代码对象与清单不一致，请保留原记录");
        return bytes;
    }
}
