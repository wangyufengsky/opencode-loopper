package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.template.SourceManifest;
import io.opencode.loopper.workflow.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Authenticates program-produced native test facts and their exact frozen source lineage. */
@Component
public final class WorkflowTestInputs {
    private final WorkflowExecutionMapper nodes;
    private final WorkflowPlanMapper plans;
    private final WorkflowSourceRecords sources;
    private final WorkflowInputSnapshots snapshots;
    private final WorkflowEncoding encoding;
    public WorkflowTestInputs(WorkflowExecutionMapper nodes,WorkflowPlanMapper plans,WorkflowSourceRecords sources,WorkflowInputSnapshots snapshots,WorkflowEncoding encoding){this.nodes=nodes;this.plans=plans;this.sources=sources;this.snapshots=snapshots;this.encoding=encoding;}
    public record Context(WorkflowDelivery.Input source,WorkflowDelivery.Input profile,WorkflowTestProfile.Frozen frozen,SourceManifest manifest){ }
    public Context require(WorkflowDelivery.Inputs inputs) {
        var source=named(inputs,"source");var profile=named(inputs,"profile");
        if(source.kind()!=WorkflowGraph.DataKind.DOCUMENT||!"NODE".equals(source.source())||source.attemptId()==null
                ||profile.kind()!=WorkflowGraph.DataKind.JSON||!"NODE".equals(profile.source())||profile.attemptId()==null||!"profile".equals(profile.outputName()))throw invalid();
        var producer=nodes.attempt(profile.attemptId()).orElseThrow(WorkflowTestInputs::invalid);
        var node=nodes.node(producer.nodeRunId()).orElseThrow(WorkflowTestInputs::invalid);
        if(!node.id().equals(profile.sourceId())||!node.requirementId().equals(inputs.requirementId())||!producer.state().equals("SUCCEEDED")
                ||!producer.adapterKey().equals(WorkflowTestProfile.ADAPTER)||nodes.stop(producer.id()).isEmpty()
                ||!WorkflowEncoding.hash(node.definitionJson()).equals(node.definitionSha256()))throw invalid();
        try{WorkflowTestProfile.require(encoding.decode(node.definitionJson(),WorkflowGraph.Node.class));}
        catch(IllegalArgumentException malformed){throw invalid();}
        var delivery=nodes.delivery(producer.id()).orElseThrow(WorkflowTestInputs::invalid);
        if(!delivery.sha256().equals(profile.sha256())||!WorkflowEncoding.hash(delivery.contentJson()).equals(delivery.sha256()))throw invalid();
        var output=encoding.decode(delivery.contentJson(),WorkflowDelivery.class).outputs().get("profile");
        if(output==null||output.kind()!=WorkflowGraph.DataKind.JSON||!output.content().equals(profile.content()))throw invalid();
        var original=named(snapshots.snapshot(producer),"source");
        if(!Objects.equals(source.attemptId(),original.attemptId())||!Objects.equals(source.sourceId(),original.sourceId())
                ||!Objects.equals(source.outputName(),original.outputName())||!Objects.equals(source.sha256(),original.sha256())||!Objects.equals(source.content(),original.content()))throw invalid();
        var frozen=encoding.decode(encoding.encode(profile.content()),WorkflowTestProfile.Frozen.class);
        var reference=encoding.decode(encoding.encode(source.content()),WorkflowSourceSnapshot.Reference.class);
        if(frozen.version()!=1||!WorkflowTestProfile.TYPE.equals(frozen.type())||!source.attemptId().equals(frozen.sourceAttemptId())||!reference.equals(frozen.source())||frozen.profile()==null)throw invalid();
        String project=plans.find(inputs.requirementId()).orElseThrow(WorkflowTestInputs::invalid).projectId();
        var manifest=sources.manifest(project,inputs.requirementId(),source.attemptId(),reference,WorkflowSourceSnapshot.Purpose.UNIT_TEST);
        if(!manifest.sha256().equals(frozen.profile().manifestSha256()))throw invalid();
        return new Context(source,profile,frozen,manifest);
    }
    public WorkflowTestDesign.Frozen design(WorkflowDelivery.Inputs inputs,Context context) {
        return design(inputs,named(inputs,"design"),context);
    }
    public WorkflowTestDesign.Frozen design(WorkflowDelivery.Inputs inputs,WorkflowDelivery.Input input,Context context) {
        if(input.kind()!=WorkflowGraph.DataKind.JSON||!"NODE".equals(input.source())||input.attemptId()==null||!"design".equals(input.outputName()))throw invalid();
        var attempt=nodes.attempt(input.attemptId()).orElseThrow(WorkflowTestInputs::invalid);var node=nodes.node(attempt.nodeRunId()).orElseThrow(WorkflowTestInputs::invalid);
        if(!node.id().equals(input.sourceId())||!node.requirementId().equals(inputs.requirementId())||!attempt.state().equals("SUCCEEDED")||nodes.stop(attempt.id()).isEmpty()
                ||!attempt.adapterKey().equals(io.opencode.loopper.runtime.WorkflowModelProfile.ADAPTER)||!WorkflowEncoding.hash(node.definitionJson()).equals(node.definitionSha256())
                ||!WorkflowTestDesign.supports(encoding.decode(node.definitionJson(),WorkflowGraph.Node.class).moduleId()))throw invalid();
        var saved=nodes.delivery(attempt.id()).orElseThrow(WorkflowTestInputs::invalid);
        if(!saved.sha256().equals(input.sha256())||!WorkflowEncoding.hash(saved.contentJson()).equals(saved.sha256()))throw invalid();
        var output=encoding.decode(saved.contentJson(),WorkflowDelivery.class).outputs().get("design");
        if(output==null||output.kind()!=WorkflowGraph.DataKind.JSON||!output.content().equals(input.content()))throw invalid();
        var design=encoding.decode(encoding.encode(input.content()),WorkflowTestDesign.Frozen.class);
        var original=snapshots.materialize(snapshots.snapshot(attempt));var source=named(original,"source");var profile=named(original,"profile");
        if(!source.equals(context.source())||!profile.equals(context.profile())||design.version()!=1||!WorkflowTestDesign.TYPE.equals(design.type())
                ||!design.sourceAttemptId().equals(context.source().attemptId())||!design.source().equals(context.frozen().source())
                ||!design.profileAttemptId().equals(context.profile().attemptId())||!design.profileSha256().equals(WorkflowEncoding.hash(encoding.encode(context.profile().content()))))throw invalid();
        return design;
    }
    private static WorkflowDelivery.Input named(WorkflowDelivery.Inputs inputs,String name){return inputs.values().stream().filter(v->name.equals(v.name())).findFirst().orElseThrow(WorkflowTestInputs::invalid);}
    private static BadRequestException invalid(){return new BadRequestException("WORKFLOW_TEST_INPUT_MISMATCH","测试配置必须来自本需求已完成的程序识别节点，并与所选冻结源码完全一致，请重新核对输入绑定。");}
}
