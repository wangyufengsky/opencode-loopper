package io.opencode.loopper.service.workflow;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.persistence.WorkflowKnowledgeMapper;
import io.opencode.loopper.service.*;
import io.opencode.loopper.service.assist.AssistRedaction;
import io.opencode.loopper.workflow.WorkflowKnowledgeEvidence;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Historical reads use only accepted receipts from the selected attempt, including after termination. */
@Service
@Transactional(readOnly=true)
public class WorkflowNodeKnowledgeEvidence {
    private final WorkflowNodeRuns nodes;
    private final WorkflowKnowledgeMapper mapper;
    private final ObjectMapper json;
    public WorkflowNodeKnowledgeEvidence(WorkflowNodeRuns nodes,WorkflowKnowledgeMapper mapper,ObjectMapper json) {
        this.nodes=nodes;this.mapper=mapper;this.json=json;
    }
    public CursorPage<WorkflowKnowledgeEvidence.Entry> list(String requirement,String key,String attemptId,String cursor,int requested) {
        var attempt=nodes.scopedAttempt(requirement,key,attemptId);int limit=PageCursor.limit(requested);var after=PageCursor.decode(cursor);
        String session=attempt.externalSessionId(),owner="WORKFLOW_ATTEMPT:"+attempt.id();
        if(after!=null) {
            var identity=mapper.nodeEvidenceIdentity(session,owner,after.id());
            if(identity==null||!identity.createdAt().equals(after.value()))throw new BadRequestException("WORKFLOW_KNOWLEDGE_CURSOR_INVALID","证据游标不属于当前执行，请重新读取列表");
        }
        if(session==null)return new CursorPage<>(List.of(),null);
        var rows=mapper.nodeEvidencePage(session,owner,after==null?"":after.value(),after==null?"":after.id(),limit+1);
        var items=rows.stream().limit(limit).map(row->new WorkflowKnowledgeEvidence.Entry(row.id(),row.toolName(),row.createdAt())).toList();
        return new CursorPage<>(items,rows.size()>limit?new PageCursor(items.getLast().createdAt(),items.getLast().id()).encode():null);
    }
    public WorkflowKnowledgeEvidence.Body read(String requirement,String key,String attemptId,String id) {
        var attempt=nodes.scopedAttempt(requirement,key,attemptId);
        var row=mapper.nodeEvidence(attempt.externalSessionId(),"WORKFLOW_ATTEMPT:"+attempt.id(),id);
        if(row==null)throw new NotFoundException("当前执行中没有此份保存证据");
        if(row.resultJson()==null||row.resultJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1_048_576)
            throw new ConflictException("WORKFLOW_KNOWLEDGE_EVIDENCE_INVALID","保存证据不完整或超出范围，请保留现场并检查原执行记录");
        try {
            var content=json.readTree(AssistRedaction.text(row.resultJson()));
            if(content==null||!content.isObject())throw invalid();
            return new WorkflowKnowledgeEvidence.Body(row.id(),row.toolName(),row.createdAt(),content);
        } catch(tools.jackson.core.JacksonException malformed) {throw invalid();}
    }
    private static ConflictException invalid(){return new ConflictException("WORKFLOW_KNOWLEDGE_EVIDENCE_INVALID","保存证据不完整，请保留现场并检查原执行记录");}
}
