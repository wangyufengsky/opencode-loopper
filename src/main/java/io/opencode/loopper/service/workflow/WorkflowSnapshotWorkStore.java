package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.workflow.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Database-only immutable prepared input and exact page coverage for one model attempt. */
@Service
@Transactional(readOnly=true)
public class WorkflowSnapshotWorkStore {
    private final WorkflowSnapshotWorkMapper mapper;
    private final WorkflowModelMapper models;
    private final WorkflowEncoding encoding;
    public WorkflowSnapshotWorkStore(WorkflowSnapshotWorkMapper mapper,WorkflowModelMapper models,WorkflowEncoding encoding){this.mapper=mapper;this.models=models;this.encoding=encoding;}
    public Optional<WorkflowSnapshotWork.Input> find(String attempt){return mapper.find(attempt).map(this::decode);}
    public WorkflowSnapshotWork.Input require(String attempt){return find(attempt).orElseThrow(WorkflowCommands::conflict);}
    @Transactional
    public void save(WorkflowModelMapper.Launch expected,WorkflowSnapshotWork.Input input) {
        var current=models.find(expected.attemptId()).orElseThrow(WorkflowCommands::conflict);
        if(current.version()!=expected.version()||!current.state().equals("PREPARING")||current.suspended())throw WorkflowCommands.conflict();
        String body=encoding.encode(input);
        if(body.getBytes(StandardCharsets.UTF_8).length>WorkflowSnapshotWork.MAX_INPUT_BYTES)
            throw invalid("本批版本审查输入超过 2 MiB，请缩小采集范围；没有截断证据。");
        var prior=mapper.find(expected.attemptId());
        if(prior.isPresent()){if(!encoding.encode(decode(prior.get())).equals(body))throw WorkflowCommands.conflict();return;}
        if(mapper.insert(new WorkflowSnapshotWorkMapper.Input(expected.attemptId(),input.sourceAttempt(),input.source().sha256(),body,
                WorkflowEncoding.hash(body),Instant.now().toString()))!=1)throw WorkflowCommands.conflict();
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public WorkflowInputPages.Page page(String attempt,int offset,int limit) {
        var row=mapper.find(attempt).orElseThrow(WorkflowCommands::conflict);decode(row);
        var input=new WorkflowDelivery.Input("analysis",WorkflowGraph.DataKind.JSON,"SYSTEM",attempt,null,null,row.sha256(),null);
        var page=WorkflowInputPages.page(input,row.inputJson(),offset,limit);
        mapper.page(new WorkflowSnapshotWorkMapper.Page(attempt,row.sha256(),offset,offset+page.text().length(),page.totalLength(),Instant.now().toString()));return page;
    }
    public void requireRead(String attempt) {
        var input=mapper.find(attempt).orElseThrow(WorkflowCommands::conflict);decode(input);int end=0;
        for(var read:mapper.pages(attempt)) {
            if(!input.sha256().equals(read.sha256())||read.totalLength()!=input.inputJson().length()||read.startOffset()>end)throw invalid("请完整读取本批固定分析输入后提交。");
            end=Math.max(end,read.endOffset());
        }
        if(end!=input.inputJson().length())throw invalid("请完整读取本批固定分析输入后提交。");
    }
    @Transactional
    public void reserve(WorkflowModelMapper.Launch expected,String digest){
        active(expected);
        try{mapper.context(expected.attemptId(),digest,Instant.now().toString());}
        catch(org.springframework.dao.DataAccessException failure){
            if(failure.getMessage()!=null&&failure.getMessage().contains("related context limit"))throw invalid("本批 12 次关联读取边界已到达；请使用已交付证据提交，将缺口写入 limitations。");
            throw failure;
        }
    }
    @Transactional
    public void receipt(WorkflowModelMapper.Launch expected,io.opencode.loopper.template.SnapshotReview.Reference reference){
        active(expected);mapper.receipt(new WorkflowSnapshotWorkMapper.Receipt(expected.attemptId(),reference.version(),reference.path(),reference.blob(),reference.startLine(),reference.endLine(),reference.quote(),Instant.now().toString()));
    }
    public Optional<String> content(String attempt,io.opencode.loopper.template.SnapshotReview.Reference reference){
        return mapper.receiptAt(attempt,reference.version(),reference.path(),reference.blob(),reference.startLine(),reference.endLine()).map(WorkflowSnapshotWorkMapper.Receipt::content);
    }
    public void active(WorkflowModelMapper.Launch expected){
        var current=models.find(expected.attemptId()).orElseThrow(WorkflowCommands::conflict);
        if(current.version()!=expected.version()||current.suspended()||!Set.of("DISPATCHING","RUNNING").contains(current.state()))throw WorkflowCommands.conflict();
    }
    private WorkflowSnapshotWork.Input decode(WorkflowSnapshotWorkMapper.Input row) {
        if(!WorkflowEncoding.hash(row.inputJson()).equals(row.sha256()))throw WorkflowCommands.conflict();
        var input=encoding.decode(row.inputJson(),WorkflowSnapshotWork.Input.class);
        if(input.version()!=1||!WorkflowSnapshotWork.supports(input.module())||!input.sourceAttempt().equals(row.sourceAttemptId())||!input.source().sha256().equals(row.sourceSha256()))throw WorkflowCommands.conflict();return input;
    }
    static BadRequestException invalid(String message){return new BadRequestException("WORKFLOW_SNAPSHOT_WORK_INVALID",message);}
}
