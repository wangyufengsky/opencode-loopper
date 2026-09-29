package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.WorkflowSnapshotReuseMapper;
import io.opencode.loopper.template.*;
import io.opencode.loopper.workflow.*;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** A saved reuse receipt substitutes provenance, never another role's MCP reading receipts. */
@Component
public final class WorkflowSnapshotReuseProof {
    private final WorkflowSnapshotReuseMapper mapper;
    private final WorkflowEncoding encoding;
    public WorkflowSnapshotReuseProof(WorkflowSnapshotReuseMapper mapper,WorkflowEncoding encoding){this.mapper=mapper;this.encoding=encoding;}
    public Optional<WorkflowSnapshotWork.Reuse> require(String requirement,String attempt,WorkflowSnapshotWork.Input input,String candidate) {
        var saved=mapper.receipt(attempt);if(saved.isEmpty())return Optional.empty();var receipt=saved.get();
        var context=mapper.context(attempt).orElseThrow(WorkflowCommands::conflict);
        var source=mapper.sourceById(requirement,receipt.fingerprint(),receipt.sourceAttemptId()).orElseThrow(WorkflowCommands::conflict);
        if(!context.inputSha256().equals(WorkflowEncoding.hash(encoding.encode(input)))||!context.fingerprint().equals(receipt.fingerprint())||!source.requirementId().equals(receipt.sourceRequirementId())
            ||!WorkflowEncoding.hash(source.contentJson()).equals(source.sha256())||!source.sha256().equals(receipt.sourceDeliverySha256())
            ||!WorkflowEncoding.hash(source.inputJson()).equals(source.inputSha256())||!WorkflowEncoding.hash(receipt.claimsJson()).equals(receipt.claimsSha256())
            ||!candidate.equals(receipt.claimsJson()))throw WorkflowCommands.conflict();
        var result=encoding.decode(encoding.encode(encoding.decode(source.contentJson(),WorkflowDelivery.class).outputs().get("analysis").content()),WorkflowSnapshotWork.Analysis.class);
        var original=encoding.decode(source.inputJson(),WorkflowSnapshotWork.Input.class);
        if(result.version()!=1||!WorkflowSnapshotWork.ANALYSIS_TYPE.equals(result.type())||result.reuse()!=null||!result.source().equals(original.source()))throw WorkflowCommands.conflict();
        var mapped=SnapshotReviewReusePolicy.remap(result.claims(),original.batch(),input.batch()).orElseThrow(WorkflowCommands::conflict);
        if(!encoding.encode(mapped).equals(candidate))throw WorkflowCommands.conflict();
        return Optional.of(new WorkflowSnapshotWork.Reuse(receipt.sourceRequirementId(),receipt.sourceAttemptId(),receipt.sourceTitle(),receipt.sourceNodeTitle(),receipt.sourceDeliverySha256()));
    }
}
