package io.opencode.loopper.service.workflow;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.persistence.WorkflowUploadMapper;
import io.opencode.loopper.service.*;
import io.opencode.loopper.service.assist.AssistDocumentParser;
import io.opencode.loopper.workflow.WorkflowUpload;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Durable upload identity is committed before original bytes are saved; retry resumes that exact identity. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowUploads {
    private final WorkflowUploadMapper mapper;
    private final WorkflowUploadStore store;
    private final WorkflowEncoding encoding;
    private final DocumentTemplateStorage storage;
    private final Set<String> active=ConcurrentHashMap.newKeySet();
    public WorkflowUploads(WorkflowUploadMapper mapper,WorkflowUploadStore store,WorkflowEncoding encoding,DocumentTemplateStorage storage) {
        this.mapper=mapper;this.store=store;this.encoding=encoding;this.storage=storage;
    }
    private record Identity(WorkflowUploadStore.Request request,List<Raw> files) { }
    private record Raw(String filename,String sha256) { }
    public WorkflowUpload.Summary upload(String requirement,WorkflowUploadStore.Request request,List<DocumentTemplateStorage.Incoming> incoming) {
        validate(incoming);
        incoming=incoming.stream().map(file->new DocumentTemplateStorage.Incoming(file.filename(),file.bytes().clone())).toList();
        String digest=encoding.digest("DOCUMENT_UPLOAD",requirement,new Identity(request,incoming.stream()
                .map(file->new Raw(file.filename(),DocumentTemplateStorage.hash(file.bytes()))).toList()));
        var previous=store.replay(request.requestKey(),digest);
        if(previous.isPresent()&&mapper.ready(previous.get().id()))return summary(previous.get(),true);
        if(!active.add(request.requestKey()))throw new ConflictException("WORKFLOW_UPLOAD_BUSY","同一次上传仍在保存，请稍后查看上传记录");
        try {
            WorkflowUploadMapper.Upload row;
            if(previous.isPresent())row=previous.get();
            else {store.preflight(requirement,request);row=admit(requirement,request,digest,storage.prepare(incoming));}
            if(mapper.ready(row.id()))return summary(row,true);
            store.resumable(row);
            // Availability is never recorded until every original can be read back with its fixed checksum.
            var manifest=manifest(row);
            for(int i=0;i<manifest.originals().size();i++) {
                var original=manifest.originals().get(i);var file=mapper.file(row.id(),original.path()).orElseThrow(WorkflowUploadStore::invalid);
                storage.save(file.storagePath(),incoming.get(i).bytes(),original.sha256());
            }
            store.ready(row.id());return summary(row,true);
        } finally {active.remove(request.requestKey());}
    }
    private WorkflowUploadMapper.Upload admit(String requirement,WorkflowUploadStore.Request request,String digest,List<DocumentTemplateStorage.Prepared> prepared) {
        String id=UUID.randomUUID().toString(),now=Instant.now().toString();
        var originals=new ArrayList<WorkflowUpload.Original>();var files=new ArrayList<WorkflowUpload.File>();
        var contents=new ArrayList<WorkflowUploadMapper.File>();int ordinal=0;long parsedBytes=0;
        for(var item:prepared) {
            String prefix=String.format(Locale.ROOT,"%02d",++ordinal),path="original/"+prefix+"/"+item.filename();
            originals.add(new WorkflowUpload.Original(item.filename(),path,item.bytes().length,item.sha256(),item.representationSha256(),item.document().format(),item.document().sections().size(),item.document().limitations()));
            files.add(new WorkflowUpload.File(path,item.bytes().length,item.sha256()));
            contents.add(new WorkflowUploadMapper.File(id,path,item.bytes().length,item.sha256(),storage.relativePath(id,UUID.randomUUID().toString()),null));
            int section=0;
            for(var part:item.document().sections()) {
                String text="# "+part.title()+"\n\n"+part.markdown(),name="parsed/"+prefix+"/"+String.format(Locale.ROOT,"%04d",++section)+".md";
                int size=text.getBytes(StandardCharsets.UTF_8).length;
                parsedBytes+=size;
                if(size>32*1024*1024||parsedBytes>64L*1024*1024)throw new BadRequestException("WORKFLOW_UPLOAD_LIMIT","解析后的文档超过 64 MiB 或单章节过大，请拆分原文");
                String hash=WorkflowEncoding.hash(text);files.add(new WorkflowUpload.File(name,size,hash));
                contents.add(new WorkflowUploadMapper.File(id,name,size,hash,null,text));
            }
        }
        var manifest=new WorkflowUpload.Manifest(1,WorkflowUpload.TYPE,AssistDocumentParser.VERSION,List.copyOf(originals),List.copyOf(files));
        String body=encoding.encode(manifest),sha=WorkflowEncoding.hash(body);
        if(body.getBytes(StandardCharsets.UTF_8).length>16*1024*1024)throw new BadRequestException("WORKFLOW_UPLOAD_LIMIT","文档目录过大，请拆分原文");
        var summary=new WorkflowUpload.Summary(id,now,false,new WorkflowUpload.Reference(1,WorkflowUpload.TYPE,id,sha),manifest.parserVersion(),manifest.originals(),new WorkflowUpload.Resume(request.requestKey(),request.expectedVersion(),request.expectedRevision()));
        return store.admit(new WorkflowUploadMapper.Upload(id,requirement,request.expectedRevision(),request.requestKey(),digest,body,sha,encoding.encode(summary),now),contents,request);
    }
    public CursorPage<WorkflowUpload.Summary> list(String requirement,String cursor,int requested) {
        store.owner(requirement);int limit=PageCursor.limit(requested);var after=PageCursor.decode(cursor);
        var rows=mapper.page(requirement,after==null?null:after.value(),after==null?null:after.id(),limit+1);
        var items=rows.stream().limit(limit).map(row->summary(row.summaryJson(),row.ready())).toList();
        var last=items.isEmpty()?null:items.getLast();return new CursorPage<>(items,rows.size()>limit?new PageCursor(last.createdAt(),last.id()).encode():null);
    }
    public WorkflowUpload.Summary get(String requirement,String id) {
        var row=mapper.find(id).orElseThrow(WorkflowUploadStore::invalid);
        if(!row.requirementId().equals(requirement))throw WorkflowUploadStore.invalid();return summary(row,mapper.ready(id));
    }
    public WorkflowUpload.Manifest manifest(String project,String requirement,WorkflowUpload.Reference reference) {
        if(!store.owner(requirement).projectId().equals(project))throw WorkflowUploadStore.invalid();
        return manifest(store.authorized(requirement,reference));
    }
    public byte[] read(String project,String requirement,WorkflowUpload.Reference reference,String path) {
        var manifest=manifest(project,requirement,reference);
        if(path.equals("manifest.json"))return encoding.encode(manifest).getBytes(StandardCharsets.UTF_8);
        var entry=manifest.files().stream().filter(file->file.path().equals(path)).findFirst().orElseThrow(()->new NotFoundException("固定文档中不存在此文件"));
        var file=mapper.file(reference.uploadId(),path).orElseThrow(WorkflowUploadStore::invalid);
        byte[] bytes=file.storagePath()!=null?storage.read(file.storagePath(),entry.sha256()):file.content().getBytes(StandardCharsets.UTF_8);
        if(bytes.length!=entry.sizeBytes()||!DocumentTemplateStorage.hash(bytes).equals(entry.sha256()))throw WorkflowUploadStore.invalid();return bytes;
    }
    private WorkflowUpload.Manifest manifest(WorkflowUploadMapper.Upload row) {
        if(!WorkflowEncoding.hash(row.manifestJson()).equals(row.sha256()))throw WorkflowUploadStore.invalid();
        return encoding.decode(row.manifestJson(),WorkflowUpload.Manifest.class);
    }
    private WorkflowUpload.Summary summary(WorkflowUploadMapper.Upload row,boolean ready){return summary(row.summaryJson(),ready);}
    private WorkflowUpload.Summary summary(String body,boolean ready) {
        var value=encoding.decode(body,WorkflowUpload.Summary.class);
        return new WorkflowUpload.Summary(value.id(),value.createdAt(),ready,value.reference(),value.parserVersion(),value.originals(),ready?null:value.resume());
    }
    private static void validate(List<DocumentTemplateStorage.Incoming> files) {
        if(files==null||files.isEmpty()||files.size()>10)throw new BadRequestException("DOCUMENT_TEMPLATE_FILES_REQUIRED","请上传 1–10 份需求文档");
        long size=0;
        for(var file:files) {
            if(file==null||file.bytes()==null||file.bytes().length==0||file.bytes().length>DocumentTemplateStorage.MAX_FILE_BYTES)
                throw new BadRequestException("DOCUMENT_TEMPLATE_FILE_SIZE","单份文档不能为空或超过 20 MiB");
            size+=file.bytes().length;
        }
        if(size>DocumentTemplateStorage.MAX_BATCH_BYTES)throw new BadRequestException("DOCUMENT_TEMPLATE_BATCH_SIZE","本次文档总大小超过 50 MiB");
    }
}
