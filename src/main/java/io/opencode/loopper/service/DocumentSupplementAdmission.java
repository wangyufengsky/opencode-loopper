package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Registers immutable supplement metadata before bytes are written, checking the preflight identities again. */
@Service
public class DocumentSupplementAdmission {
    private final DocumentSupplementMapper supplements;
    private final DocumentTemplateAdmission admission;
    private final DocumentTemplateMapper files;
    private final DocumentClarificationMapper clarifications;
    private final DocumentTemplateModelMapper models;
    private final DocumentTemplateControl controls;
    private final LoopperMapper domain;
    private final ObjectMapper json;
    public DocumentSupplementAdmission(DocumentSupplementMapper supplements,DocumentTemplateAdmission admission,
            DocumentTemplateMapper files,DocumentClarificationMapper clarifications,DocumentTemplateModelMapper models,
            DocumentTemplateControl controls,LoopperMapper domain,ObjectMapper json) {
        this.supplements=supplements;this.admission=admission;this.files=files;this.clarifications=clarifications;
        this.models=models;this.controls=controls;this.domain=domain;this.json=json;
    }
    @Transactional
    public DocumentSupplementMapper.Supplement reserve(String id,DocumentSupplementService.Request request,String digest,
            List<DocumentTemplateStorage.Prepared> prepared,DocumentSupplementService.Anchor anchor) {
        var replay=supplements.request(id,request.requestKey());
        if(replay.isPresent()) return same(replay.get(),digest);
        var run=admission.require(id); requireWaiting(run);
        if(run.version()!=request.expectedVersion() || !models.active(id).isEmpty() || supplements.pending(id).isPresent()) throw changed();
        if(run.taskId()!=null) {
            var task=domain.findTask(run.taskId()).orElseThrow(DocumentSupplementAdmission::changed);
            var pack=domain.currentTaskPackageRun(task.id()).orElseThrow(DocumentSupplementAdmission::changed);
            if(task.version()!=request.expectedTaskVersion() || !pack.id().equals(anchor.packageId()) || pack.version()!=anchor.packageVersion()) throw changed();
        }
        var contract=json.readValue(run.contractJson(),DocumentTemplateService.Contract.class); controls.budget(run,contract);
        if(run.basisRevision()>=contract.maxTaskAttempts())
            throw new BadRequestException("DOCUMENT_REVISION_BUDGET_EXHAUSTED","本次需求修订预算已耗尽，请保留已有证据后拆分任务");
        var existing=files.files(id);
        if(existing.size()+prepared.size()>10 || existing.stream().mapToLong(DocumentTemplateFileRow::sizeBytes).sum()
                +prepared.stream().mapToLong(file->file.bytes().length).sum()>DocumentTemplateStorage.MAX_BATCH_BYTES)
            throw new BadRequestException("DOCUMENT_SUPPLEMENT_SIZE","原文与补充文档合计最多 10 份、50 MiB，请按业务范围拆分");
        int revision=run.basisRevision()+1; String now=Instant.now().toString();
        var row=new DocumentSupplementMapper.Supplement(id,request.requestKey(),digest,revision,run.version(),request.expectedTaskVersion(),
                anchor.packageId(),anchor.packageVersion(),existing.size(),prepared.size(),anchor.planRevision(),null,0,now,null);
        if(supplements.insert(row)!=1) throw changed();
        if(run.designerId()!=null && run.taskId()==null) {
            var designer=domain.findDesignerSession(run.designerId()).orElseThrow(DocumentSupplementAdmission::changed);
            var source=domain.findCurrentDesignRequirementRevision(designer.id()).orElseThrow(DocumentSupplementAdmission::changed);
            if(!designer.state().equals("WAITING_INPUT") || designer.taskId()!=null) throw changed();
            var profile=domain.findCurrentDesignerTaskProfile(designer.id()).orElseThrow(DocumentSupplementAdmission::changed);
            if(supplements.insertDesign(new DocumentSupplementMapper.Design(id,request.requestKey(),designer.id(),source.id(),
                    UUID.randomUUID().toString(),json.writeValueAsString(profile),designer.version(),designer.discussionRevision(),now))!=1) throw changed();
        }
        String answers=clarifications.latest(id).map(DocumentClarificationMapper.Revision::answersJson).orElse("[]");
        if(!run.directDocuments() && clarifications.insert(new DocumentClarificationMapper.Revision(id,revision,run.basisRevision(),
                request.requestKey(),digest,answers,now))!=1) throw changed();
        admission.appendFiles(run,prepared,existing.size());
        admission.transition(run,DocumentTemplateState.PREPARING,LifecycleEvent.PREPARE,null,null);
        return row;
    }
    static void requireWaiting(DocumentTemplateRunRow run) {
        if(!run.templateId().equals("REQUIREMENT_DEVELOPMENT") || !run.state().equals("WAITING_INPUT")
                || !Set.of("DESIGNING","EXECUTING").contains(Objects.toString(run.resumeState(),"")) || run.basisRevision()<1) throw changed();
    }
    static DocumentSupplementMapper.Supplement same(DocumentSupplementMapper.Supplement row,String digest) {
        if(!row.requestSha256().equals(digest)) throw new ConflictException("DOCUMENT_SUPPLEMENT_REQUEST_CONFLICT","同一次补充请求的文档或参数已变化，请重新选择后提交");
        return row;
    }
    static ConflictException changed() {
        return new ConflictException("DOCUMENT_SUPPLEMENT_STATE_CHANGED","补充文档需要需求或未执行设计等待，或已停止且具有成功事实点的滚动包等待；请先处理关联任务的当前状态");
    }
}
