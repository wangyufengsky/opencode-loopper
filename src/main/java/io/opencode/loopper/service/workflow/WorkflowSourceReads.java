package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Per-role evidence records only the immutable bytes actually returned by its authorized MCP call. */
@Service
public class WorkflowSourceReads {
    private final WorkflowModelStore models;
    private final WorkflowNodeRuns nodes;
    private final WorkflowSourceDesignContract contract;
    private final WorkflowTestWorkContract tests;
    private final WorkflowTestReviewContract reviews;
    private final WorkflowSourceContent content;
    private final WorkflowSourceReadMapper reads;
    private final TransactionTemplate transactions;
    public WorkflowSourceReads(WorkflowModelStore models,WorkflowNodeRuns nodes,WorkflowSourceDesignContract contract,WorkflowSourceContent content,
            WorkflowSourceReadMapper reads,TransactionTemplate transactions,WorkflowTestWorkContract tests,WorkflowTestReviewContract reviews){this.models=models;this.nodes=nodes;this.contract=contract;this.content=content;this.reads=reads;this.transactions=transactions;this.tests=tests;this.reviews=reviews;}
    public record SourceText(String path,String sha256,int startLine,int endLine,int totalLines,String content,Integer nextLine){ }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public SourceText file(WorkflowModelMapper.Launch row,Map<String,Object> args) {
        if(!Set.of("name","path","sha256","startLine","lineCount").containsAll(args.keySet()))throw parameters();
        String name=WorkflowModelTools.text(args,"name",64),path=WorkflowModelTools.text(args,"path",2048),expected=WorkflowModelTools.text(args,"sha256",64);
        int start=WorkflowModelTools.integer(args,"startLine",1,1,Integer.MAX_VALUE),limit=WorkflowModelTools.integer(args,"lineCount",200,1,200);
        active(row);var attempt=models.attempt(row);byte[] bytes;
        if(name.equals("code")&&WorkflowTestReview.supports(models.definition(row).moduleId())) {
            var context=reviews.context(models.definition(row),nodes.inputs(attempt));bytes=reviews.file(context,path,expected);
        }else {
            if(!name.equals("source"))throw parameters();var context=source(row);
            var file=context.manifest().files().stream().filter(value->value.path().equals(path)).findFirst().orElseThrow(WorkflowSourceReads::parameters);
            if(file.sha256()==null||!file.sha256().equals(expected))throw parameters();bytes=content.read(context.reference().snapshotId(),file);
        }
        final List<String> lines;
        try{lines=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString().lines().toList();}
        catch(java.nio.charset.CharacterCodingException invalid){throw parameters();}
        if(start>lines.size()+1)throw parameters();
        int end=(int)Math.min(lines.size(),(long)start-1+limit);String text=String.join("\n",lines.subList(start-1,end));
        if(text.length()>48000)throw new BadRequestException("SOURCE_READ_LIMIT","所选源码段过长，请减少读取行数。");
        var result=new SourceText(path,expected,start,end,lines.size(),text,end<lines.size()?end+1:null);
        transactions.executeWithoutResult(tx->{active(row);reads.source(new WorkflowSourceReadMapper.SourceRead(attempt.id(),name,path,expected,start,end,lines.size(),text,Instant.now().toString()));});
        return result;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void input(WorkflowModelMapper.Launch row,WorkflowDelivery.Input input,String text,int start,int end) {
        if(input.kind()!=WorkflowGraph.DataKind.JSON)return;
        active(row);reads.input(new WorkflowSourceReadMapper.InputRead(row.attemptId(),input.name(),WorkflowEncoding.hash(text),start,end,text.length(),Instant.now().toString()));
    }
    private record Source(WorkflowSourceSnapshot.Reference reference,io.opencode.loopper.template.SourceManifest manifest){ }
    private Source source(WorkflowModelMapper.Launch row) {
        var definition=models.definition(row);var values=nodes.inputs(models.attempt(row));
        if(WorkflowTestReview.supports(definition.moduleId())){var context=reviews.context(definition,values).original().fixed();return new Source(context.frozen().source(),context.manifest());}
        if(WorkflowTestDesign.supports(definition.moduleId())||WorkflowTestWrite.supports(definition.moduleId())){var context=tests.context(definition,values).inputs();return new Source(context.frozen().source(),context.manifest());}
        var context=contract.context(definition,values);return new Source(context.reference(),context.manifest());
    }
    public Map<String,Object> work(WorkflowModelMapper.Launch row){
        var definition=models.definition(row);var values=nodes.inputs(models.attempt(row));
        if(WorkflowTestReview.supports(definition.moduleId()))return reviews.work(definition,values);
        return WorkflowTestDesign.supports(definition.moduleId())||WorkflowTestWrite.supports(definition.moduleId())?tests.work(definition,values):contract.work(definition,values);
    }
    public Object cases(WorkflowModelMapper.Launch row,Map<String,Object> args){active(row);return reviews.cases(models.definition(row),nodes.inputs(models.attempt(row)),args);}
    public static boolean supports(String module){return WorkflowSourceDesign.supports(module)||WorkflowTestDesign.supports(module)||WorkflowTestWrite.supports(module)||WorkflowTestReview.supports(module);}
    private void active(WorkflowModelMapper.Launch row) {
        models.activeWork(row);
        if(row.suspended()||!Set.of("DISPATCHING","RUNNING").contains(row.state())||models.stopProof(row.attemptId()).isPresent()
                ||!supports(models.definition(row).moduleId()))throw WorkflowCommands.conflict();
    }
    private static BadRequestException parameters(){return new BadRequestException("SOURCE_READ_PARAMETERS_INVALID","请按本节点工作信息使用固定输入名称、文件哈希、起始行和最多 200 行读取源码。");}
}
