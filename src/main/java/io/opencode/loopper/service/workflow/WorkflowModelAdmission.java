package io.opencode.loopper.service.workflow;

import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.service.roles.RoleConfigurationService;
import io.opencode.loopper.workflow.*;
import java.nio.file.Path;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Filesystem preflight precedes the short, idempotent admission transaction. No Session is created here. */
@Service
public class WorkflowModelAdmission {
    private final WorkflowPlans plans;
    private final ProjectService projects;
    private final WorkflowNodeActions actions;
    private final WorkflowNodeRuns nodes;
    private final WorkflowModelStore store;
    private final RoleConfigurationService roles;
    private final WorkflowCommands commands;
    private final TransactionTemplate transactions;
    private final WorkflowWriterLeases writers;
    public WorkflowModelAdmission(WorkflowPlans plans, ProjectService projects, WorkflowNodeActions actions, WorkflowNodeRuns nodes,
            WorkflowModelStore store, RoleConfigurationService roles, WorkflowCommands commands, TransactionTemplate transactions, WorkflowWriterLeases writers) {
        this.plans=plans; this.projects=projects; this.actions=actions; this.nodes=nodes;
        this.store=store; this.roles=roles; this.commands=commands; this.transactions=transactions; this.writers=writers;
    }
    public record Start(String requestKey, long expectedVersion, Map<String, WorkflowDelivery.Value> inputs,
                        OpenCodeClient.OpenCodeModel model) { }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public WorkflowNodeActions.Receipt start(String id, String key, Start request) { return startInternal(id,key,request,null); }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public WorkflowNodeActions.Receipt dispatch(String id,String key,Start request,WorkflowDispatch.Permit permit) { return startInternal(id,key,request,permit); }
    private WorkflowNodeActions.Receipt startInternal(String id, String key, Start request,WorkflowDispatch.Permit permit) {
        String digest = store.encoding().digest("NODE_MODEL_START", id + "/" + key, request);
        var replay = commands.replay(request.requestKey(), digest, WorkflowNodeActions.Receipt.class);
        if (replay.isPresent()) return replay.get();
        requireModel(request.model());
        var project = projects.get(plans.require(id).projectId());
        final Path directory;
        try { directory = Path.of(project.rootPath()).toRealPath(); }
        catch (java.io.IOException failure) { throw new BadRequestException("WORKFLOW_DIRECTORY_UNAVAILABLE", "项目目录不可访问，请先检查项目配置"); }
        var identity=DirectWorkspaceLeaseCoordinator.identify(directory);
        return transactions.execute(tx -> {
            var repeated = commands.replay(request.requestKey(), digest, WorkflowNodeActions.Receipt.class);
            if (repeated.isPresent()) return repeated.get();
            var currentProject = projects.get(plans.require(id).projectId());
            if (!currentProject.rootPath().equals(project.rootPath()) || currentProject.version()!=project.version())
                throw WorkflowCommands.conflict();
            var admitted = actions.admit(id, key, new WorkflowNodeActions.Start(request.requestKey(), request.expectedVersion(), request.inputs()),permit);
            var definition = admitted.definition(); requireSupported(definition);
            boolean writer=WorkflowModelProfile.writeModule(definition.moduleId());
            String adapter=writer?WorkflowModelProfile.WRITE_ADAPTER:WorkflowModelProfile.ADAPTER;
            var owner = new RoleConfigurationService.OwnerRef("WORKFLOW_NODE", admitted.node().id());
            var role = roles.freezeSelection(owner, WorkflowModelProfile.slot(adapter), definition.roleId(), definition.roleRevisionId());
            if (role.workInstructions()==null) throw new BadRequestException("WORKFLOW_ROLE_INSTRUCTIONS_REQUIRED", "此角色版本没有工作节点专业说明，请选择支持的版本");
            var attempt = nodes.begin(admitted.node(), admitted.owner().headRevision(), admitted.inputs(),
                    adapter, store.encoding().encode(role));
            roles.freezeOwner(new RoleConfigurationService.OwnerRef("WORKFLOW_ATTEMPT", attempt.id()), owner);
            if (writer) writers.admit(identity,attempt.id());
            store.create(attempt, admitted.owner(), directory, request.model());
            return actions.acknowledge(request.requestKey(), digest, "MODEL_START", id, key, attempt.id());
        });
    }
    static void requireModel(OpenCodeClient.OpenCodeModel model) {
        if(model==null || !identifier(model.providerId()) || !identifier(model.modelId())) throw new BadRequestException("WORKFLOW_MODEL_REQUIRED","请选择有效的模型");
    }
    private static boolean identifier(String value) { return value!=null && !value.isBlank() && value.length()<=160 && value.chars().noneMatch(Character::isISOControl); }
    private static void requireSupported(WorkflowGraph.Node node) {
        if (node.kind()!=WorkflowGraph.NodeKind.WORK || !Set.of(WorkflowModelProfile.MODULE,WorkflowModelProfile.WRITE_MODULE,WorkflowReviewContract.REQUIREMENT,WorkflowReviewContract.RISK,WorkflowSourceDesign.AUTHOR,WorkflowSourceDesign.REVIEW,WorkflowTestDesign.MODULE,WorkflowTestWrite.MODULE,WorkflowTestReview.MODULE,WorkflowDocumentReview.AUTHOR,WorkflowDocumentReview.REVIEW,WorkflowKnowledgeBundle.MODULE,WorkflowHistoryAnalysis.REVIEW,WorkflowHistoryAnalysis.CONTRIBUTION,WorkflowSnapshotWork.ANALYZE,WorkflowSnapshotWork.REVIEW).contains(node.moduleId())
                || node.moduleVersion()!=1&&!(WorkflowTestReview.supports(node.moduleId())&&node.moduleVersion()==2)
                || node.completion()==null || !Set.of(WorkflowGraph.CompletionKind.DELIVERABLES,WorkflowGraph.CompletionKind.OUTCOME).contains(node.completion().kind())
                || node.outputs().stream().anyMatch(output -> output.kind()==WorkflowGraph.DataKind.DOCUMENT || output.kind()==WorkflowGraph.DataKind.PLAN && WorkflowModelProfile.writeModule(node.moduleId())
                        || output.kind()==WorkflowGraph.DataKind.CODE && !WorkflowModelProfile.writeModule(node.moduleId())))
            throw new BadRequestException("WORKFLOW_MODEL_ADAPTER_UNSUPPORTED", "此入口支持只读或文件工作及结构化交付；文档和程序验证使用对应工作模块；候选计划由只读节点提出");
        if(WorkflowReviewContract.reviewer(node.moduleId()))WorkflowReviewContract.requireNode(node);
        if(WorkflowSourceDesign.supports(node.moduleId()))try{WorkflowSourceDesign.require(node);}catch(IllegalArgumentException invalid){throw new BadRequestException("WORKFLOW_SOURCE_DESIGN_INVALID",invalid.getMessage());}
        if(WorkflowTestDesign.supports(node.moduleId()))try{WorkflowTestDesign.require(node);}catch(IllegalArgumentException invalid){throw new BadRequestException("WORKFLOW_TEST_DESIGN_INVALID",invalid.getMessage());}
        if(WorkflowTestWrite.supports(node.moduleId()))try{WorkflowTestWrite.require(node);}catch(IllegalArgumentException invalid){throw new BadRequestException("WORKFLOW_TEST_DESIGN_INVALID",invalid.getMessage());}
        if(WorkflowTestReview.supports(node.moduleId()))try{WorkflowTestReview.require(node);}catch(IllegalArgumentException invalid){throw new BadRequestException("WORKFLOW_TEST_REVIEW_INVALID",invalid.getMessage());}
        if(WorkflowDocumentReview.supports(node.moduleId()))try{WorkflowDocumentReview.require(node);}catch(IllegalArgumentException invalid){throw new BadRequestException("WORKFLOW_DOCUMENT_REVIEW_INVALID",invalid.getMessage());}
        if(WorkflowHistoryAnalysis.supports(node.moduleId()))try{WorkflowHistoryAnalysis.require(node);}catch(IllegalArgumentException invalid){throw new BadRequestException("WORKFLOW_HISTORY_ANALYSIS_INVALID",invalid.getMessage());}
        if(WorkflowSnapshotWork.supports(node.moduleId()))try{WorkflowSnapshotWork.require(node);}catch(IllegalArgumentException invalid){throw WorkflowSnapshotWorkStore.invalid(invalid.getMessage());}
        if(WorkflowKnowledgeBundle.supports(node.moduleId()))try{WorkflowKnowledgeBundle.require(node);}catch(IllegalArgumentException invalid){throw new BadRequestException("WORKFLOW_KNOWLEDGE_BUNDLE_INVALID",invalid.getMessage());}
        if(node.outputs().stream().filter(output->output.kind()==WorkflowGraph.DataKind.PLAN).count()>1)throw new BadRequestException("WORKFLOW_PLAN_CANDIDATE_INVALID","每个节点最多交付一份候选计划。");
        if (node.roleRevisionId()==null || node.roleRevisionId().isBlank())
            throw new BadRequestException("WORKFLOW_ROLE_VERSION_REQUIRED", "请为节点选择明确的角色版本");
    }
}
