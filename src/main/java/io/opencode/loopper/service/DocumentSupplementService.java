package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** Supplements append sources without assigning overwrite precedence and reuse the existing suffix replan safety checks. */
@Service
public class DocumentSupplementService {
    private final DocumentSupplementMapper supplements;
    private final DocumentSupplementAdmission admission;
    private final DocumentTemplateMapper documents;
    private final DocumentTemplateStorage storage;
    private final DocumentTemplatePreparation preparation;
    private final RollingPackagePlanService plans;
    private final LoopperMapper domain;
    private final ObjectMapper json;
    public DocumentSupplementService(DocumentSupplementMapper supplements,DocumentSupplementAdmission admission,
            DocumentTemplateMapper documents,DocumentTemplateStorage storage,DocumentTemplatePreparation preparation,
            RollingPackagePlanService plans,LoopperMapper domain,ObjectMapper json) {
        this.supplements=supplements;this.admission=admission;this.documents=documents;this.storage=storage;
        this.preparation=preparation;this.plans=plans;this.domain=domain;this.json=json;
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Options options(String id) {
        var run=documents.find(id).orElseThrow(()->new NotFoundException("需求任务不存在"));
        var pending=supplements.pending(id).orElse(null);
        if(pending!=null && pending.uploadReady()==0 && Set.of("PREPARING","WAITING_INPUT").contains(run.state()))
            return new Options(true,"请选择上次补充的原文件，恢复未完成上传。",new Request(pending.requestKey(),pending.baseRunVersion(),pending.baseTaskVersion()));
        try {
            DocumentSupplementAdmission.requireWaiting(run);
            if(pending!=null) throw DocumentSupplementAdmission.changed();
            long version=run.taskId()==null?-1:domain.findTask(run.taskId()).orElseThrow(DocumentSupplementAdmission::changed).version();
            anchor(run,version);
            return new Options(true,"补充文档与原文一起复核，冲突需要澄清；已有执行结果保持原版本。",new Request(UUID.randomUUID().toString(),run.version(),version));
        } catch(ConflictException unavailable) { return new Options(false,unavailable.getMessage(),null); }
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public DocumentTemplateRunRow upload(String id,Request request,List<DocumentTemplateStorage.Incoming> incoming) {
        if(request==null || request.requestKey()==null || !request.requestKey().matches("[A-Za-z0-9_-]{16,100}")
                || request.expectedVersion()<0 || request.expectedTaskVersion() < -1)
            throw new BadRequestException("DOCUMENT_SUPPLEMENT_PARAMETERS","请刷新补充文档表单后重试");
        var existing=supplements.request(id,request.requestKey()).orElse(null);
        var run=documents.find(id).orElseThrow(()->new NotFoundException("需求任务不存在"));
        Anchor anchor=existing==null?anchor(run,request.expectedTaskVersion()):null;
        var prepared=storage.prepare(incoming);
        String digest=DocumentModelStore.hash(json.writeValueAsString(Map.of("request",request,"files",prepared.stream()
                .map(file->Map.of("filename",file.filename(),"sha256",file.sha256())).toList())));
        var saved=existing==null?admission.reserve(id,request,digest,prepared,anchor):DocumentSupplementAdmission.same(existing,digest);
        if(saved.uploadReady()!=0 || saved.appliedAt()!=null) return documents.find(id).orElseThrow();
        var files=documents.files(id).stream().filter(file->file.ordinal()>=saved.firstFileOrdinal()
                && file.ordinal()<saved.firstFileOrdinal()+saved.fileCount()).toList();
        if(files.size()!=prepared.size()) throw DocumentSupplementAdmission.changed();
        for(int index=0;index<files.size();index++) storage.save(files.get(index).relativePath(),prepared.get(index).bytes(),files.get(index).sha256());
        supplements.ready(id,request.requestKey());
        return preparation.recover(id);
    }
    private Anchor anchor(DocumentTemplateRunRow run,long version) {
        DocumentSupplementAdmission.requireWaiting(run);
        if(run.taskId()==null) {
            if(version!=-1) throw DocumentSupplementAdmission.changed();
            if(run.designerId()!=null) {
                var designer=domain.findDesignerSession(run.designerId()).orElseThrow(DocumentSupplementAdmission::changed);
                if(!designer.state().equals("WAITING_INPUT") || designer.taskId()!=null
                        || domain.findCurrentDesignRequirementRevision(designer.id()).isEmpty()) throw DocumentSupplementAdmission.changed();
            }
            return new Anchor(null,0,0);
        }
        var context=plans.safeContext(run.taskId(),version,false,RollingPackageCommandPolicy.Command.REPLAN);
        return new Anchor(context.current().id(),context.current().version(),context.active().revision());
    }
    public record Request(String requestKey,long expectedVersion,long expectedTaskVersion) { }
    public record Anchor(String packageId,long packageVersion,int planRevision) { }
    public record Options(boolean available,String message,Request request) { }
}
