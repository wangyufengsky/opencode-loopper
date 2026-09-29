package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short transactions own upload admission/availability; file and parser I/O belongs to WorkflowUploads. */
@Service
public class WorkflowUploadStore {
    private final WorkflowUploadMapper uploads;
    private final WorkflowPlanMapper plans;
    private final WorkflowExecutionMapper execution;
    private final WorkflowEncoding encoding;
    public WorkflowUploadStore(WorkflowUploadMapper uploads,WorkflowPlanMapper plans,WorkflowExecutionMapper execution,WorkflowEncoding encoding) {
        this.uploads=uploads;this.plans=plans;this.execution=execution;this.encoding=encoding;
    }
    public record Request(String requestKey,long expectedVersion,int expectedRevision) { }
    public WorkflowRows.Requirement owner(String requirement) {
        return plans.find(requirement).orElseThrow(()->new NotFoundException("需求任务不存在"));
    }
    public Optional<WorkflowUploadMapper.Upload> replay(String key,String digest) {
        if(key==null||!key.matches("[A-Za-z0-9_-]{16,100}"))throw new BadRequestException("WORKFLOW_REQUEST_KEY_INVALID","请使用有效的上传标识");
        var row=uploads.request(key);
        if(row.isPresent()&&!row.get().requestSha256().equals(digest))throw new ConflictException("WORKFLOW_REQUEST_CONFLICT","请使用同一次上传的原文件，或发起新的上传");
        return row;
    }
    public void preflight(String requirement,Request request) {
        var owner=owner(requirement);editable(owner,request.expectedRevision());
        if(owner.version()!=request.expectedVersion())throw WorkflowCommands.conflict();
    }
    public void resumable(WorkflowUploadMapper.Upload row) {editable(owner(row.requirementId()),row.planRevision());}
    @Transactional
    public WorkflowUploadMapper.Upload admit(WorkflowUploadMapper.Upload row,List<WorkflowUploadMapper.File> files,Request request) {
        var previous=replay(row.requestKey(),row.requestSha256());if(previous.isPresent())return previous.get();
        preflight(row.requirementId(),request);
        if(uploads.insert(row)!=1)throw WorkflowCommands.conflict();
        for(var file:files)if(uploads.insertFile(file)!=1)throw WorkflowCommands.conflict();
        return row;
    }
    @Transactional
    public void ready(String id) {
        var row=uploads.find(id).orElseThrow(()->new NotFoundException("上传记录不存在"));
        if(uploads.ready(id))return;
        editable(owner(row.requirementId()),row.planRevision());
        if(uploads.markReady(id,Instant.now().toString())!=1)throw WorkflowCommands.conflict();
    }
    private void editable(WorkflowRows.Requirement owner,int revision) {
        if(owner.headRevision()!=revision||!Set.of("PLANNING","PENDING_START").contains(owner.state())
                ||execution.inputs(owner.id(),owner.headRevision()).isPresent())
            throw new ConflictException("WORKFLOW_UPLOAD_UNAVAILABLE","计划已经变化或公共资料已经固定，请回到当前计划检查输入");
    }
    public WorkflowUploadMapper.Upload authorized(String requirement,WorkflowUpload.Reference reference) {
        if(reference==null||reference.version()!=1||!WorkflowUpload.TYPE.equals(reference.type()))throw invalid();
        var row=uploads.find(reference.uploadId()).orElseThrow(WorkflowUploadStore::invalid);
        if(!row.requirementId().equals(requirement)||!uploads.ready(row.id())||!row.sha256().equals(reference.sha256())
                ||!WorkflowEncoding.hash(row.manifestJson()).equals(row.sha256()))throw invalid();
        return row;
    }
    public WorkflowUpload.Reference reference(String requirement,tools.jackson.databind.JsonNode content) {
        try {
            var ref=encoding.decode(encoding.encode(content),WorkflowUpload.Reference.class);authorized(requirement,ref);return ref;
        } catch(ConflictException invalid){throw invalid;}catch(RuntimeException invalid){throw invalid();}
    }
    public static ConflictException invalid(){return new ConflictException("WORKFLOW_UPLOAD_INVALID","文档尚未完整保存，或固定文档不属于当前需求，请重新选择已保存的资料");}
}
