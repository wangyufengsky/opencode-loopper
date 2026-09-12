package io.opencode.loopper.service.assist;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.PageCursor;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Queries persisted facts only. Judge raw output is deliberately excluded. */
@Service
public class AssistEvidenceService {
    private final AssistMapper mapper;
    private final LoopperMapper domain;
    private final ObjectMapper json;
    public AssistEvidenceService(AssistMapper mapper,LoopperMapper domain,ObjectMapper json){this.mapper=mapper;this.domain=domain;this.json=json;}
    public Map<String,Object> context(AssistScopeService.Scope scope,String cursor) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("tools",scope.tools());result.put("profile",scope.profile());
        result.put("evidenceIsData",true);result.put("directory",scope.directory().toString());
        if(scope.stageId()!=null) {
            var stage=domain.findStage(scope.stageId()).orElseThrow(()->new AssistFailure("STAGE_MISSING","当前阶段不可用","REAUTHORIZE"));
            result.put("stage",Map.of("objective",stage.objective(),"allowedPaths",json.readTree(stage.allowedPathsJson()),
                    "forbiddenPaths",json.readTree(stage.forbiddenPathsJson()),"deliverables",json.readTree(stage.deliverablesJson())));
        }
        result.put("attachments",scope.taskId()!=null?mapper.taskAttachments(scope.taskId()):scope.designerId()!=null?mapper.designerAttachments(scope.designerId()):List.of());
        PageCursor page=PageCursor.decode(cursor);String time=page==null?"":page.value(),id=page==null?"":page.id();
        if(scope.taskId()!=null)result.put("evidence",page(mapper.evidence(scope.taskId(),time,id,51,before(scope))));
        result.put("calls",page(mapper.calls(scope.ownerKey(),time,id,51,before(scope))));
        result.put("instructions","使用 evidence:产物ID、verification:验证ID 或 call:调用ID 读取正文。只有已完成调用有结果；历史错误不代表当前任务仍然失败。正式验收仍由验证器与独立双 Judge 决定。");
        return result;
    }
    private Map<String,Object> page(List<Map<String,Object>> rows) {
        var visible=rows.stream().limit(50).toList();String cursor="";
        if(rows.size()>50){var last=visible.getLast();cursor=new PageCursor(Objects.toString(last.get("created_at")),Objects.toString(last.get("id"))).encode();}
        return Map.of("items",visible,"nextCursor",cursor);
    }
    public Map<String,Object> failure(AssistScopeService.Scope scope,String attempt) {
        requireTask(scope);String selected=attempt;
        if(selected==null||selected.isBlank())selected=mapper.previousAttempt(scope.taskId(),scope.stageId());
        if(selected==null)return Map.of("found",false,"detail","当前阶段没有已完成尝试；没有失败证据不能推断任务成功");
        if(mapper.ownsCompletedAttempt(scope.taskId(),scope.stageId(),selected)!=1)throw denied();
        var row=domain.findAttempt(selected).orElseThrow(AssistEvidenceService::denied);
        var result=new LinkedHashMap<String,Object>();result.put("found",true);result.put("attemptId",selected);
        result.put("state",row.state());result.put("historical",true);result.put("verification",mapper.verificationFacts(scope.taskId(),selected));
        result.put("detail","此处为已完成尝试的不可变事实；先读取失败验证正文定位原因，修复后重新执行验证，不以旧错误推断当前故障。");return result;
    }
    public Map<String,Object> read(AssistScopeService.Scope scope,String reference,int offset) {
        if(reference==null||reference.length()>200)throw denied();String content;
        if(reference.startsWith("call:"))content=mapper.evidenceCall(scope.ownerKey(),reference.substring(5),before(scope));
        else {requireTask(scope);
            if(reference.startsWith("evidence:"))content=mapper.evidenceBody(scope.taskId(),reference.substring(9),before(scope));
            else if(reference.startsWith("verification:"))content=mapper.verificationBody(scope.taskId(),reference.substring(13),before(scope));
            else throw new AssistFailure("EVIDENCE_REFERENCE_INVALID","请使用目录返回的 evidence:、verification: 或 call: 引用");
        }
        if(content==null)throw denied();content=AssistRedaction.text(content);
        if(offset<0||offset>content.length())throw new AssistFailure("EVIDENCE_CURSOR_INVALID","请使用上次结果返回的 nextOffset");
        int end=Math.min(content.length(),offset+12000);if(end<content.length()&&Character.isHighSurrogate(content.charAt(end-1)))end--;
        return Map.of("reference",reference,"content",content.substring(offset,end),"nextOffset",end<content.length()?end:-1,
                "sha256",AssistFiles.sha(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"historical",true);
    }
    private String before(AssistScopeService.Scope scope) {
        return scope.profile().contains("JUDGE")||scope.profile().contains("REVIEWER")?mapper.session(scope.externalSessionId()).createdAt():"9999";
    }
    private static void requireTask(AssistScopeService.Scope scope){if(scope.taskId()==null)throw new AssistFailure("TASK_SCOPE_REQUIRED","当前会话尚未绑定执行任务，请使用设计资料工具");}
    private static AssistFailure denied(){return new AssistFailure("EVIDENCE_SCOPE_DENIED","证据不存在、未完成或不属于当前授权范围","REAUTHORIZE");}
}
