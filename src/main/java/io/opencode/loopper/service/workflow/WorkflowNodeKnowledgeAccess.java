package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.assist.*;
import io.opencode.loopper.service.roles.RoleConfigurationService;
import java.nio.file.Path;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** A node's auxiliary evidence remains bound to one active attempt and its frozen Session policy. */
@Service
public final class WorkflowNodeKnowledgeAccess {
    private final WorkflowModelMapper models;
    private final WorkflowExecutionMapper nodes;
    private final WorkflowPlanMapper plans;
    private final AssistMapper assist;
    private final RoleConfigurationService roles;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    public WorkflowNodeKnowledgeAccess(WorkflowModelMapper models,WorkflowExecutionMapper nodes,WorkflowPlanMapper plans,
            AssistMapper assist,RoleConfigurationService roles,ObjectMapper json,TransactionTemplate transactions) {
        this.models=models;this.nodes=nodes;this.plans=plans;this.assist=assist;this.roles=roles;this.json=json;this.transactions=transactions;
    }
    public static boolean supports(String profile){return Set.of("WORKFLOW_READ_ONLY","WORKFLOW_WRITE").contains(profile);}
    public AssistScopeService.Scope resolve(AssistMapper.Session session) {
        var scope=active(session);
        try{if(!scope.directory().equals(scope.directory().toRealPath()))throw denied();}
        catch(java.io.IOException unavailable){throw denied();}
        assist.bindScopeOwner(session.externalSessionId(),scope.ownerKey());
        if(!scope.ownerKey().equals(assist.scopeOwner(session.externalSessionId())))throw denied();
        return scope;
    }
    public void require(AssistScopeService.Scope expected) {
        if(!active(assist.session(expected.externalSessionId())).equals(expected))throw denied();
    }
    /** DB-only recheck and receipt completion share the same short transaction as cancellation exclusion. */
    public void complete(AssistScopeService.Scope scope,String id,String content,String now) {
        transactions.executeWithoutResult(tx->{
            require(scope);
            if(assist.finishScopedCall(id,scope.ownerKey(),scope.externalSessionId(),content,now)!=1)throw denied();
        });
    }
    private AssistScopeService.Scope active(AssistMapper.Session session) {
        if(session==null||!supports(session.profile()))throw denied();
        var model=models.session(session.externalSessionId()).orElseThrow(WorkflowNodeKnowledgeAccess::denied);
        var attempt=nodes.attempt(model.attemptId()).orElseThrow(WorkflowNodeKnowledgeAccess::denied);
        var node=nodes.node(attempt.nodeRunId()).orElseThrow(WorkflowNodeKnowledgeAccess::denied);
        var owner=plans.find(node.requirementId()).orElseThrow(WorkflowNodeKnowledgeAccess::denied);
        if(!Set.of("DISPATCHING","RUNNING").contains(model.state())||model.suspended()||!attempt.state().equals("RUNNING")
                ||nodes.stop(attempt.id()).isPresent()||!Set.of("RUNNING","PAUSED","STALLED").contains(owner.state())
                ||!model.requirementId().equals(owner.id())||!model.directory().equals(session.directory())
                ||!WorkflowModelProfile.profile(attempt.adapterKey()).name().equals(session.profile()))throw denied();
        var role=roles.sessionSnapshot(session.externalSessionId()).orElseThrow(WorkflowNodeKnowledgeAccess::denied);
        if(!role.context().owner().type().equals("WORKFLOW_ATTEMPT")||!role.context().owner().id().equals(attempt.id())
                ||!role.adapterProfile().equals(session.profile()))throw denied();
        List<OpenCodeClient.SessionPermissionRule> policy=json.readValue(session.permissionsJson(),new TypeReference<>(){});
        if(!policy.equals(role.permissionPolicy()))throw denied();
        String server=json.readTree(model.creationPlanJson()).path("internalMcpServer").asString();
        if(server.isBlank())throw denied();
        List<String> names=json.readValue(session.toolsJson(),new TypeReference<>(){});
        var tools=names.stream().filter(AssistToolCatalog::knowledgeTool)
                .filter(tool->ConfiguredRoleRuntime.allowed(policy,AssistToolCatalog.serverName(server)+"_"+tool)).toList();
        return new AssistScopeService.Scope(session.externalSessionId(),"WORKFLOW_ATTEMPT:"+attempt.id(),owner.projectId(),
                null,null,attempt.id(),null,session.profile(),Path.of(model.directory()),tools,List.of());
    }
    private static AssistFailure denied(){return new AssistFailure("WORKFLOW_KNOWLEDGE_DENIED","知识读取不属于当前活动节点、冻结角色或执行版本，请检查原节点状态","REAUTHORIZE");}
}
