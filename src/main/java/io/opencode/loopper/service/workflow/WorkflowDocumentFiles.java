package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Body access remains separate from manifest/list reads and tied to the successful producer. */
@Service
@Transactional(readOnly=true)
public class WorkflowDocumentFiles {
    private final WorkflowDocumentMapper documents;
    private final WorkflowExecutionMapper nodes;
    private final WorkflowPlanMapper plans;
    private final WorkflowEncoding encoding;
    public WorkflowDocumentFiles(WorkflowDocumentMapper documents,WorkflowExecutionMapper nodes,WorkflowPlanMapper plans,WorkflowEncoding encoding){this.documents=documents;this.nodes=nodes;this.plans=plans;this.encoding=encoding;}
    public WorkflowDocument.Manifest manifest(String project,String requirement,String producer,WorkflowDocument.Reference reference) {
        if(reference==null||reference.version()!=1||!WorkflowDocument.type(reference.type())||!producer.equals(reference.attemptId()))throw invalid();
        var attempt=nodes.attempt(producer).orElseThrow(WorkflowDocumentFiles::invalid);var node=nodes.node(attempt.nodeRunId()).orElseThrow(WorkflowDocumentFiles::invalid);
        var owner=plans.find(requirement).orElseThrow(WorkflowDocumentFiles::invalid);var row=documents.find(producer).orElseThrow(WorkflowDocumentFiles::invalid);
        if(!node.requirementId().equals(requirement)||!owner.projectId().equals(project)||!attempt.state().equals("SUCCEEDED")||!attempt.adapterKey().equals(WorkflowDocument.adapterForType(reference.type()))
                ||nodes.stop(producer).isEmpty()||!row.sha256().equals(reference.sha256())||!WorkflowEncoding.hash(row.manifestJson()).equals(row.sha256()))throw invalid();
        var delivery=nodes.delivery(producer).orElseThrow(WorkflowDocumentFiles::invalid);
        if(!WorkflowEncoding.hash(delivery.contentJson()).equals(delivery.sha256()))throw invalid();
        var output=encoding.decode(delivery.contentJson(),WorkflowDelivery.class).outputs().get("document");
        if(output==null||output.kind()!=WorkflowGraph.DataKind.DOCUMENT||!encoding.encode(output.content()).equals(encoding.encode(reference)))throw invalid();
        var manifest=encoding.decode(row.manifestJson(),WorkflowDocument.Manifest.class);
        if(manifest.version()!=1||!manifest.type().equals(reference.type())||manifest.files().size()!=documents.count(producer))throw invalid();
        if(manifest.files().size()>WorkflowDocumentPaths.limit(reference.type()))throw invalid();
        manifest.files().forEach(file->WorkflowDocumentPaths.require(reference.type(),file.path()));
        return manifest;
    }
    public byte[] read(String project,String requirement,String producer,WorkflowDocument.Reference reference,String path) {
        var file=manifest(project,requirement,producer,reference).files().stream().filter(value->value.path().equals(path)).findFirst().orElseThrow(()->new NotFoundException("文档清单中不存在此文件"));
        var row=documents.file(producer,path).orElseThrow(WorkflowDocumentFiles::invalid);return checked(file,row);
    }
    public Map<String,byte[]> bundle(String project,String requirement,String producer,WorkflowDocument.Reference reference) {
        var manifest=manifest(project,requirement,producer,reference);var rows=documents.files(producer);var byPath=new HashMap<String,WorkflowDocumentMapper.File>();
        rows.forEach(row->byPath.put(row.path(),row));var result=new LinkedHashMap<String,byte[]>();
        if(rows.size()!=manifest.files().size()||rows.size()>WorkflowDocumentPaths.limit(reference.type())||rows.stream().mapToLong(WorkflowDocumentMapper.File::sizeBytes).sum()>64L*1024*1024)throw invalid();
        for(var file:manifest.files()){var row=byPath.get(file.path());if(row==null)throw invalid();result.put(file.path(),checked(file,row));}
        return result;
    }
    private byte[] checked(WorkflowDocument.File file,WorkflowDocumentMapper.File row) {
        byte[] bytes=row.content().getBytes(StandardCharsets.UTF_8);
        if(file.sizeBytes()!=bytes.length||row.sizeBytes()!=bytes.length||!file.sha256().equals(row.sha256())||!WorkflowEncoding.hash(row.content()).equals(file.sha256()))throw invalid();
        return bytes;
    }
    private static ConflictException invalid(){return new ConflictException("WORKFLOW_DOCUMENT_BINDING_INVALID","文档不属于当前节点的完整固定交付，已保留原记录，请检查输入或数据完整性。");}
}
