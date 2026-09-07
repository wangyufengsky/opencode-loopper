package io.opencode.loopper.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import tools.jackson.databind.ObjectMapper;

/** Production submission/policy/writer with an isolated in-memory storage adapter, not a runtime integration proof. */
final class PackageDesignLunaSession {
    private final PersistentMachineCandidateSubmission service;
    private final AtomicReference<CandidateSubmissionRunRow> run;
    private final AtomicReference<PackageDesignAcceptedResultRow> accepted = new AtomicReference<>();

    PackageDesignLunaSession(ObjectMapper json, PackageDesignCompilation.Input input) {
        var mapper = mock(LoopperMapper.class);
        var lifecycle = mock(LifecycleTransitionService.class);
        var compiler = new DeterministicPackageDesignCompilation(json);
        PackageDesignCompilationInputLoader inputs = ignored -> input;
        run = new AtomicReference<>(new CandidateSubmissionRunRow("qualification", "qualification", null, null,
                "DESIGN_WORK_PACKAGE", "package-row", "PACKAGE_DESIGN_V1", input.behaviorContract() == null ? input.semanticContractVersion() : PackageBehaviorRuns.WORKFLOW, 1, 0,
                "INTERNAL_MCP", input.semanticContractVersion(), "offline", "offline-session", "OPEN", 3, 0, null,
                "now", "now", 0, null, 4));
        List<CandidateSubmissionAttemptRow> attempts = new ArrayList<>();
        when(mapper.findCandidateSubmissionRun("qualification")).thenAnswer(call -> Optional.of(run.get()));
        when(mapper.findCandidateSubmissionAttemptByKey(eq("qualification"), anyString())).thenAnswer(call ->
                attempts.stream().filter(row -> row.idempotencyKey().equals(call.getArgument(1))).findFirst());
        when(mapper.recentCandidateSubmissionAttempts("qualification")).thenAnswer(call -> attempts.reversed().stream().limit(3).toList());
        when(mapper.insertCandidateSubmissionAttempt(any())).thenAnswer(call -> { attempts.add(call.getArgument(0)); return 1; });
        when(mapper.updateCandidateSubmissionRun(any())).thenAnswer(call -> {
            CandidateSubmissionRunRow next = call.getArgument(0);
            run.set(new CandidateSubmissionRunRow(next.id(), next.designerSessionId(), next.taskId(), next.projectId(),
                    next.ownerType(), next.ownerId(), next.candidateKind(), next.workflowStep(), next.sourceRevision(),
                    next.ownerVersion(), next.submissionChannel(), next.contractVersion(), next.runtimeGenerationId(),
                    next.externalSessionId(), next.state(), next.maxAttempts(), next.attemptsUsed(), next.terminalAttemptId(),
                    next.createdAt(), next.updatedAt(), next.version() + 1, next.closeReason(), next.correctionLimit()));
            return 1;
        });
        when(mapper.insertPackageDesignAcceptedResult(any())).thenAnswer(call -> { accepted.set(call.getArgument(0)); return 1; });
        doAnswer(call -> { ((IntSupplier) call.getArgument(0)).getAsInt(); return null; })
                .when(lifecycle).mutateWithoutTransition(any(), any());
        doAnswer(call -> { ((IntSupplier) call.getArgument(5)).getAsInt(); return null; })
                .when(lifecycle).transition(any(), anyString(), anyString(), anyString(), anyMap(), any(), any());
        if (input.repositoryEvidence() != null) {
            String snapshot = json.writeValueAsString(input.repositoryEvidence());
            when(mapper.findPackageDesignEvidence("qualification")).thenReturn(Optional.of(new PackageDesignEvidenceRow(
                    "qualification", PackageDesignGapAssessment.VERSION, input.repositoryEvidence().requirementSha256(), snapshot,
                    PackageDesignEvidencePreparation.hash(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "now")));
        }
        service = new PersistentMachineCandidateSubmission(mapper, lifecycle, json,
                List.of(new PackageDesignCandidatePolicy(inputs, compiler, mapper)),
                List.of(new PackageDesignAcceptedCandidateWriter(mapper, inputs, compiler)), List.of());
    }

    MachineCandidateSubmission.SubmissionResult submit(String candidate) {
        return service.submit(new MachineCandidateSubmission.SubmitCommand("qualification", "offline-" + run.get().version(),
                candidate, run.get().version(), MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                MachineCandidateSubmission.SubmissionSchema.ROLE_SPECIFIC_V2));
    }

    PackageDesignAcceptedResultRow accepted() { return accepted.get(); }
}
