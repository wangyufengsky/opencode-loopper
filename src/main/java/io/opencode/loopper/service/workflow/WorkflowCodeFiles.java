package io.opencode.loopper.service.workflow;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.runtime.WorkflowModelProfile;
import io.opencode.loopper.persistence.WorkflowModelMapper.Launch;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Resolves an exact named binding before reading managed bytes. Never accepts a producer ID from the model. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowCodeFiles {
    private final WorkflowNodeRuns nodes;
    private final WorkflowPlans plans;
    private final WorkflowCodeSnapshots codes;
    private final WorkflowEncoding encoding;
    private final WorkflowModelStore models;
    private final WorkflowSourceFiles sources;
    private final WorkflowDocumentFiles documents;
    private final WorkflowUploads uploads;
    public WorkflowCodeFiles(WorkflowNodeRuns nodes,WorkflowPlans plans,WorkflowCodeSnapshots codes,WorkflowEncoding encoding,WorkflowModelStore models,WorkflowSourceFiles sources,WorkflowDocumentFiles documents,WorkflowUploads uploads) {
        this.nodes=nodes;this.plans=plans;this.codes=codes;this.encoding=encoding;this.models=models;
        this.sources=sources;
        this.documents=documents;
        this.uploads=uploads;
    }
    public record Binding(String project,String requirement,String producer,WorkflowCodeSnapshot.Reference reference,WorkflowSourceSnapshot.Reference sourceReference,WorkflowDocument.Reference documentReference,boolean ownOutput,WorkflowUpload.Reference uploadReference,WorkflowRepositorySnapshot.Reference repositoryReference,WorkflowHistorySnapshot.Reference historyReference,WorkflowReviewSource.Reference reviewReference) {
        public Binding(String project,String requirement,String producer,WorkflowCodeSnapshot.Reference reference,WorkflowSourceSnapshot.Reference sourceReference,WorkflowDocument.Reference documentReference,boolean ownOutput,WorkflowUpload.Reference uploadReference,WorkflowRepositorySnapshot.Reference repositoryReference,WorkflowHistorySnapshot.Reference historyReference){this(project,requirement,producer,reference,sourceReference,documentReference,ownOutput,uploadReference,repositoryReference,historyReference,null);}
        public Binding(String project,String requirement,String producer,WorkflowCodeSnapshot.Reference reference,WorkflowSourceSnapshot.Reference sourceReference,WorkflowDocument.Reference documentReference,boolean ownOutput,WorkflowUpload.Reference uploadReference,WorkflowRepositorySnapshot.Reference repositoryReference){this(project,requirement,producer,reference,sourceReference,documentReference,ownOutput,uploadReference,repositoryReference,null);}
        public Binding(String project,String requirement,String producer,WorkflowCodeSnapshot.Reference reference,WorkflowSourceSnapshot.Reference sourceReference,WorkflowDocument.Reference documentReference,boolean ownOutput,WorkflowUpload.Reference uploadReference){this(project,requirement,producer,reference,sourceReference,documentReference,ownOutput,uploadReference,null);}
        public Binding(String project,String requirement,String producer,WorkflowCodeSnapshot.Reference reference,WorkflowSourceSnapshot.Reference sourceReference,WorkflowDocument.Reference documentReference,boolean ownOutput){this(project,requirement,producer,reference,sourceReference,documentReference,ownOutput,null);}
        public Binding(String project,String requirement,String producer,WorkflowCodeSnapshot.Reference reference,WorkflowSourceSnapshot.Reference sourceReference,WorkflowDocument.Reference documentReference){this(project,requirement,producer,reference,sourceReference,documentReference,false);}
        public Binding(String project,String requirement,String producer,WorkflowCodeSnapshot.Reference reference){this(project,requirement,producer,reference,null,null);}
        public Binding(String project,String requirement,String producer,WorkflowCodeSnapshot.Reference reference,WorkflowSourceSnapshot.Reference sourceReference){this(project,requirement,producer,reference,sourceReference,null);}
        public Binding{if((reference==null?0:1)+(sourceReference==null?0:1)+(documentReference==null?0:1)+(uploadReference==null?0:1)+(repositoryReference==null?0:1)+(historyReference==null?0:1)+(reviewReference==null?0:1)!=1)throw new IllegalArgumentException("One fixed file reference is required");}
        public String sha256(){return reference!=null?reference.sha256():sourceReference!=null?sourceReference.sha256():documentReference!=null?documentReference.sha256():uploadReference!=null?uploadReference.sha256():repositoryReference!=null?repositoryReference.sha256():historyReference!=null?historyReference.sha256():reviewReference.sha256();}
    }
    public record File(String path,long sizeBytes,String sha256,String mode,Boolean target,String exclusion,String blobSha) {
        public File(String path,long sizeBytes,String sha256,String mode,Boolean target,String exclusion){this(path,sizeBytes,sha256,mode,target,exclusion,null);}
        public File(String path,long sizeBytes,String sha256,String mode){this(path,sizeBytes,sha256,mode,null,null);}
    }
    public record Text(String path,String sha256,String text,int offset,Integer nextOffset) { }
    public Binding input(String requirement,String key,String attemptId,String name) {
        var attempt=nodes.scopedAttempt(requirement,key,attemptId);
        var input=nodes.inputSnapshot(attempt).values().stream().filter(value->value.name().equals(name)).findFirst().orElseThrow(WorkflowCodeFiles::missing);
        if(input.kind()==WorkflowGraph.DataKind.DOCUMENT&&input.source().equals("REQUIREMENT")&&WorkflowUpload.TYPE.equals(input.content().path("type").asString()))
            return uploaded(requirement,encoding.decode(encoding.encode(input.content()),WorkflowUpload.Reference.class));
        if (!Set.of(WorkflowGraph.DataKind.CODE,WorkflowGraph.DataKind.DOCUMENT).contains(input.kind()) || !input.source().equals("NODE") || input.attemptId()==null) throw missing();
        return binding(requirement,input.attemptId(),input.content(),input.kind());
    }
    public Binding output(String requirement,String key,String attemptId,String name) {
        nodes.scopedAttempt(requirement,key,attemptId);
        var delivery=encoding.decode(nodes.delivery(attemptId).contentJson(),WorkflowDelivery.class);
        var output=delivery.outputs().get(name);
        if (output==null || !Set.of(WorkflowGraph.DataKind.CODE,WorkflowGraph.DataKind.DOCUMENT).contains(output.kind())) throw missing();
        var value=binding(requirement,attemptId,output.content(),output.kind());
        return new Binding(value.project(),value.requirement(),value.producer(),value.reference(),value.sourceReference(),value.documentReference(),true,value.uploadReference(),value.repositoryReference(),value.historyReference(),value.reviewReference());
    }
    public Object model(Launch row,String tool,Map<String,Object> args) {
        String name=WorkflowModelTools.text(args,"name",64);
        var attempt=models.attempt(row);var node=models.definition(row);
        var bound=input(row.requirementId(),node.id(),attempt.id(),name);
        Object result;
        if (tool.equals(WorkflowModelProfile.FILES)) {
            if (!Set.of("name","cursor","limit").containsAll(args.keySet())) throw parameters();
            String cursor=args.containsKey("cursor")?WorkflowModelTools.text(args,"cursor",2048):null;
            result=list(bound,cursor,WorkflowModelTools.integer(args,"limit",50,1,100));
        } else {
            if (!Set.of("name","path","offset","limit").containsAll(args.keySet())) throw parameters();
            result=text(bound,WorkflowModelTools.text(args,"path",1024),WorkflowModelTools.integer(args,"offset",0,0,bound.historyReference()!=null||bound.reviewReference()!=null?WorkflowHistorySnapshot.MAX_FILE_BYTES:4_000_000),
                    WorkflowModelTools.integer(args,"limit",12000,1,12000));
        }
        models.activeWork(row);
        if (models.stopProof(row.attemptId()).isPresent()) throw WorkflowCommands.conflict();
        return result;
    }
    public CursorPage<File> list(Binding binding,String cursor,int requested) {
        int limit=PageCursor.limit(requested);var after=PageCursor.decode(cursor);
        if (after!=null && !after.value().equals(binding.sha256())) throw parameters();
        java.util.stream.Stream<File> files;
        if(binding.reviewReference()!=null)files=sources.manifest(binding.project(),binding.requirement(),binding.producer(),binding.reviewReference()).files().stream()
                .map(file->new File(file.path(),file.sizeBytes(),file.sha256(),null));
        else if(binding.historyReference()!=null)files=sources.manifest(binding.project(),binding.requirement(),binding.producer(),binding.historyReference()).files().stream()
                .map(file->new File(file.path(),file.sizeBytes(),file.sha256(),null));
        else if(binding.uploadReference()!=null){
            var manifest=uploads.manifest(binding.project(),binding.requirement(),binding.uploadReference());
            files=java.util.stream.Stream.concat(java.util.stream.Stream.of(new File("manifest.json",encoding.encode(manifest).getBytes(StandardCharsets.UTF_8).length,binding.sha256(),null)),
                    manifest.files().stream().map(file->new File(file.path(),file.sizeBytes(),file.sha256(),null)));
        }
        else if(binding.repositoryReference()!=null)files=sources.manifest(binding.project(),binding.requirement(),binding.producer(),binding.repositoryReference()).files().stream()
                .map(file->new File(file.path(),file.sizeBytes(),null,file.mode(),null,file.limitation(),file.blobSha()));
        else if(binding.documentReference()!=null)files=documents.manifest(binding.project(),binding.requirement(),binding.producer(),binding.documentReference()).files().stream()
                .map(file->new File(file.path(),file.sizeBytes(),file.sha256(),null));
        else if(binding.sourceReference()!=null)files=sources.manifest(binding.project(),binding.requirement(),binding.producer(),binding.sourceReference()).files().stream()
                .map(file->new File(file.path(),file.sizeBytes(),file.sha256(),null,file.target(),file.exclusion()));
        else files=codeManifest(binding).files().stream()
                .map(file->new File(file.path(),file.sizeBytes(),file.sha256(),file.mode()));
        var result=files.filter(file->after==null || file.path().compareTo(after.id())>0).sorted(Comparator.comparing(File::path)).limit(limit+1L).toList();
        var items=result.stream().limit(limit).toList();
        return new CursorPage<>(items,result.size()>limit?new PageCursor(binding.sha256(),items.getLast().path()).encode():null);
    }
    public CursorPage<WorkflowCodeSnapshot.Change> changes(Binding binding,String cursor,int requested) {
        if(binding.reference()==null)throw new BadRequestException("WORKFLOW_CODE_CHANGES_REQUIRED","请选择代码交付物查看改动文件");
        int limit=PageCursor.limit(requested);var after=PageCursor.decode(cursor);
        String scope=binding.sha256()+":changes";
        if(after!=null&&!after.value().equals(scope))throw parameters();
        var result=codeManifest(binding).changes().stream()
                .filter(change->after==null||change.path().compareTo(after.id())>0)
                .sorted(Comparator.comparing(WorkflowCodeSnapshot.Change::path)).limit(limit+1L).toList();
        var items=result.stream().limit(limit).toList();
        return new CursorPage<>(items,result.size()>limit?new PageCursor(scope,items.getLast().path()).encode():null);
    }
    public byte[] bytes(Binding binding,String path) {
        if(binding.reviewReference()!=null)return sources.read(binding.project(),binding.requirement(),binding.producer(),binding.reviewReference(),path);
        if(binding.historyReference()!=null)return sources.read(binding.project(),binding.requirement(),binding.producer(),binding.historyReference(),path);
        if(binding.repositoryReference()!=null)return sources.read(binding.project(),binding.requirement(),binding.producer(),binding.repositoryReference(),path);
        if(binding.uploadReference()!=null)return uploads.read(binding.project(),binding.requirement(),binding.uploadReference(),path);
        if(binding.documentReference()!=null)return documents.read(binding.project(),binding.requirement(),binding.producer(),binding.documentReference(),path);
        if(binding.sourceReference()!=null)return sources.read(binding.project(),binding.requirement(),binding.producer(),binding.sourceReference(),path);
        var manifest=codeManifest(binding);var file=manifest.files().stream().filter(entry->entry.path().equals(path)).findFirst()
                .orElseThrow(()->new NotFoundException("冻结代码清单中不存在此文件"));
        return codes.readAcceptedFile(manifest,file);
    }
    private WorkflowCodeSnapshot codeManifest(Binding binding) {
        return binding.ownOutput()?codes.outputManifest(binding.project(),binding.requirement(),binding.producer(),binding.reference())
                :codes.manifest(binding.project(),binding.requirement(),binding.producer(),binding.reference());
    }
    public Binding uploaded(String requirement,WorkflowUpload.Reference reference) {
        String project=plans.require(requirement).projectId();uploads.manifest(project,requirement,reference);
        return new Binding(project,requirement,null,null,null,null,false,reference);
    }
    public byte[] archive(Binding binding) {
        if(binding.documentReference()==null)throw missing();
        var files=documents.bundle(binding.project(),binding.requirement(),binding.producer(),binding.documentReference());
        try(var bytes=new java.io.ByteArrayOutputStream();var zip=new java.util.zip.ZipOutputStream(bytes,StandardCharsets.UTF_8)) {
            for(var file:files.entrySet()){var entry=new java.util.zip.ZipEntry(file.getKey());entry.setTime(0);zip.putNextEntry(entry);zip.write(file.getValue());zip.closeEntry();}
            zip.finish();return bytes.toByteArray();
        }catch(java.io.IOException failure){throw new IllegalStateException("文档压缩包生成失败",failure);}
    }
    public Text text(Binding binding,String path,int offset,int limit) {
        if (offset<0 || limit<1 || limit>12000) throw parameters();
        byte[] bytes=bytes(binding,path);final String text;
        try { text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException failure) { throw binary(); }
        if (text.indexOf('\0')>=0) throw binary();
        if (offset>text.length() || offset>0 && offset<text.length() && Character.isLowSurrogate(text.charAt(offset)) && Character.isHighSurrogate(text.charAt(offset-1))) throw parameters();
        int end=offset;for(int count=0;count<limit && end<text.length();count++) end+=Character.charCount(text.codePointAt(end));
        return new Text(path,WorkflowEncoding.hash(text),text.substring(offset,end),offset,end<text.length()?end:null);
    }
    private Binding binding(String requirement,String producer,tools.jackson.databind.JsonNode value,WorkflowGraph.DataKind kind) {
        String project=plans.require(requirement).projectId();
        try {
            if(kind==WorkflowGraph.DataKind.DOCUMENT&&WorkflowReviewSource.TYPE.equals(value.path("type").asString()))return new Binding(project,requirement,producer,null,null,null,false,null,null,null,encoding.decode(encoding.encode(value),WorkflowReviewSource.Reference.class));
            if(kind==WorkflowGraph.DataKind.DOCUMENT&&WorkflowHistorySnapshot.TYPE.equals(value.path("type").asString()))return new Binding(project,requirement,producer,null,null,null,false,null,null,encoding.decode(encoding.encode(value),WorkflowHistorySnapshot.Reference.class));
            if(kind==WorkflowGraph.DataKind.DOCUMENT&&WorkflowRepositorySnapshot.TYPE.equals(value.path("type").asString()))return new Binding(project,requirement,producer,null,null,null,false,null,encoding.decode(encoding.encode(value),WorkflowRepositorySnapshot.Reference.class));
            if(kind==WorkflowGraph.DataKind.DOCUMENT&&WorkflowDocument.type(value.path("type").asString()))return new Binding(project,requirement,producer,null,null,encoding.decode(encoding.encode(value),WorkflowDocument.Reference.class));
            if(kind==WorkflowGraph.DataKind.DOCUMENT)return new Binding(project,requirement,producer,null,encoding.decode(encoding.encode(value),WorkflowSourceSnapshot.Reference.class));
            return new Binding(project,requirement,producer,encoding.decode(encoding.encode(value),WorkflowCodeSnapshot.Reference.class));
        }
        catch (RuntimeException invalid) { throw missing(); }
    }
    private static BadRequestException missing() { return new BadRequestException("WORKFLOW_CODE_BINDING_REQUIRED","该名称不是本节点可读取的固定文件交付，请查询节点的输入输出名称"); }
    private static BadRequestException parameters() { return new BadRequestException("WORKFLOW_FILE_PARAMETERS_INVALID","文件读取参数或分页游标不属于当前交付，请从文件列表重新读取"); }
    private static BadRequestException binary() { return new BadRequestException("WORKFLOW_FILE_NOT_TEXT","该文件不是 UTF-8 文本，请从交付物文件列表下载原始文件"); }
}
