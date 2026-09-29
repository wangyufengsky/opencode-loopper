package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.template.DirectDocumentAssessment;
import io.opencode.loopper.workflow.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Fixed upload and committed tree identities; metadata and original DB text are read without filesystem I/O. */
@Component
public final class WorkflowDocumentReviewContext {
    private final WorkflowUploadStore uploads;
    private final WorkflowUploadMapper documents;
    private final WorkflowRepositoryRecords repositories;
    private final WorkflowPlanMapper plans;
    private final WorkflowEncoding encoding;
    public WorkflowDocumentReviewContext(WorkflowUploadStore uploads,WorkflowUploadMapper documents,WorkflowRepositoryRecords repositories,WorkflowPlanMapper plans,WorkflowEncoding encoding){this.uploads=uploads;this.documents=documents;this.repositories=repositories;this.plans=plans;this.encoding=encoding;}
    public record Section(DirectDocumentAssessment.Source source,String filename,String path,String sha256,int characters){ }
    public record Context(String requirement,WorkflowUpload.Reference documents,WorkflowRepositorySnapshot.Reference code,String codeProducer,
                          WorkflowUpload.Manifest documentManifest,WorkflowRepositorySnapshot.Manifest codeManifest,List<Section> assigned,int ordinal){ }
    public Context resolve(WorkflowGraph.Node node,WorkflowDelivery.Inputs inputs) {
        try{WorkflowDocumentReview.require(node);}catch(IllegalArgumentException invalid){throw invalid(invalid.getMessage());}
        return resolve(inputs, node, false);
    }
    public Context all(WorkflowDelivery.Inputs inputs) { return resolve(inputs, null, true); }
    private Context resolve(WorkflowDelivery.Inputs inputs,WorkflowGraph.Node node,boolean all) {
        var source=value(inputs,"documents");var documentRef=uploads.reference(inputs.requirementId(),source.content());
        var uploaded=uploads.authorized(inputs.requirementId(),documentRef);var manifest=encoding.decode(uploaded.manifestJson(),WorkflowUpload.Manifest.class);
        var code=value(inputs,"code");if(!code.source().equals("NODE")||code.attemptId()==null)throw invalid("代码须来自已完成的固定分支采集节点。");
        var codeRef=encoding.decode(encoding.encode(code.content()),WorkflowRepositorySnapshot.Reference.class);
        String project=plans.find(inputs.requirementId()).orElseThrow(WorkflowCommands::conflict).projectId();
        var codeManifest=repositories.manifest(project,inputs.requirementId(),code.attemptId(),codeRef);
        var assigned=all?allSections(manifest,documentRef.uploadId()):selection(node,manifest,documentRef.uploadId());
        return new Context(inputs.requirementId(),documentRef,codeRef,code.attemptId(),manifest,codeManifest,assigned,all?0:WorkflowDocumentReview.ordinal(node));
    }
    private List<Section> allSections(WorkflowUpload.Manifest manifest,String upload) {
        var selected=new ArrayList<Section>();
        for(int file=0;file<manifest.originals().size();file++)for(int section=1;section<=manifest.originals().get(file).sections();section++)
            selected.add(descriptor(manifest,new DirectDocumentAssessment.Source("DOC-"+(file+1),section)));
        if(selected.isEmpty())throw invalid("本次原文没有可评审章节。");
        var metadata=new HashMap<String,WorkflowUploadMapper.Section>();
        for(int offset=0;offset<selected.size();offset+=100)
            documents.sections(upload,selected.subList(offset,Math.min(offset+100,selected.size())).stream().map(Section::path).toList()).forEach(row->metadata.put(row.path(),row));
        return selected.stream().map(ref->metadata(ref,metadata.get(ref.path()))).toList();
    }
    private List<Section> selection(WorkflowGraph.Node node,WorkflowUpload.Manifest manifest,String upload) {
        List<DirectDocumentAssessment.Source> selected;
        String raw=node.parameters().getOrDefault("documentSections","");
        if(raw.isBlank()) {
            selected=new ArrayList<>();
            for(int file=0;file<manifest.originals().size();file++)for(int section=1;section<=manifest.originals().get(file).sections();section++) {
                if(selected.size()>=256)throw batch();selected.add(new DirectDocumentAssessment.Source("DOC-"+(file+1),section));
            }
        } else {
            try{selected=List.of(encoding.decode(raw,DirectDocumentAssessment.Source[].class));}catch(RuntimeException malformed){throw invalid("请按 DOC 编号和章节序号选择本批原文。");}
        }
        if(selected.isEmpty()||selected.size()>256||new HashSet<>(selected).size()!=selected.size())throw batch();
        var descriptors=selected.stream().map(ref->descriptor(manifest,ref)).toList();
        var metadata=new HashMap<String,WorkflowUploadMapper.Section>();
        documents.sections(upload,descriptors.stream().map(Section::path).toList()).forEach(row->metadata.put(row.path(),row));
        var result=descriptors.stream().map(ref->metadata(ref,metadata.get(ref.path()))).toList();
        if(result.size()>1&&result.stream().mapToLong(Section::characters).sum()>48000)throw batch();
        return List.copyOf(result);
    }
    public Section section(Context context,DirectDocumentAssessment.Source source){
        var assigned=context.assigned().stream().filter(row->row.source().equals(source)).findFirst();if(assigned.isPresent())return assigned.get();
        var descriptor=descriptor(context.documentManifest(),source);
        return metadata(descriptor,documents.section(context.documents().uploadId(),descriptor.path()).orElseThrow(WorkflowUploadStore::invalid));
    }
    private Section descriptor(WorkflowUpload.Manifest manifest,DirectDocumentAssessment.Source ref) {
        if(ref==null||ref.fileId()==null||!ref.fileId().matches("DOC-[1-9][0-9]?"))throw invalid("原文编号不属于本次上传。");
        int index=Integer.parseInt(ref.fileId().substring(4))-1;
        if(index>=manifest.originals().size()||ref.section()<1||ref.section()>manifest.originals().get(index).sections())throw invalid("原文章节不属于本次上传。");
        String path=String.format(Locale.ROOT,"parsed/%02d/%04d.md",index+1,ref.section());
        var file=manifest.files().stream().filter(row->row.path().equals(path)).findFirst().orElseThrow(WorkflowUploadStore::invalid);
        return new Section(ref,manifest.originals().get(index).filename(),path,file.sha256(),0);
    }
    private static Section metadata(Section section,WorkflowUploadMapper.Section metadata) {
        if(metadata==null||!metadata.sha256().equals(section.sha256()))throw WorkflowUploadStore.invalid();
        return new Section(section.source(),section.filename(),section.path(),section.sha256(),metadata.characters());
    }
    public WorkflowUploadMapper.File original(Context context,Section section) {
        var value=documents.file(context.documents().uploadId(),section.path()).orElseThrow(WorkflowUploadStore::invalid);
        if(value.content()==null||!value.sha256().equals(section.sha256())||!WorkflowEncoding.hash(value.content()).equals(value.sha256()))throw WorkflowUploadStore.invalid();return value;
    }
    private static WorkflowDelivery.Input value(WorkflowDelivery.Inputs inputs,String name) {
        return inputs.values().stream().filter(value->value.name().equals(name)&&value.kind()==WorkflowGraph.DataKind.DOCUMENT).findFirst().orElseThrow(()->invalid("原文或固定代码输入缺失。"));
    }
    private static BadRequestException batch(){return new BadRequestException("WORKFLOW_DOCUMENT_BATCH_REQUIRED","原文超过单批范围，请按连续章节分批评审；每批最多 256 章、48000 字符，单个超长章节单独成批。");}
    static BadRequestException invalid(String message){return new BadRequestException("WORKFLOW_DOCUMENT_REVIEW_INVALID",message);}
}
