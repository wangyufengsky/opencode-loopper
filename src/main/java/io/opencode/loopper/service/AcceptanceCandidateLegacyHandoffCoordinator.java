package io.opencode.loopper.service;

import io.opencode.loopper.domain.AcceptanceCandidateHandoffState;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.AcceptanceCandidateLegacyHandoffRow;
import io.opencode.loopper.persistence.DesignAcceptancePlanningRow;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Advances one durable unopened-fallback saga step without holding a database transaction over I/O. */
@Component
final class AcceptanceCandidateLegacyHandoffCoordinator {
    private final AcceptanceCandidateLegacyHandoffService handoffs;
    private final AcceptanceCandidateHandoffCleanupLedger cleanupLedger;
    private final DesignerAcceptanceCandidateOrchestrator candidates;
    private final DesignerModelPromptTransport prompts;
    private final ProjectService projects;
    private final OpenCodeClient openCode;
    private final Duration claimTtl;
    private final SecureRandom random = new SecureRandom();

    AcceptanceCandidateLegacyHandoffCoordinator(AcceptanceCandidateLegacyHandoffService handoffs,
            AcceptanceCandidateHandoffCleanupLedger cleanupLedger,
            DesignerAcceptanceCandidateOrchestrator candidates, OpenCodeClient openCode,
            DesignerAttachmentContext attachments, tools.jackson.databind.ObjectMapper json,
            ProjectService projects, LoopperProperties properties) {
        this.handoffs = handoffs;
        this.cleanupLedger = cleanupLedger;
        this.candidates = candidates;
        this.prompts = new DesignerModelPromptTransport(openCode, attachments, json);
        this.projects = projects;
        this.openCode = openCode;
        this.claimTtl = claimTtl(properties);
    }

    boolean exists(String compilationId) { return handoffs.find(compilationId).isPresent(); }

    boolean requiresAdvance(String compilationId) {
        return handoffs.find(compilationId).map(row -> !java.util.Set.of(
                AcceptanceCandidateHandoffState.HANDED_OFF.name(),
                AcceptanceCandidateHandoffState.SETTLED.name(),
                AcceptanceCandidateHandoffState.FAILED_STOPPED.name(),
                AcceptanceCandidateHandoffState.CANCELLED.name(),
                AcceptanceCandidateHandoffState.STALE.name()).contains(row.state())).orElse(false);
    }

    java.util.Optional<Terminal> terminal(String compilationId) {
        return handoffs.find(compilationId).flatMap(row -> switch (
                AcceptanceCandidateHandoffState.valueOf(row.state())) {
            case FAILED_STOPPED -> java.util.Optional.of(new Terminal(
                    row.state(), row.lastErrorCode(), row.lastErrorDetail()));
            case SETTLED, CANCELLED, STALE -> java.util.Optional.of(new Terminal(row.state(), null, null));
            default -> java.util.Optional.empty();
        });
    }

    CancellationRecovery reconcileDesignerCancellation(String designerSessionId) {
        int stopped = 0;
        int failed = 0;
        LinkedHashMap<String, String> proofs = new LinkedHashMap<>();
        for (AcceptanceCandidateLegacyHandoffRow row : handoffs.activeForDesigner(designerSessionId)) {
            CancellationRecovery recovered = recoverCancellation(row);
            stopped += recovered.stoppedSessions();
            failed += recovered.failedSessions();
            proofs.putAll(recovered.proofs());
        }
        return new CancellationRecovery(failed == 0, stopped, failed, Map.copyOf(proofs));
    }

    private CancellationRecovery recoverCancellation(AcceptanceCandidateLegacyHandoffRow row) {
        if (!AcceptanceCandidateHandoffState.STOPPING_LEGACY.name().equals(row.state())
                || !row.createDispatchAttempted()) return CancellationRecovery.ready(Map.of(), 0);
        if (active(row.promptClaimExpiresAt())) return CancellationRecovery.pending();
        AcceptanceCandidateLegacyHandoffService.Claim claim = handoffs.claimCreate(
                row.id(), UUID.randomUUID().toString(), Instant.now(), claimTtl);
        if (!claim.acquired()) return CancellationRecovery.pending();
        try {
            var cleanup = handoffs.cleanupRemotes(row.id());
            if (!cleanup.isEmpty()) return cleanupCancellationMatches(row, claim, cleanup);
            if (row.legacyExternalSessionId() != null) return stopRecoveredSuccessor(row, claim);
            OpenCodeClient.SessionLookup lookup = openCode.findSessionsByExactTitle(handoffs.creationPlan(row));
            if (!lookup.supported() || lookup.matches().isEmpty()) {
                return unresolvedCancellation(row, "Designer 已停止，但创建请求结果尚未精确回查到远端");
            }
            if (lookup.matches().size() == 1) {
                OpenCodeClient.SessionAttestation recovered = lookup.matches().getFirst();
                handoffs.validateCancellationMatch(row.id(), recovered, claim);
                candidates.bindLegacy(recovered);
                AcceptanceCandidateLegacyHandoffRow persisted =
                        handoffs.recordLegacyCreated(row.id(), recovered, claim);
                return stopRecoveredSuccessor(persisted, claim);
            }
            lookup.matches().forEach(match -> handoffs.validateCancellationMatch(row.id(), match, claim));
            lookup.matches().forEach(candidates::bindLegacy);
            handoffs.registerAmbiguity(row.id(), lookup.matches().stream().map(this::identity).toList());
            return cleanupCancellationMatches(row, claim, handoffs.cleanupRemotes(row.id()));
        } catch (RuntimeException failure) {
            try {
                handoffs.recordFailure(row.id(), "LEGACY_CREATE", code(failure), failure.getMessage());
                handoffs.fenceCreate(row.id());
            } catch (RuntimeException ignored) { }
            return CancellationRecovery.pending();
        }
    }

    private CancellationRecovery unresolvedCancellation(
            AcceptanceCandidateLegacyHandoffRow row, String detail) {
        handoffs.recordFailure(row.id(), "LEGACY_CREATE",
                "OPENCODE_SESSION_CREATION_RESULT_UNKNOWN", detail);
        handoffs.fenceCreate(row.id());
        return CancellationRecovery.pending();
    }

    private CancellationRecovery stopRecoveredSuccessor(
            AcceptanceCandidateLegacyHandoffRow row, AcceptanceCandidateLegacyHandoffService.Claim claim) {
        handoffs.requireCancellationCreateClaim(row.id(), claim);
        DesignerAcceptanceCandidateOrchestrator.StopResult stopped = candidates.stopUnopened(
                successorRemote(row, row.legacyExternalSessionId(), row.legacyRuntimeGenerationId()));
        if (!stopped.confirmed()) {
            handoffs.recordFailure(row.id(), "LEGACY_STOP",
                    "OPENCODE_ACCEPTANCE_LEGACY_STOP_UNCONFIRMED", stopped.detail());
            handoffs.fenceCreate(row.id());
            return CancellationRecovery.pending();
        }
        handoffs.completeLegacyCleanup(row.id(), stopped.proof());
        return CancellationRecovery.ready(Map.of(row.legacyExternalSessionId(), stopped.proof()), 1);
    }

    private CancellationRecovery cleanupCancellationMatches(AcceptanceCandidateLegacyHandoffRow row,
            AcceptanceCandidateLegacyHandoffService.Claim claim,
            java.util.List<io.opencode.loopper.persistence.AcceptanceCandidateHandoffCleanupRemoteRow> remotes) {
        int stoppedCount = 0;
        int failedCount = 0;
        LinkedHashMap<String, String> proofs = new LinkedHashMap<>();
        for (var remote : remotes) {
            if (CandidateSessionTerminationProof.persisted(remote.terminationProof())) {
                proofs.put(remote.externalSessionId(), remote.terminationProof());
                continue;
            }
            handoffs.requireCancellationCreateClaim(row.id(), claim);
            AcceptanceCandidateHandoffCleanupLedger.StopClaim stopClaim = cleanupLedger.claimStop(
                    row.id(), remote.externalSessionId(), UUID.randomUUID().toString(), Instant.now(), claimTtl);
            if (!stopClaim.acquired()) {
                failedCount++;
                continue;
            }
            DesignerAcceptanceCandidateOrchestrator.StopResult result = candidates.stopUnopened(
                    successorRemote(row, remote.externalSessionId(), remote.runtimeGenerationId()));
            if (!result.confirmed()) {
                failedCount++;
                cleanupLedger.disconnected(row.id(), remote.externalSessionId(), stopClaim,
                        "OPENCODE_ACCEPTANCE_LEGACY_STOP_UNCONFIRMED", result.detail());
            } else {
                stoppedCount++;
                proofs.put(remote.externalSessionId(), result.proof());
                cleanupLedger.stopped(row.id(), remote.externalSessionId(), stopClaim, result.proof());
            }
        }
        if (failedCount == 0) {
            handoffs.completeRecoveredUnknownCancellation(row.id());
            return CancellationRecovery.ready(Map.copyOf(proofs), stoppedCount);
        }
        handoffs.recordFailure(row.id(), "LEGACY_STOP",
                "OPENCODE_ACCEPTANCE_LEGACY_STOP_UNCONFIRMED",
                "一个或多个精确回查到的兼容候选 Session 未确认停止");
        handoffs.fenceCreate(row.id());
        return new CancellationRecovery(false, stoppedCount, failedCount, Map.copyOf(proofs));
    }

    private OpenCodeClient.OpenCodeSession successorRemote(
            AcceptanceCandidateLegacyHandoffRow row, String remoteId, String generationId) {
        return new OpenCodeClient.OpenCodeSession(remoteId, Path.of(row.successorCanonicalDirectory()),
                row.successorManaged() ? generationId : null,
                row.successorManaged() ? row.successorInternalMcpServer() : null);
    }

    Result stopOldAndAdvance(Command command, OpenCodeClient.OpenCodeSession oldRemote) {
        AcceptanceCandidateLegacyHandoffRow row = ensureHandoff(command);
        DesignerAcceptanceCandidateOrchestrator.StopResult stopped = candidates.stopUnopened(oldRemote);
        if (!stopped.confirmed()) {
            handoffs.recordOldDisconnected(row.id(),
                    "OPENCODE_ACCEPTANCE_CANDIDATE_STOP_UNCONFIRMED", stopped.detail());
            return Result.pending();
        }
        handoffs.confirmOldStopped(row.id(), stopped.proof());
        return advance(command);
    }

    Result recoverProofAndAdvance(Command command, String proof) {
        AcceptanceCandidateLegacyHandoffRow row = ensureHandoff(command);
        handoffs.confirmOldStopped(row.id(), proof);
        return advance(command);
    }

    Result advance(Command command) {
        AcceptanceCandidateLegacyHandoffRow row = ensureHandoff(command);
        try {
            if (AcceptanceCandidateHandoffState.STOPPING_OLD.name().equals(row.state())) {
                DesignerAcceptanceCandidateOrchestrator.StopResult stopped =
                        candidates.stopUnopened(oldRemote(command, row));
                if (!stopped.confirmed()) {
                    handoffs.recordOldDisconnected(row.id(),
                            "OPENCODE_ACCEPTANCE_CANDIDATE_STOP_UNCONFIRMED", stopped.detail());
                    return Result.pending();
                }
                row = handoffs.confirmOldStopped(row.id(), stopped.proof());
            }
            if (AcceptanceCandidateHandoffState.OLD_STOPPED.name().equals(row.state())) {
                row = handoffs.beginCreating(row.id()).row();
            }
            if (AcceptanceCandidateHandoffState.CREATING_LEGACY.name().equals(row.state())) {
                row = ensureUniqueSession(command, row);
            }
            if (AcceptanceCandidateHandoffState.STOPPING_LEGACY.name().equals(row.state())
                    && row.legacyExternalSessionId() == null && row.createDispatchAttempted()) {
                row = ensureUniqueSession(command, row);
            }
            if (AcceptanceCandidateHandoffState.STOPPING_LEGACY.name().equals(row.state())) {
                if (active(row.createClaimExpiresAt()) || active(row.promptClaimExpiresAt())) return Result.pending();
                cleanup(row, command);
                return Result.pending();
            }
            if (AcceptanceCandidateHandoffState.LEGACY_CREATED.name().equals(row.state())) {
                OpenCodeClient.OpenCodeSession remote = remote(command, row);
                DesignerAcceptanceCandidateOrchestrator.Start start = handoffs.openLegacy(
                        row.id(), command.planning(), command.routing(), remote);
                row = handoffs.find(command.compilation().id()).orElseThrow();
                return prompt(command, row, remote, start.prompt());
            }
            if (AcceptanceCandidateHandoffState.LEGACY_OPENED.name().equals(row.state())
                    || AcceptanceCandidateHandoffState.PROMPTING.name().equals(row.state())) {
                OpenCodeClient.OpenCodeSession remote = remote(command, row);
                return prompt(command, row, remote,
                        candidates.legacyPrompt(command.planning(), command.routing(), null));
            }
            return AcceptanceCandidateHandoffState.HANDED_OFF.name().equals(row.state())
                    ? Result.handedOff(remote(command, row)) : Result.pending();
        } catch (RuntimeException failure) {
            AcceptanceCandidateLegacyHandoffRow latest = handoffs.find(command.compilation().id()).orElse(row);
            if (latest.legacyExternalSessionId() == null) {
                handoffs.recordFailure(latest.id(), phase(latest), code(failure), failure.getMessage());
                return Result.pending();
            }
            AcceptanceCandidateLegacyHandoffRow cleanup = handoffs.beginLegacyCleanup(
                    latest.id(), phase(latest), code(failure), failure.getMessage());
            cleanup(cleanup, command);
            return Result.pending();
        }
    }

    private AcceptanceCandidateLegacyHandoffRow ensureUniqueSession(
            Command command, AcceptanceCandidateLegacyHandoffRow row) {
        if (!handoffs.cleanupRemotes(row.id()).isEmpty()) {
            cleanupRegisteredRemotes(row.id(), command);
            return handoffs.find(command.compilation().id()).orElseThrow();
        }
        AcceptanceCandidateLegacyHandoffService.Claim claim = handoffs.claimCreate(
                row.id(), UUID.randomUUID().toString(), Instant.now(), claimTtl);
        if (!claim.acquired()) return row;
        OpenCodeClient.SessionCreationPlan plan = handoffs.creationPlan(row);
        OpenCodeClient.SessionLookup lookup = openCode.findSessionsByExactTitle(plan);
        if (!lookup.supported()) {
            if (row.createDispatchAttempted()) {
                return handoffs.recordFailure(row.id(), "LEGACY_CREATE",
                        "OPENCODE_SESSION_CREATION_RESULT_UNKNOWN",
                        "创建请求已跨过 POST 边界且目标 OpenCode 不支持精确回查，禁止再次创建");
            }
            return handoffs.failWithoutSuccessor(row.id(), "OPENCODE_SESSION_LOOKUP_UNAVAILABLE",
                    "目标 OpenCode 不支持按确定性创建键回查 Session，未发送创建请求");
        }
        if (lookup.matches().size() > 1) {
            lookup.matches().forEach(candidates::bindLegacy);
            handoffs.registerAmbiguity(row.id(), lookup.matches().stream().map(this::identity).toList());
            cleanupRegisteredRemotes(row.id(), command);
            return handoffs.find(command.compilation().id()).orElseThrow();
        }
        boolean stopping = AcceptanceCandidateHandoffState.STOPPING_LEGACY.name().equals(row.state());
        if (stopping) {
            if (lookup.matches().isEmpty()) {
                return handoffs.recordFailure(row.id(), "LEGACY_CREATE",
                        "OPENCODE_SESSION_CREATION_RESULT_UNKNOWN",
                        "Designer 已停止，但创建请求结果尚未精确回查到远端");
            }
            OpenCodeClient.SessionAttestation recovered = lookup.matches().getFirst();
            candidates.bindLegacy(recovered);
            return handoffs.recordLegacyCreated(row.id(), recovered, claim);
        }
        boolean budgetAvailable = handoffs.promptBudgetAvailable(row.id(), command.revision().id());
        if (!budgetAvailable && !lookup.matches().isEmpty()) {
            lookup.matches().forEach(candidates::bindLegacy);
            handoffs.registerCleanupRemotes(row.id(), lookup.matches().stream().map(this::identity).toList(),
                    "WORK_PACKAGE_MODEL_CALL_LIMIT", "兼容候选提示没有剩余模型调用预算");
            cleanupRegisteredRemotes(row.id(), command);
            return handoffs.find(command.compilation().id()).orElseThrow();
        }
        if (!budgetAvailable) {
            if (row.createDispatchAttempted()) {
                return handoffs.recordFailure(row.id(), "LEGACY_CREATE",
                        "OPENCODE_SESSION_CREATION_RESULT_UNKNOWN",
                        "创建请求结果未知；即使模型预算耗尽也必须先恢复并清理远端");
            }
            return handoffs.failWithoutSuccessor(row.id(), "WORK_PACKAGE_MODEL_CALL_LIMIT",
                    "兼容候选提示没有剩余模型调用预算");
        }
        if (lookup.matches().isEmpty() && row.createDispatchAttempted()) {
            return handoffs.recordFailure(row.id(), "LEGACY_CREATE",
                    "OPENCODE_SESSION_CREATION_RESULT_UNKNOWN",
                    "创建请求已跨过 POST 边界但尚未精确回查到远端，禁止再次创建");
        }
        OpenCodeClient.SessionAttestation attestation;
        if (lookup.matches().isEmpty()) {
            AcceptanceCandidateLegacyHandoffService.DispatchCheckpoint checkpoint =
                    handoffs.markCreateDispatchStarted(row.id(), claim);
            if (!checkpoint.newlyStarted()) {
                return handoffs.recordFailure(row.id(), "LEGACY_CREATE",
                        "OPENCODE_SESSION_CREATION_RESULT_UNKNOWN",
                        "创建请求已发起且结果未知，禁止再次创建");
            }
            attestation = openCode.createSession(plan);
        } else {
            attestation = lookup.matches().getFirst();
        }
        candidates.bindLegacy(attestation);
        return handoffs.recordLegacyCreated(row.id(), attestation, claim);
    }

    private AcceptanceCandidateLegacyHandoffRow ensureHandoff(Command command) {
        AcceptanceCandidateLegacyHandoffRow existing = handoffs.find(command.compilation().id()).orElse(null);
        if (existing != null) return existing;
        ProjectRow project = projects.get(command.session().projectId());
        byte[] credential = new byte[32];
        random.nextBytes(credential);
        OpenCodeClient.SessionCreationPlan plan = openCode.prepareSessionCreation(
                Path.of(project.rootPath()),
                "OpenCode Loopper acceptance closed-choice legacy candidate (NO_TOOLS)",
                command.model(), OpenCodeClient.SessionProfile.COMPILER_BINDING_NO_TOOLS,
                Base64.getUrlEncoder().withoutPadding().encodeToString(credential));
        return handoffs.begin(command.compilation(), command.session(), command.planning(), plan);
    }

    private AcceptanceCandidateLegacyHandoffService.RemoteIdentity identity(
            OpenCodeClient.SessionAttestation attestation) {
        return new AcceptanceCandidateLegacyHandoffService.RemoteIdentity(attestation.remoteId(),
                attestation.runtimeGenerationId(), attestation.endpointFingerprint(),
                digest(attestation.canonicalDirectory().toString()), digest(attestation.exactTitle()));
    }

    private void cleanupRegisteredRemotes(String handoffId, Command command) {
        for (var duplicate : handoffs.cleanupRemotes(handoffId)) {
            if (duplicate.terminationProof() != null) continue;
            AcceptanceCandidateHandoffCleanupLedger.StopClaim stopClaim = cleanupLedger.claimStop(
                    handoffId, duplicate.externalSessionId(), UUID.randomUUID().toString(), Instant.now(), claimTtl);
            if (!stopClaim.acquired()) return;
            DesignerAcceptanceCandidateOrchestrator.StopResult stopped = candidates.stopUnopened(
                    new OpenCodeClient.OpenCodeSession(duplicate.externalSessionId(),
                            Path.of(projects.get(command.session().projectId()).rootPath())));
            if (!stopped.confirmed()) {
                cleanupLedger.disconnected(handoffId, duplicate.externalSessionId(), stopClaim,
                        "OPENCODE_ACCEPTANCE_LEGACY_STOP_UNCONFIRMED", stopped.detail());
                handoffs.recordFailure(handoffId, "LEGACY_CREATE",
                        "OPENCODE_ACCEPTANCE_LEGACY_STOP_UNCONFIRMED", stopped.detail());
                return;
            }
            cleanupLedger.stopped(handoffId, duplicate.externalSessionId(), stopClaim, stopped.proof());
        }
        AcceptanceCandidateLegacyHandoffRow row = handoffs.find(command.compilation().id()).orElseThrow();
        handoffs.failWithoutSuccessor(handoffId,
                row.lastErrorCode() == null ? "OPENCODE_SESSION_CREATION_AMBIGUOUS" : row.lastErrorCode(),
                row.lastErrorDetail() == null
                        ? "确定性创建凭据匹配到多个兼容候选 Session，已逐个确认停止"
                        : row.lastErrorDetail());
    }

    private static String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private Result prompt(Command command, AcceptanceCandidateLegacyHandoffRow row,
            OpenCodeClient.OpenCodeSession remote, String text) {
        DesignerModelPromptTransport.PreparedPrompt prepared = prompts.prepare(text, "TEXT_MARKER", null,
                command.session().id(), command.workPackageId(), row.legacyPromptMessageId());
        if (row.legacyPromptSha256() != null && !row.legacyPromptSha256().equals(prepared.sha256())) {
            throw new ConflictException("ACCEPTANCE_LEGACY_HANDOFF_PROMPT_STALE",
                    "兼容候选提示或附件上下文已漂移");
        }
        AcceptanceCandidateLegacyHandoffService.PromptClaim reserved = handoffs.claimPrompt(row.id(),
                command.revision(), prepared.sha256(), UUID.randomUUID().toString(),
                Instant.now(), claimTtl);
        AcceptanceCandidateLegacyHandoffService.Claim claim = reserved.claim();
        if (!claim.acquired()) {
            if ("BUDGET_EXHAUSTED".equals(claim.reason())) {
                AcceptanceCandidateLegacyHandoffRow cleanup = handoffs.beginLegacyCleanup(row.id(),
                        "LEGACY_PROMPT", "WORK_PACKAGE_MODEL_CALL_LIMIT",
                        "兼容候选提示没有剩余模型调用预算");
                cleanup(cleanup, command);
            }
            return Result.pending();
        }
        OpenCodeClient.MessageLookup lookup;
        try {
            handoffs.requirePromptClaim(row.id(), claim);
            lookup = prompts.lookupPrompt(remote, prepared);
        } catch (io.opencode.loopper.domain.SessionFailure lookupFailure) {
            throw lookupFailure;
        }
        if (!lookup.exists()) {
            if (!reserved.newlyStarted()) {
                handoffs.recordFailure(row.id(), "LEGACY_PROMPT",
                        "OPENCODE_PROMPT_RESULT_UNKNOWN",
                        "提示请求已跨过 POST 边界但尚未精确回查到消息，禁止再次发送");
                return Result.pending();
            }
            try {
                handoffs.requirePromptClaim(row.id(), claim);
                prompts.dispatchPrompt(remote, prepared);
            } catch (RuntimeException uncertain) {
                handoffs.recordFailure(row.id(), "LEGACY_PROMPT", code(uncertain), uncertain.getMessage());
                return Result.pending();
            }
        }
        try {
            handoffs.requirePromptClaim(row.id(), claim);
        } catch (ConflictException stoppedAfterPrompt) {
            handoffs.fencePrompt(row.id(), claim);
            throw stoppedAfterPrompt;
        } catch (RuntimeException uncertain) {
            handoffs.recordFailure(row.id(), "LEGACY_PROMPT", code(uncertain), uncertain.getMessage());
            return Result.pending();
        }
        try {
            handoffs.markHandedOff(row.id(), claim);
        } catch (ConflictException ownerDrift) {
            handoffs.fencePrompt(row.id(), claim);
            throw ownerDrift;
        } catch (RuntimeException localCheckpointFailure) {
            handoffs.recordFailure(row.id(), "LEGACY_PROMPT",
                    "ACCEPTANCE_LEGACY_PROMPT_CHECKPOINT_FAILED", localCheckpointFailure.getMessage());
            return Result.pending();
        }
        return Result.handedOff(remote);
    }

    private void cleanup(AcceptanceCandidateLegacyHandoffRow row, Command command) {
        if (row.legacyExternalSessionId() == null) return;
        DesignerAcceptanceCandidateOrchestrator.StopResult stopped = candidates.stopUnopened(remote(command, row));
        if (stopped.confirmed()) handoffs.completeLegacyCleanup(row.id(), stopped.proof());
        else handoffs.recordFailure(row.id(), "LEGACY_STOP",
                "OPENCODE_ACCEPTANCE_LEGACY_STOP_UNCONFIRMED", stopped.detail());
    }

    private OpenCodeClient.OpenCodeSession remote(Command command, AcceptanceCandidateLegacyHandoffRow row) {
        return new OpenCodeClient.OpenCodeSession(row.legacyExternalSessionId(),
                Path.of(projects.get(command.session().projectId()).rootPath()),
                row.successorManaged() ? row.legacyRuntimeGenerationId() : null,
                row.successorManaged() ? row.successorInternalMcpServer() : null);
    }

    private OpenCodeClient.OpenCodeSession oldRemote(Command command, AcceptanceCandidateLegacyHandoffRow row) {
        return new OpenCodeClient.OpenCodeSession(row.oldExternalSessionId(),
                Path.of(projects.get(command.session().projectId()).rootPath()));
    }

    private static String phase(AcceptanceCandidateLegacyHandoffRow row) {
        return AcceptanceCandidateHandoffState.STOPPING_OLD.name().equals(row.state()) ? "OLD_STOP"
                : AcceptanceCandidateHandoffState.PROMPTING.name().equals(row.state()) ? "LEGACY_PROMPT"
                : AcceptanceCandidateHandoffState.LEGACY_OPENED.name().equals(row.state())
                ? "LEGACY_OPEN" : "LEGACY_CREATE";
    }

    private static String code(RuntimeException failure) {
        return failure instanceof ConflictException conflict ? conflict.code()
                : failure instanceof io.opencode.loopper.domain.SessionFailure session ? session.code()
                : "OPENCODE_ACCEPTANCE_LEGACY_HANDOFF_FAILED";
    }

    private static boolean active(String expiresAt) {
        return expiresAt != null && Instant.parse(expiresAt).isAfter(Instant.now());
    }

    static Duration claimTtl(LoopperProperties properties) {
        Duration connectTimeout = properties.getOpenCode().getConnectTimeout();
        Duration requestTimeout = properties.getOpenCode().getRequestTimeout();
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("OpenCode connect/request timeouts must be positive");
        }
        return connectTimeout.plus(requestTimeout).multipliedBy(2).plusSeconds(15);
    }

    record Command(LoopSpecCompilationRow compilation, DesignerSessionRow session,
                   DesignRequirementRevisionRow revision, String workPackageId,
                   DesignAcceptancePlanningRow planning,
                   DesignerAcceptanceWorkflow.RoutingResult routing,
                   OpenCodeClient.OpenCodeModel model) { }
    record Result(boolean handedOff, OpenCodeClient.OpenCodeSession remote) {
        static Result pending() { return new Result(false, null); }
        static Result handedOff(OpenCodeClient.OpenCodeSession remote) { return new Result(true, remote); }
    }
    record CancellationRecovery(boolean ready, int stoppedSessions, int failedSessions,
                                Map<String, String> proofs) {
        static CancellationRecovery pending() { return new CancellationRecovery(false, 0, 1, Map.of()); }
        static CancellationRecovery ready(Map<String, String> proofs, int stopped) {
            return new CancellationRecovery(true, stopped, 0, proofs);
        }
    }
    record Terminal(String state, String code, String detail) { }
}
