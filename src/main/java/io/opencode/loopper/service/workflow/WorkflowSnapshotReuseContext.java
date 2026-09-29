package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.WorkflowModelProfile;
import io.opencode.loopper.service.roles.*;
import io.opencode.loopper.template.*;
import io.opencode.loopper.workflow.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Only actually authorized mutable evidence excludes reuse; a denied role cannot use a stored source list. */
@Component
public final class WorkflowSnapshotReuseContext {
    private final RoleConfigurationService roles;
    private final WorkflowKnowledgeMapper knowledge;
    private final WorkflowPlanMapper plans;
    private final WorkflowSnapshotReuseMapper reuse;
    private final WorkflowEncoding encoding;
    public WorkflowSnapshotReuseContext(RoleConfigurationService roles,WorkflowKnowledgeMapper knowledge,WorkflowPlanMapper plans,WorkflowSnapshotReuseMapper reuse,WorkflowEncoding encoding){this.roles=roles;this.knowledge=knowledge;this.plans=plans;this.reuse=reuse;this.encoding=encoding;}
    public Optional<String> fingerprint(WorkflowModelMapper.Launch model,WorkflowExecutionRows.Attempt attempt,WorkflowGraph.Node node,
                                        WorkflowDelivery.Inputs inputs,WorkflowSnapshotWork.Input prepared,SnapshotReview.Snapshot snapshot) {
        if(!WorkflowSnapshotWork.ANALYZE.equals(node.moduleId())||node.moduleVersion()!=1||attempt.ordinal()!=1
            ||!WorkflowSnapshotWork.reuseAllowed(node)||reuse.priorFailure(model.requirementId())||!SnapshotReviewReusePolicy.closed(prepared.batch())
            ||snapshot.scopeIdentity()==null||snapshot.scopeIdentity().isBlank()||snapshot.targetTree()==null)return Optional.empty();
        var owner=new RoleConfigurationService.OwnerRef("WORKFLOW_ATTEMPT",attempt.id());
        var role=roles.resolveFrozen(owner,WorkflowModelProfile.slot(attempt.adapterKey())).orElse(null);
        if(role==null||role.workInstructions()==null||!encoding.encode(role).equals(attempt.roleSnapshotJson()))return Optional.empty();
        var capabilities=RoleCapabilities.effective(role.adapterProfile(),role.capabilities());
        if(capabilities.stream().anyMatch(c->c!=RoleCapabilities.Capability.PROJECT_KNOWLEDGE))return Optional.empty();
        var binding=knowledge.binding("WORKFLOW_ATTEMPT",attempt.id());
        if(capabilities.contains(RoleCapabilities.Capability.PROJECT_KNOWLEDGE)&&(binding==null||!"[]".equals(binding.sourcesJson())))return Optional.empty();
        var parameters=new TreeMap<>(node.parameters());parameters.remove("snapshotBatchOrdinal");parameters.remove("snapshotReuse");
        var extra=inputs.values().stream().filter(i->!i.name().equals("source")).sorted(Comparator.comparing(WorkflowDelivery.Input::name)).map(i->List.of(i.name(),i.kind(),i.content())).toList();
        var project=plans.find(model.requirementId()).orElseThrow(WorkflowCommands::conflict).projectId();
        return Optional.of(WorkflowEncoding.hash(encoding.encode(Arrays.asList("WORKFLOW_SNAPSHOT_REUSE_V1",project,model.directory(),model.modelJson(),attempt.roleSnapshotJson(),
            snapshot.scopeIdentity(),snapshot.targetTree(),snapshot.baselineTree(),prepared.batch().policy(),SnapshotReviewReusePolicy.materials(prepared.batch()),
            inputs.objective(),node.title(),node.task(),node.outputs(),node.outcomes(),node.completion(),parameters,extra))));
    }
}
