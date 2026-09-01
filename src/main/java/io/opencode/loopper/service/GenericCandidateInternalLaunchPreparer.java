package io.opencode.loopper.service;

import io.opencode.loopper.domain.GenericCandidateInternalLaunchState;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Freezes stable launch/run ids and a complete local V57 create plan before any remote I/O. */
@Service
final class GenericCandidateInternalLaunchPreparer {
    private final OpenCodeClient openCode;
    private final GenericCandidateInternalLaunchStore store;
    private final GenericCandidateInternalLaunchPlanCodec plans;
    private final AcceptanceCandidateCreationCredentialSource credentials;

    GenericCandidateInternalLaunchPreparer(OpenCodeClient openCode,
            GenericCandidateInternalLaunchStore store,
            GenericCandidateInternalLaunchPlanCodec plans,
            AcceptanceCandidateCreationCredentialSource credentials) {
        this.openCode = openCode;
        this.store = store;
        this.plans = plans;
        this.credentials = credentials;
    }

    Prepared prepare(PrepareCommand command) {
        validate(command);
        GenericCandidateInternalLaunchRow existing = store.findActive(
                command.owner(), command.candidateKind().name()).orElse(null);
        if (existing != null) return replay(command, existing);

        String launchId = launchId(command);
        String runId = candidateRunId(command);
        String baseTitle = baseTitle(command.candidateKind(), runId, launchId);
        String credential = credentials.create();
        OpenCodeClient.SessionCreationPlan plan = openCode.prepareCandidateSessionCreationLocally(
                command.projectRoot(), baseTitle, command.model(), command.profile(), credential);
        plans.validatePreparedPlan(baseTitle, command.model(), command.profile(), plan);
        GenericCandidateInternalLaunchRow requested = row(command, launchId, runId, plan);
        if (!plans.decode(requested).equals(plan)) throw invalidPlan();
        try {
            GenericCandidateInternalLaunchRow stored = store.insert(requested);
            return new Prepared(stored, plans.decode(stored));
        } catch (ConflictException raced) {
            if (!"GENERIC_CANDIDATE_INTERNAL_LAUNCH_CREATE_CONFLICT".equals(raced.code())) throw raced;
            GenericCandidateInternalLaunchRow stored = store.findActive(
                    command.owner(), command.candidateKind().name()).orElseThrow(() -> raced);
            return replay(command, stored);
        }
    }

    static String launchId(PrepareCommand command) { return deterministic("generic-v1-launch", command); }
    static String candidateRunId(PrepareCommand command) { return deterministic("generic-v1-run", command); }

    static String baseTitle(MachineCandidateKind kind, String candidateRunId, String launchId) {
        if (kind == null || blank(candidateRunId) || blank(launchId)) {
            throw new IllegalArgumentException("Complete generic launch title identity is required");
        }
        return "OpenCode Loopper " + kind.name() + " [candidate-run:" + candidateRunId
                + "] [candidate_launch_id:" + launchId + "]";
    }

    private Prepared replay(PrepareCommand command, GenericCandidateInternalLaunchRow row) {
        if (!row.id().equals(launchId(command)) || !row.candidateRunId().equals(candidateRunId(command))
                || !row.candidateKind().equals(command.candidateKind().name())
                || !row.workflowStep().equals(command.candidateKind().name())
                || !row.contractVersion().equals(command.candidateKind().name())
                || row.sourceRevision() != command.sourceRevision()
                || row.preparedOwnerVersion() != command.ownerVersion()
                || !row.ownerType().equals(command.owner().type().name())
                || !row.ownerId().equals(command.owner().id())
                || !scopeMatches(row, command.scope())
                || !row.canonicalDirectory().equals(canonical(command.projectRoot()).toString())
                || !row.profile().equals(command.profile().name())
                || !Objects.equals(row.modelProviderId(), command.model() == null ? null : command.model().providerId())
                || !Objects.equals(row.modelId(), command.model() == null ? null : command.model().modelId())
                || !Objects.equals(row.thinking(), command.model() == null ? null : command.model().thinking())) {
            throw new ConflictException("GENERIC_CANDIDATE_INTERNAL_LAUNCH_REPLAY_MISMATCH",
                    "通用候选 internal launch 的重放参数与冻结事实不一致");
        }
        return new Prepared(row, plans.decode(row));
    }

    private GenericCandidateInternalLaunchRow row(PrepareCommand command, String launchId, String runId,
            OpenCodeClient.SessionCreationPlan plan) {
        String at = Instant.now().toString();
        var scope = command.scope();
        var owner = command.owner();
        return new GenericCandidateInternalLaunchRow(
                launchId, runId, command.candidateKind().name(),
                scope.type() == MachineCandidateSubmission.CandidateScopeType.DESIGNER_SESSION ? scope.id() : null,
                scope.type() == MachineCandidateSubmission.CandidateScopeType.TASK ? scope.id() : null,
                scope.type() == MachineCandidateSubmission.CandidateScopeType.PROJECT ? scope.id() : null,
                owner.type().name(), owner.id(),
                owner.type() == MachineCandidateSubmission.CandidateOwnerType.ANALYSIS_REPORT ? owner.id() : null,
                owner.type() == MachineCandidateSubmission.CandidateOwnerType.PROJECT_CONVENTION_DRAFT
                        ? owner.id() : null,
                owner.type() == MachineCandidateSubmission.CandidateOwnerType.JUDGE_RUN ? owner.id() : null,
                command.candidateKind().name(), command.sourceRevision(), command.candidateKind().name(),
                command.candidateKind().maximumAttempts(), GenericCandidateInternalLaunchState.PREPARED.name(),
                command.ownerVersion(), null, null, plan.exactTitle(), plan.canonicalDirectory().toString(),
                plan.runtimeGenerationId(), plan.managed(), plan.internalMcpServer(), plan.endpointFingerprint(),
                plan.model() == null ? null : plan.model().providerId(),
                plan.model() == null ? null : plan.model().modelId(),
                plan.model() == null ? null : plan.model().thinking(), plan.profile().name(),
                plans.encodePermissionPolicy(plan), plan.permissionPolicyDigest(), plan.createRequestSha256(),
                plan.creationCredential(), "LOCAL_REQUEST_ATTESTED", null, null, null, 0,
                false, null, null, null, null, null, null, null, null, at, at, 0);
    }

    private static void validate(PrepareCommand command) {
        if (command == null || command.candidateKind() == null || command.scope() == null
                || command.owner() == null || command.projectRoot() == null || command.profile() == null
                || command.sourceRevision() < 0 || command.ownerVersion() < 0
                || command.profile() == OpenCodeClient.SessionProfile.IMPLEMENTATION) {
            throw new IllegalArgumentException("Complete read-only generic candidate launch command is required");
        }
        MachineCandidateProtocolPolicy.Contract policy = MachineCandidateProtocolPolicy.contract(
                command.candidateKind());
        if (CandidatePromptRunContract.expectedProtocol(command.candidateKind())
                    != CandidateLaunchRef.Protocol.GENERIC_V1
                || policy.scopeType() != command.scope().type()
                || policy.ownerType() != command.owner().type()) {
            throw new IllegalArgumentException("Candidate kind, scope, and owner do not match V57");
        }
    }

    private static boolean scopeMatches(GenericCandidateInternalLaunchRow row,
            MachineCandidateSubmission.CandidateScope scope) {
        return switch (scope.type()) {
            case DESIGNER_SESSION -> scope.id().equals(row.designerSessionId())
                    && row.taskId() == null && row.projectId() == null;
            case TASK -> scope.id().equals(row.taskId())
                    && row.designerSessionId() == null && row.projectId() == null;
            case PROJECT -> scope.id().equals(row.projectId())
                    && row.designerSessionId() == null && row.taskId() == null;
        };
    }

    private static String deterministic(String domain, PrepareCommand command) {
        Objects.requireNonNull(command, "Generic candidate launch command is required");
        String identity = domain + "\n" + command.candidateKind() + "\n" + command.scope().type()
                + "\n" + command.scope().id() + "\n" + command.owner().type() + "\n"
                + command.owner().id() + "\n" + command.sourceRevision() + "\n" + command.ownerVersion();
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static Path canonical(Path value) {
        try { return value.toRealPath(); }
        catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("Generic candidate project root must exist", invalid);
        }
    }

    private static ConflictException invalidPlan() {
        return new ConflictException("GENERIC_CANDIDATE_INTERNAL_LAUNCH_PLAN_INVALID",
                "通用候选 internal launch 的本地 create plan 无法无损冻结");
    }

    record PrepareCommand(
            MachineCandidateKind candidateKind,
            MachineCandidateSubmission.CandidateScope scope,
            MachineCandidateSubmission.CandidateOwnerRef owner,
            long sourceRevision, long ownerVersion, Path projectRoot,
            OpenCodeClient.OpenCodeModel model, OpenCodeClient.SessionProfile profile) { }

    record Prepared(GenericCandidateInternalLaunchRow row,
                    OpenCodeClient.SessionCreationPlan plan) { }
}
