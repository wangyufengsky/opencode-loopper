package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.TaskDecompositionRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Coordinates stable Decomposer candidate runs without owning model or Designer lifecycle state. */
@Component
final class DesignerDecompositionCandidateCoordinator {
    static final String CONTRACT_VERSION = "DECOMPOSITION_PLAN_V2";
    static final String WORKFLOW_STEP = "PLANNING";
    static final int MAX_ATTEMPTS = 5;

    private final MachineCandidateSubmission submissions;
    private final Optional<CandidateRuntimeBindingService> bindings;
    private final DesignerDecompositionLegacyCandidateAdapter legacyAdapter;

    @Autowired
    DesignerDecompositionCandidateCoordinator(MachineCandidateSubmission submissions,
                                              Optional<CandidateRuntimeBindingService> bindings,
                                              AiOutputExtractor extractor, ObjectMapper json) {
        this(submissions, bindings, new DesignerDecompositionLegacyCandidateAdapter(extractor, json));
    }

    DesignerDecompositionCandidateCoordinator(MachineCandidateSubmission submissions,
                                              Optional<CandidateRuntimeBindingService> bindings,
                                              DesignerDecompositionLegacyCandidateAdapter legacyAdapter) {
        this.submissions = submissions;
        this.bindings = bindings;
        this.legacyAdapter = legacyAdapter;
    }

    MachineCandidateSubmission.RunSnapshot open(
            TaskDecompositionRow owner, DesignRequirementRevisionRow revision,
            OpenCodeClient.OpenCodeSession remote,
            MachineCandidateSubmission.SubmissionChannel channel) {
        CandidateRuntimeBindingService.Binding binding = bindings
                .orElseThrow(() -> new ConflictException("CANDIDATE_RUNTIME_BINDING_UNAVAILABLE",
                        "候选运行时绑定服务不可用"))
                .bind(remote, channel);
        return submissions.open(new MachineCandidateSubmission.OpenCommand(
                runId(owner.id(), channel), owner.designerSessionId(),
                MachineCandidateSubmission.CandidateOwner.taskDecomposition(owner.id()),
                MachineCandidateKind.DECOMPOSITION_PLAN_V2, WORKFLOW_STEP, revision.revision(), owner.version(),
                channel, CONTRACT_VERSION, binding.runtimeGenerationId(), remote.id(), MAX_ATTEMPTS));
    }

    Optional<MachineCandidateSubmission.RunSnapshot> find(String ownerId) {
        Optional<MachineCandidateSubmission.RunSnapshot> legacy = submissions.find(runId(
                ownerId, MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY));
        return legacy.isPresent() ? legacy : submissions.find(runId(
                ownerId, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP));
    }

    Optional<MachineCandidateSubmission.SubmissionResult> terminal(String ownerId) {
        return find(ownerId).flatMap(run -> submissions.terminal(run.runId()));
    }

    MachineCandidateSubmission.SubmissionResult submitLegacy(String ownerId, String output) {
        MachineCandidateSubmission.RunSnapshot run = submissions.find(runId(
                        ownerId, MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY))
                .orElseThrow(() -> new ConflictException("DECOMPOSER_LEGACY_RUN_MISSING",
                        "进程内兼容候选运行不存在"));
        return submissions.submit(new MachineCandidateSubmission.SubmitCommand(
                run.runId(), run.externalSessionId() + ":" + (run.attemptsUsed() + 1),
                legacyAdapter.candidateJson(output), run.version(),
                MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY));
    }

    MachineCandidateSubmission.RunSnapshot close(String ownerId,
                                                   MachineCandidateSubmission.SubmissionChannel channel) {
        MachineCandidateSubmission.RunSnapshot run = submissions.find(runId(ownerId, channel))
                .orElseThrow(() -> new ConflictException("DECOMPOSER_CANDIDATE_RUN_MISSING",
                        "任务拆解候选运行不存在"));
        return submissions.close(new MachineCandidateSubmission.CloseCommand(run.runId(), run.version()));
    }

    String runId(String ownerId, MachineCandidateSubmission.SubmissionChannel channel) {
        if (ownerId == null || ownerId.isBlank() || channel == null) {
            throw new IllegalArgumentException("Candidate owner and channel are required");
        }
        return UUID.nameUUIDFromBytes(("decomposition-candidate:" + ownerId + ":" + channel.name())
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
}
