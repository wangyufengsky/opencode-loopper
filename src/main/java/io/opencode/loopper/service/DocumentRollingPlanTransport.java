package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Exact creation/message identities make supplement planning recoverable without duplicate delivery. */
@Service
public final class DocumentRollingPlanTransport {
    @org.springframework.beans.factory.annotation.Autowired(required = false) private io.opencode.loopper.service.RoleSessions roleSessions;
    private final DocumentPlanTransportMapper transports;
    private final LoopperMapper domain;
    private final RollingPackagePlanService plans;
    private final RollingPackagePlanCandidateOrchestrator candidates;
    private final OpenCodeClient runtime;
    private final Optional<CandidateRuntimeBindingService> bindings;
    private final ObjectMapper json;
    public DocumentRollingPlanTransport(DocumentPlanTransportMapper transports,LoopperMapper domain,RollingPackagePlanService plans,
            RollingPackagePlanCandidateOrchestrator candidates,OpenCodeClient runtime,Optional<CandidateRuntimeBindingService> bindings,ObjectMapper json) {
        this.transports=transports;this.domain=domain;this.plans=plans;this.candidates=candidates;
        this.runtime=runtime;this.bindings=bindings;this.json=json;
    }
    public void advance(TaskPackagePlanRevisionRow proposed,Path directory,String facts,OpenCodeClient.OpenCodeModel model) {
        var binding=bindings.orElseThrow(() -> unavailable("补充需求规划的运行身份校验未启用"));
        var row=active(proposed);
        var saved=transports.find(row.id()).orElse(null);
        if(saved==null) {
            byte[] nonce=new byte[32]; new java.security.SecureRandom().nextBytes(nonce);
            var plan=runtime.prepareSessionCreation(directory,"需求补充计划 "+row.id(),model,
                    OpenCodeClient.SessionProfile.ROLLING_PACKAGE_CANDIDATE_READ_ONLY,Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
            plan = RoleSessions.prepare(roleSessions, plan, "TASK", row.taskId(), null);
            if(!plan.managed() || plan.internalMcpServer()==null) throw unavailable("补充需求规划需要托管角色 MCP");
            transports.insert(new DocumentPlanTransportMapper.Transport(row.id(),json.writeValueAsString(plan),null,null,Instant.now().toString()));
            return;
        }
        var creation=json.readValue(saved.creationPlanJson(),OpenCodeClient.SessionCreationPlan.class);
        if(row.externalSessionId()==null) {
            var lookup=runtime.findSessionsByExactTitle(creation);
            if(!lookup.supported() || lookup.matches().size()>1) throw unavailable("无法唯一核对规划会话，保留原创建身份等待恢复");
            active(row);
            var remote=lookup.matches().isEmpty()?runtime.createSession(creation):lookup.matches().getFirst();
            binding.bindInternalAttested(remote,creation);
            plans.attachSuggestionSession(row,remote.session().id(),"PROMPTING");
            return;
        }
        var remote=new OpenCodeClient.OpenCodeSession(row.externalSessionId(),creation.canonicalDirectory(),
                creation.runtimeGenerationId(),creation.internalMcpServer());
        if(saved.promptJson()==null) {
            var start=candidates.open(row,remote,facts);
            var prompt=new FrozenPrompt(start.prompt(),OpenCodeClient.STRUCTURED_AGENT_PROMPT,
                    OpenCodeClient.STRUCTURED_AGENT,"msg_"+row.id().replace("-",""));
            if(transports.prompt(row.id(),json.writeValueAsString(prompt),OpenCodeClient.promptRequestSha256(prompt.request()))!=1) throw unavailable("规划请求已被并发保存，请读取后恢复");
            return;
        }
        var prompt=json.readValue(saved.promptJson(),FrozenPrompt.class).request();
        if(!OpenCodeClient.promptRequestSha256(prompt).equals(saved.promptSha256())) throw unavailable("冻结规划请求校验失败");
        runtime.restoreDesignTurn(remote,creation.profile(),creation.model(),prompt.messageId());
        var lookup=runtime.findPromptMessage(remote,prompt,saved.promptSha256());
        if(!lookup.supported()) throw unavailable("规划请求送达状态未知，禁止重复发送");
        if(lookup.exists() && !saved.promptSha256().equals(lookup.verifiedRequestSha256())) throw unavailable("远端规划消息身份不符");
        active(row);
        if(!lookup.exists()) runtime.promptAsync(remote,prompt);
        plans.updateSuggestionState(domain.findTaskPackagePlanRevision(row.id()).orElseThrow(),"RUNNING");
    }
    public boolean stop(TaskPackagePlanRevisionRow row) {
        var saved=transports.find(row.id()).orElse(null);
        if(saved==null) {
            if(row.externalSessionId()!=null) return false;
            plans.failSuggestion(row,"DOCUMENT_PLAN_CANCELLED","规划尚未创建模型会话","NOT_CREATED"); return true;
        }
        var creation=json.readValue(saved.creationPlanJson(),OpenCodeClient.SessionCreationPlan.class);
        var lookup=runtime.findSessionsByExactTitle(creation);
        if(!lookup.supported()) return false;
        for(var found:lookup.matches()) {
            if(!found.plan().equals(creation)) return false;
            var remote=found.session();
            if(runtime.abortWithConfirmation(remote)==null) return false;
        }
        if(row.externalSessionId()!=null) {
            var result=candidates.terminateAfterDispatchFailure(row,creation.canonicalDirectory(),"DOCUMENT_PLAN_CANCELLED","补充需求规划已取消");
            if(!CandidateSessionTerminationProof.persisted(result.terminationProof())) return false;
        }
        plans.failSuggestion(domain.findTaskPackagePlanRevision(row.id()).orElseThrow(),"DOCUMENT_PLAN_CANCELLED",
                "补充需求规划已确认停止",lookup.matches().isEmpty()?CandidateSessionTerminationProof.ALREADY_ABSENT.name():CandidateSessionTerminationProof.ABORT_ACKNOWLEDGED.name());
        return true;
    }
    private TaskPackagePlanRevisionRow active(TaskPackagePlanRevisionRow proposed) {
        var current=domain.findTaskPackagePlanRevision(proposed.id()).orElseThrow();
        if(current.version()!=proposed.version() || !current.state().equals("GENERATING")
                || !TemplateDevelopmentAuthorization.planning(domain, current.taskId())
                || !TemplateDevelopmentAuthorization.planCurrent(domain, current.designerSessionId(), current.id())) throw unavailable("需求规划拥有者或来源版本已变化");
        return current;
    }
    private static ConflictException unavailable(String message) { return new ConflictException("DOCUMENT_PLAN_TRANSPORT_UNCONFIRMED",message); }
    public record FrozenPrompt(String text,String system,String agent,String messageId) {
        public OpenCodeClient.PromptRequest request() {
            return new OpenCodeClient.PromptRequest(text,system,agent,new OpenCodeClient.ResponseFormat.Text(),messageId,List.of());
        }
    }
}
