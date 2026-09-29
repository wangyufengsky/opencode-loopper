package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.nio.*;
import java.nio.charset.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Only exact bytes returned to an active document reviewer become its reading evidence. */
@Service
public class WorkflowDocumentReads {
    private final WorkflowModelStore models;
    private final WorkflowNodeRuns nodes;
    private final WorkflowDocumentReviewContext contexts;
    private final WorkflowDocumentReviewContract contract;
    private final WorkflowCodeFiles files;
    private final WorkflowDocumentReadMapper reads;
    private final TransactionTemplate transactions;
    public WorkflowDocumentReads(WorkflowModelStore models,WorkflowNodeRuns nodes,WorkflowDocumentReviewContext contexts,WorkflowDocumentReviewContract contract,
            WorkflowCodeFiles files,WorkflowDocumentReadMapper reads,TransactionTemplate transactions){this.models=models;this.nodes=nodes;this.contexts=contexts;this.contract=contract;this.files=files;this.reads=reads;this.transactions=transactions;}
    public record Text(String name,String path,String sha256,String blobSha,int startLine,int endLine,int totalLines,String content,Integer nextLine){ }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Text file(WorkflowModelMapper.Launch row,Map<String,Object> args) {
        String name=WorkflowModelTools.text(args,"name",64),path=WorkflowModelTools.text(args,"path",2048);
        boolean code=name.equals("code");if(!code&&!name.equals("documents"))throw parameters();
        String hashKey=code?"blobSha":"sha256";
        if(!Set.of("name","path",hashKey,"startLine","lineCount").containsAll(args.keySet()))throw parameters();
        String hash=WorkflowModelTools.text(args,hashKey,64);
        int start=WorkflowModelTools.integer(args,"startLine",1,1,Integer.MAX_VALUE),limit=WorkflowModelTools.integer(args,"lineCount",200,1,200);
        active(row);var definition=models.definition(row);var attempt=models.attempt(row);var context=contexts.resolve(definition,nodes.inputs(attempt));
        if(code) {
            var file=context.codeManifest().files().stream().filter(value->value.path().equals(path)).findFirst().orElseThrow(WorkflowDocumentReads::parameters);
            if(file.limitation()!=null||!file.blobSha().equals(hash))throw parameters();
        } else {
            if(!path.matches("parsed/[0-9]{2}/[0-9]{4,}\\.md"))throw parameters();
            var file=context.documentManifest().files().stream().filter(value->value.path().equals(path)).findFirst().orElseThrow(WorkflowDocumentReads::parameters);
            if(!file.sha256().equals(hash))throw parameters();
        }
        var binding=files.input(row.requirementId(),definition.id(),attempt.id(),name);byte[] bytes=files.bytes(binding,path);
        final List<String> lines;
        try{lines=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().lines().toList();}
        catch(CharacterCodingException invalid){throw parameters();}
        if(start>lines.size()+1)throw parameters();int end=(int)Math.min(lines.size(),(long)start-1+limit);
        String text=String.join("\n",lines.subList(start-1,end));if(text.length()>48000)throw new BadRequestException("SOURCE_READ_LIMIT","所选内容过长，请减少每次读取的行数。");
        var result=new Text(name,path,code?null:hash,code?hash:null,start,end,lines.size(),text,end<lines.size()?end+1:null);
        transactions.executeWithoutResult(tx->{active(row);reads.read(new WorkflowDocumentReadMapper.Read(attempt.id(),name,path,hash,start,end,lines.size(),text,Instant.now().toString()));});
        return result;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void input(WorkflowModelMapper.Launch row,WorkflowDelivery.Input input,String text,int start,int end) {
        if(input.kind()!=WorkflowGraph.DataKind.JSON)return;active(row);
        reads.input(new WorkflowDocumentReadMapper.Input(row.attemptId(),input.name(),WorkflowEncoding.hash(text),start,end,text.length(),Instant.now().toString()));
    }
    public Map<String,Object> work(WorkflowModelMapper.Launch row){active(row);return contract.work(models.definition(row),nodes.inputs(models.attempt(row)));}
    private void active(WorkflowModelMapper.Launch row) {
        models.activeWork(row);
        if(row.suspended()||!Set.of("DISPATCHING","RUNNING").contains(row.state())||models.stopProof(row.attemptId()).isPresent()
            ||!WorkflowDocumentReview.supports(models.definition(row).moduleId()))throw WorkflowCommands.conflict();
    }
    private static BadRequestException parameters(){return new BadRequestException("WORKFLOW_DOCUMENT_READ_INVALID","请按当前节点工作信息使用固定文件、哈希、起始行和最多 200 行读取；代码使用 blobSha，原文使用 sha256。");}
}
