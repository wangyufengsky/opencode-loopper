package io.opencode.loopper.service.workflow;

import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.service.ConflictException;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Local observation only: disconnected transcript reads cannot change execution state. */
@Service
public class WorkflowRunActivity {
    private final WorkflowRunReads reads;
    private final WorkflowModelStore models;
    private final OpenCodeClient client;
    public WorkflowRunActivity(WorkflowRunReads reads,WorkflowModelStore models,OpenCodeClient client){this.reads=reads;this.models=models;this.client=client;}
    public record Activity(boolean connected,String observedAt,String detail,List<OpenCodeClient.SessionPart> parts,boolean truncated) { }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Activity get(String id,String key,String attempt) {
        var summary=reads.get(id,key,attempt);
        if(summary.modelState()==null)return new Activity(false,Instant.now().toString(),"人工节点没有模型日志。",List.of(),false);
        var row=models.require(attempt);var frozen=models.attempt(row);
        if(frozen.externalSessionId()==null || row.promptJson()==null)
            return new Activity(false,Instant.now().toString(),row.state().equals("SUCCEEDED")?"本次没有创建模型会话，请查看交付物中的执行来源。":"正在等待模型会话准备完成。",List.of(),false);
        try {
            var remote=models.remote(row);var plan=models.plan(row);
            client.restoreDesignTurn(remote,plan.profile(),plan.model(),models.prompt(row).messageId());
            var source=client.sessionTranscript(remote).parts();
            if(!Objects.equals(models.attempt(models.require(attempt)).externalSessionId(),frozen.externalSessionId()))
                throw new ConflictException("WORKFLOW_VERSION_CONFLICT","执行信息已变化，请重新读取。");
            var parts=new ArrayList<OpenCodeClient.SessionPart>();int remaining=96000;boolean truncated=source.size()>80;
            for(int index=source.size()-1;index>=Math.max(0,source.size()-80);index--) {
                var part=source.get(index);
                String body=part.content()==null?"":part.content();int limit=Math.min(8000,remaining);
                int end=Math.min(body.length(),limit);if(end>0&&end<body.length()&&Character.isHighSurrogate(body.charAt(end-1)))end--;
                truncated|=end<body.length();remaining-=end;
                parts.add(new OpenCodeClient.SessionPart(part.id(),part.type(),part.label(),body.substring(0,end),part.status(),part.startedAt()));
                if(remaining<=0){truncated=true;break;}
            }
            return new Activity(true,Instant.now().toString(),null,List.copyOf(parts.reversed()),truncated);
        } catch(RuntimeException unavailable) {
            return new Activity(false,Instant.now().toString(),"模型日志暂时无法读取，请检查运行环境后重试；节点状态以画布记录为准。",List.of(),false);
        }
    }
}
