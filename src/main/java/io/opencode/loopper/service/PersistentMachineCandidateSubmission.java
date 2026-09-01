package io.opencode.loopper.service;

import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateOutcome;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.CandidateSubmissionAttemptRow;
import io.opencode.loopper.persistence.CandidateSubmissionRunRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.LoopperMachineCandidateMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Persistent implementation of the bounded machine-candidate correction protocol. */
@Service
public final class PersistentMachineCandidateSubmission implements MachineCandidateSubmission {
    private static final int MAX_CANDIDATE_BYTES = 128 * 1024;
    private static final int MAX_PROBLEMS = 16;
    private static final int MAX_PROBLEM_CODE_BYTES = 64;
    private static final int MAX_PROBLEM_POINTER_BYTES = 256;
    private static final int MAX_PROBLEM_DETAIL_BYTES = 1024;
    private static final int MAX_ALLOWED_VALUES = 32;
    private static final int MAX_ALLOWED_VALUE_BYTES = 256;
    private static final int MAX_SAFE_JSON_BYTES = 16 * 1024;

    private final LoopperMachineCandidateMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final ObjectMapper json;
    private final List<CandidatePolicy> policies;
    private final List<AcceptedCandidateWriter> writers;
    private final List<CandidateRunGuard> guards;

    public PersistentMachineCandidateSubmission(
            LoopperMapper mapper, LifecycleTransitionService lifecycle, ObjectMapper json,
            List<CandidatePolicy> policies, List<AcceptedCandidateWriter> writers, List<CandidateRunGuard> guards) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.json = json;
        this.policies = List.copyOf(policies);
        this.writers = List.copyOf(writers);
        this.guards = List.copyOf(guards);
    }

    @Override
    public synchronized RunSnapshot open(OpenCommand command) {
        validateOpen(command);
        CandidateSubmissionRunRow prior = mapper.findCandidateSubmissionRun(command.runId()).orElse(null);
        if (prior != null) {
            if (!sameContract(prior, command)) {
                throw new ConflictException("CANDIDATE_RUN_ID_REUSED", "候选运行 ID 已用于不同合同");
            }
            return snapshot(prior);
        }
        String now = Instant.now().toString();
        CandidateSubmissionRunRow row = new CandidateSubmissionRunRow(
                command.runId(), scopeId(command.scope(), CandidateScopeType.DESIGNER_SESSION),
                scopeId(command.scope(), CandidateScopeType.TASK), scopeId(command.scope(), CandidateScopeType.PROJECT),
                command.owner().type().name(), command.owner().id(),
                command.candidateKind().name(), command.workflowStep(),
                command.sourceRevision(), command.ownerVersion(), command.submissionChannel().name(),
                command.contractVersion(), command.runtimeGenerationId(), command.externalSessionId(),
                MachineCandidateRunState.OPEN.name(), command.maxAttempts(), 0, null, now, now, 0);
        lifecycle.create(subject(row), row.state(), Map.of(
                        "candidateKind", row.candidateKind(), "workflowStep", row.workflowStep(),
                        "sourceRevision", row.sourceRevision(), "ownerVersion", row.ownerVersion(),
                        "submissionChannel", row.submissionChannel(),
                        "maxAttempts", row.maxAttempts()),
                () -> mapper.insertCandidateSubmissionRun(row),
                () -> new ConflictException("CANDIDATE_RUN_CONFLICT", "候选运行无法创建"));
        return snapshot(row);
    }

    @Override
    public synchronized SubmissionResult submit(SubmitCommand command) {
        validateSubmit(command);
        String requestSha = sha256(command.candidateJson());
        CandidateSubmissionRunRow run = requireRun(command.runId());
        if (command.submissionChannel() != SubmissionChannel.valueOf(run.submissionChannel())) {
            throw new ConflictException("CANDIDATE_SUBMISSION_CHANNEL_MISMATCH",
                    "候选提交渠道与运行合同不一致");
        }
        CandidateSubmissionAttemptRow replay = mapper.findCandidateSubmissionAttemptByKey(
                command.runId(), command.idempotencyKey()).orElse(null);
        if (replay != null) return replay(run, replay, requestSha);
        if (command.expectedSubmissionRevision() != run.version()) {
            throw new ConflictException("CANDIDATE_SUBMISSION_REVISION_CONFLICT", "候选提交修订已过期");
        }
        if (MachineCandidateRunState.valueOf(run.state()) != MachineCandidateRunState.OPEN) {
            throw new ConflictException("CANDIDATE_RUN_TERMINAL", "候选运行已经结束");
        }
        validateGuards(run, command.submissionChannel());

        CandidatePolicy.Context context = context(run);
        CandidatePolicy.Decision decision = policy(run).evaluate(context, command.candidateJson());
        ValidatedDecision validated = validateDecision(decision, context.candidateKind());
        return persist(command, requestSha, run, context, validated);
    }

    @Override
    public synchronized RunSnapshot close(CloseCommand command) {
        if (command == null || blank(command.runId()) || command.expectedVersion() < 0 || command.reason() == null) {
            throw new BadRequestException("CANDIDATE_CLOSE_INVALID", "候选运行关闭参数不完整");
        }
        CandidateSubmissionRunRow run = requireRun(command.runId());
        MachineCandidateRunState state = MachineCandidateRunState.valueOf(run.state());
        if (state.terminal()) {
            if (state == MachineCandidateRunState.CLOSED
                    && !command.reason().name().equals(run.closeReason())) {
                throw new ConflictException("CANDIDATE_CLOSE_REASON_CONFLICT", "候选运行已按其他原因关闭");
            }
            return snapshot(run);
        }
        if (run.version() != command.expectedVersion()) {
            throw new ConflictException("CANDIDATE_RUN_VERSION_CONFLICT", "候选运行已被其他请求更新");
        }
        CandidateSubmissionRunRow closed = updated(run, MachineCandidateRunState.CLOSED,
                run.attemptsUsed(), run.terminalAttemptId(), Instant.now().toString(), command.reason());
        lifecycle.transition(subject(run), run.state(), closed.state(), "CANDIDATE_RUN_CLOSED",
                Map.of("closeReason", command.reason().name()),
                () -> mapper.updateCandidateSubmissionRun(closed),
                () -> new ConflictException("CANDIDATE_RUN_VERSION_CONFLICT", "候选运行已被其他请求更新"));
        return snapshot(mapper.findCandidateSubmissionRun(run.id()).orElseThrow());
    }

    @Override
    public Optional<RunSnapshot> find(String runId) {
        if (blank(runId)) return Optional.empty();
        return mapper.findCandidateSubmissionRun(runId).map(this::snapshot);
    }

    @Override
    public Optional<SubmissionResult> terminal(String runId) {
        if (blank(runId)) return Optional.empty();
        CandidateSubmissionRunRow run = mapper.findCandidateSubmissionRun(runId).orElse(null);
        if (run == null || !MachineCandidateRunState.valueOf(run.state()).terminal()
                || blank(run.terminalAttemptId())) {
            return Optional.empty();
        }
        return mapper.listCandidateSubmissionAttempts(runId).stream()
                .filter(attempt -> attempt.id().equals(run.terminalAttemptId()))
                .findFirst()
                .map(attempt -> storedResult(run, attempt));
    }

    private SubmissionResult persist(SubmitCommand command, String requestSha, CandidateSubmissionRunRow run,
                                     CandidatePolicy.Context context, ValidatedDecision decision) {
        int ordinal = run.attemptsUsed() + 1;
        MachineCandidateOutcome outcome;
        if (decision.accepted()) {
            outcome = MachineCandidateOutcome.ACCEPTED;
        } else if (decision.retryable() && ordinal >= run.maxAttempts() && decision.fallbackEligible()) {
            outcome = MachineCandidateOutcome.FALLBACK_REQUIRED;
        } else {
            outcome = !decision.retryable() || ordinal >= run.maxAttempts()
                    ? MachineCandidateOutcome.WAITING_INPUT : MachineCandidateOutcome.REJECTED;
        }
        MachineCandidateRunState target = switch (outcome) {
            case ACCEPTED -> MachineCandidateRunState.ACCEPTED;
            case WAITING_INPUT -> MachineCandidateRunState.WAITING_INPUT;
            case FALLBACK_REQUIRED -> MachineCandidateRunState.FALLBACK_REQUIRED;
            case REJECTED -> MachineCandidateRunState.OPEN;
        };
        String attemptId = UUID.randomUUID().toString();
        String canonicalSha = decision.accepted() ? sha256(decision.canonicalCandidateJson()) : null;
        boolean retryable = outcome == MachineCandidateOutcome.REJECTED && decision.retryable();
        SubmissionResult result = result(run, outcome, target, ordinal, retryable, decision.problems(), canonicalSha);
        String problemsJson = boundedJson(result.problems(), "Candidate problems");
        String responseJson = result.responseJson();
        String now = Instant.now().toString();
        CandidateSubmissionAttemptRow attempt = new CandidateSubmissionAttemptRow(
                attemptId, run.id(), ordinal, command.idempotencyKey(), requestSha, outcome.name(), retryable,
                problemsJson, responseJson, canonicalSha, now);
        CandidateSubmissionRunRow next = updated(run, target, ordinal,
                target.terminal() ? attemptId : null, now);

        if (target == MachineCandidateRunState.OPEN) {
            lifecycle.mutateWithoutTransition(
                    () -> writeAttemptAndRun(attempt, run, next, null, null, command.submissionChannel()),
                    () -> new ConflictException("CANDIDATE_RUN_VERSION_CONFLICT", "候选运行已被其他请求更新"));
        } else {
            lifecycle.transition(subject(run), run.state(), next.state(), outcome.name(),
                    Map.of("attemptOrdinal", ordinal, "outcome", outcome.name()),
                    () -> writeAttemptAndRun(attempt, run, next, context, decision, command.submissionChannel()),
                    () -> new ConflictException("CANDIDATE_RUN_VERSION_CONFLICT", "候选运行已被其他请求更新"));
        }
        return result;
    }

    private int writeAttemptAndRun(CandidateSubmissionAttemptRow attempt, CandidateSubmissionRunRow current,
                                   CandidateSubmissionRunRow next,
                                   CandidatePolicy.Context context, ValidatedDecision decision,
                                   SubmissionChannel submissionChannel) {
        validateGuards(current, submissionChannel);
        if (decision != null && decision.accepted()) {
            writer(context.candidateKind()).write(
                    context, decision.canonicalCandidateJson(), attempt.canonicalResultSha256());
        }
        if (mapper.insertCandidateSubmissionAttempt(attempt) != 1) {
            throw new ConflictException("CANDIDATE_ATTEMPT_CONFLICT", "候选尝试无法持久化");
        }
        return mapper.updateCandidateSubmissionRun(next);
    }

    private SubmissionResult result(CandidateSubmissionRunRow run, MachineCandidateOutcome outcome,
                                    MachineCandidateRunState state, int ordinal, boolean retryable,
                                    List<Problem> problems, String canonicalSha) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("runId", run.id());
        response.put("outcome", outcome.name());
        response.put("runState", state.name());
        response.put("attemptOrdinal", ordinal);
        response.put("remainingAttempts", Math.max(0, run.maxAttempts() - ordinal));
        response.put("retryable", retryable);
        response.put("problems", problems);
        response.put("submissionRevision", run.version() + 1);
        if (canonicalSha != null) response.put("canonicalResultSha256", canonicalSha);
        String responseJson = boundedJson(response, "Candidate response");
        return new SubmissionResult(run.id(), outcome, state, ordinal,
                Math.max(0, run.maxAttempts() - ordinal), retryable, problems, canonicalSha,
                run.version() + 1, responseJson);
    }

    private SubmissionResult replay(CandidateSubmissionRunRow run, CandidateSubmissionAttemptRow attempt,
                                    String requestSha) {
        if (!attempt.requestSha256().equals(requestSha)) {
            throw new ConflictException("CANDIDATE_IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同候选内容");
        }
        return storedResult(run, attempt);
    }

    private SubmissionResult storedResult(CandidateSubmissionRunRow run, CandidateSubmissionAttemptRow attempt) {
        List<Problem> problems;
        try {
            problems = json.readValue(attempt.problemsJson(), new TypeReference<List<Problem>>() { });
        } catch (JacksonException invalid) {
            throw new IllegalStateException("Stored candidate problems are invalid", invalid);
        }
        try {
            JsonNode response = json.readTree(attempt.responseJson());
            return new SubmissionResult(run.id(), MachineCandidateOutcome.valueOf(attempt.outcome()),
                    MachineCandidateRunState.valueOf(response.path("runState").asText()), attempt.ordinal(),
                    response.path("remainingAttempts").asInt(), attempt.retryable(), problems,
                    attempt.canonicalResultSha256(), response.path("submissionRevision").asLong(),
                    attempt.responseJson());
        } catch (JacksonException invalid) {
            throw new IllegalStateException("Stored candidate response is invalid", invalid);
        }
    }

    private ValidatedDecision validateDecision(CandidatePolicy.Decision decision, MachineCandidateKind kind) {
        if (decision == null || decision.problems() == null || decision.problems().size() > MAX_PROBLEMS) {
            throw new IllegalStateException("Candidate policy returned an invalid problem set");
        }
        List<Problem> problems = new ArrayList<>();
        for (Problem problem : decision.problems()) {
            if (problem == null || blank(problem.code()) || !problem.code().matches("[A-Z0-9_]+")
                    || bytes(problem.code()) > MAX_PROBLEM_CODE_BYTES || bytes(nullToEmpty(problem.pointer())) > MAX_PROBLEM_POINTER_BYTES
                    || blank(problem.detail()) || bytes(problem.detail()) > MAX_PROBLEM_DETAIL_BYTES
                    || problem.allowedValues() == null || problem.allowedValues().size() > MAX_ALLOWED_VALUES
                    || problem.allowedValues().stream().anyMatch(value -> blank(value)
                        || bytes(value) > MAX_ALLOWED_VALUE_BYTES)) {
                throw new IllegalStateException("Candidate policy returned an invalid problem");
            }
            problems.add(new Problem(problem.code(), nullToEmpty(problem.pointer()), problem.detail(),
                    problem.allowedValues()));
        }
        if (decision.accepted()) {
            if (!problems.isEmpty() || blank(decision.canonicalCandidateJson()) || decision.fallbackEligible()) {
                throw new IllegalStateException("Accepted candidate policy decision is incomplete");
            }
            validateJsonObject(decision.canonicalCandidateJson());
        } else if (problems.isEmpty() || decision.canonicalCandidateJson() != null) {
            throw new IllegalStateException("Rejected candidate policy decision is incomplete");
        }
        if (decision.fallbackEligible() && !MachineCandidateProtocolPolicy.contract(kind).fallbackAllowed()) {
            throw new IllegalStateException("Fallback eligibility is restricted to package-design candidates");
        }
        if (decision.fallbackEligible() && !decision.retryable()) {
            throw new IllegalStateException("Fallback eligibility requires a retryable package-design rejection");
        }
        boundedJson(problems, "Candidate problems");
        return new ValidatedDecision(decision.accepted(), decision.canonicalCandidateJson(),
                decision.retryable(), decision.fallbackEligible(), List.copyOf(problems));
    }

    private void validateJsonObject(String value) {
        if (bytes(value) > MAX_CANDIDATE_BYTES) {
            throw new IllegalStateException("Canonical candidate exceeds 128 KiB");
        }
        try {
            JsonNode node = json.readTree(value);
            if (node == null || !node.isObject()) throw new IllegalStateException("Canonical candidate must be a JSON object");
        } catch (JacksonException invalid) {
            throw new IllegalStateException("Canonical candidate is invalid JSON", invalid);
        }
    }

    private CandidatePolicy policy(CandidateSubmissionRunRow run) {
        MachineCandidateKind kind = MachineCandidateKind.valueOf(run.candidateKind());
        List<CandidatePolicy> matches = policies.stream().filter(policy -> policy.supports(kind)).toList();
        if (matches.size() != 1) throw new IllegalStateException("Exactly one candidate policy is required for " + kind);
        return matches.getFirst();
    }

    private AcceptedCandidateWriter writer(MachineCandidateKind kind) {
        List<AcceptedCandidateWriter> matches = writers.stream().filter(writer -> writer.supports(kind)).toList();
        if (matches.size() != 1) throw new IllegalStateException("Exactly one accepted writer is required for " + kind);
        return matches.getFirst();
    }

    private void validateOpen(OpenCommand command) {
        if (command == null || blank(command.runId()) || command.scope() == null || command.owner() == null
                || command.candidateKind() == null || blank(command.workflowStep()) || command.sourceRevision() < 0
                || command.ownerVersion() < 0 || command.submissionChannel() == null
                || blank(command.contractVersion()) || blank(command.runtimeGenerationId())
                || blank(command.externalSessionId())) {
            throw new BadRequestException("CANDIDATE_RUN_INVALID", "候选运行合同不完整");
        }
        MachineCandidateProtocolPolicy.Contract protocol = MachineCandidateProtocolPolicy.contract(
                command.candidateKind());
        if (command.scope().type() != protocol.scopeType() || command.owner().type() != protocol.ownerType()) {
            throw new BadRequestException("CANDIDATE_OWNER_INVALID", "候选类型、作用域与拥有者不匹配");
        }
        if (command.maxAttempts() < 1 || command.maxAttempts() > command.candidateKind().maximumAttempts()) {
            throw new BadRequestException("CANDIDATE_ATTEMPT_LIMIT_INVALID", "候选尝试预算超出角色上限");
        }
        if (!protocol.integrated()) {
            throw new BadRequestException("CANDIDATE_KIND_NOT_INTEGRATED", "候选类型尚未接入业务适配器");
        }
    }

    private void validateSubmit(SubmitCommand command) {
        if (command == null || blank(command.runId()) || blank(command.idempotencyKey())
                || bytes(command.idempotencyKey()) > 128 || blank(command.candidateJson())
                || command.expectedSubmissionRevision() < 0 || command.submissionChannel() == null) {
            throw new BadRequestException("CANDIDATE_SUBMISSION_INVALID", "候选提交参数不完整");
        }
        if (bytes(command.candidateJson()) > MAX_CANDIDATE_BYTES) {
            throw new BadRequestException("CANDIDATE_TOO_LARGE", "候选 JSON 超过 128 KiB");
        }
    }

    private boolean sameContract(CandidateSubmissionRunRow row, OpenCommand command) {
        return scope(row).equals(command.scope())
                && owner(row).equals(command.owner())
                && row.candidateKind().equals(command.candidateKind().name())
                && row.workflowStep().equals(command.workflowStep()) && row.sourceRevision() == command.sourceRevision()
                && row.ownerVersion() == command.ownerVersion()
                && row.submissionChannel().equals(command.submissionChannel().name())
                && row.contractVersion().equals(command.contractVersion())
                && row.runtimeGenerationId().equals(command.runtimeGenerationId())
                && row.externalSessionId().equals(command.externalSessionId())
                && row.maxAttempts() == command.maxAttempts();
    }

    private CandidateSubmissionRunRow requireRun(String id) {
        return mapper.findCandidateSubmissionRun(id)
                .orElseThrow(() -> new NotFoundException("Candidate submission run not found"));
    }

    private CandidatePolicy.Context context(CandidateSubmissionRunRow run) {
        return new CandidatePolicy.Context(run.id(), scope(run), owner(run),
                MachineCandidateKind.valueOf(run.candidateKind()), run.workflowStep(), run.sourceRevision(),
                run.ownerVersion(), run.contractVersion(), run.maxAttempts(), run.attemptsUsed());
    }

    private RunSnapshot snapshot(CandidateSubmissionRunRow row) {
        return new RunSnapshot(row.id(), scope(row), owner(row),
                MachineCandidateKind.valueOf(row.candidateKind()), row.workflowStep(), row.sourceRevision(),
                row.ownerVersion(), SubmissionChannel.valueOf(row.submissionChannel()), row.contractVersion(),
                row.runtimeGenerationId(), row.externalSessionId(), MachineCandidateRunState.valueOf(row.state()),
                row.maxAttempts(), row.attemptsUsed(), row.terminalAttemptId(), row.version(),
                blank(row.closeReason()) ? null : CandidateCloseReason.valueOf(row.closeReason()));
    }

    private CandidateOwnerRef owner(CandidateSubmissionRunRow row) {
        return new CandidateOwnerRef(CandidateOwnerType.valueOf(row.ownerType()), row.ownerId());
    }

    private CandidateScope scope(CandidateSubmissionRunRow row) {
        if (!blank(row.designerSessionId())) return CandidateScope.designerSession(row.designerSessionId());
        if (!blank(row.taskId())) return CandidateScope.task(row.taskId());
        if (!blank(row.projectId())) return CandidateScope.project(row.projectId());
        throw new IllegalStateException("Stored candidate run has no scope");
    }

    private CandidateSubmissionRunRow updated(CandidateSubmissionRunRow row, MachineCandidateRunState state,
                                              int attemptsUsed, String terminalAttemptId, String updatedAt) {
        return updated(row, state, attemptsUsed, terminalAttemptId, updatedAt, null);
    }

    private CandidateSubmissionRunRow updated(CandidateSubmissionRunRow row, MachineCandidateRunState state,
                                              int attemptsUsed, String terminalAttemptId, String updatedAt,
                                              CandidateCloseReason closeReason) {
        return new CandidateSubmissionRunRow(row.id(), row.designerSessionId(), row.taskId(), row.projectId(),
                row.ownerType(), row.ownerId(), row.candidateKind(), row.workflowStep(), row.sourceRevision(),
                row.ownerVersion(), row.submissionChannel(), row.contractVersion(), row.runtimeGenerationId(),
                row.externalSessionId(), state.name(), row.maxAttempts(), attemptsUsed, terminalAttemptId,
                row.createdAt(), updatedAt, row.version(), closeReason == null ? row.closeReason() : closeReason.name());
    }

    private LifecycleTransitionService.Subject subject(CandidateSubmissionRunRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.CANDIDATE_SUBMISSION_RUN,
                row.id(), lifecycleScope(scope(row).type()), scope(row).id());
    }

    private static LifecycleScopeType lifecycleScope(CandidateScopeType type) {
        return switch (type) {
            case DESIGNER_SESSION -> LifecycleScopeType.DESIGNER;
            case TASK -> LifecycleScopeType.TASK;
            case PROJECT -> LifecycleScopeType.PROJECT;
        };
    }

    private static String scopeId(CandidateScope scope, CandidateScopeType expected) {
        return scope.type() == expected ? scope.id() : null;
    }

    private void validateGuards(CandidateSubmissionRunRow row, SubmissionChannel submissionChannel) {
        RunSnapshot run = snapshot(row);
        guards.forEach(guard -> guard.validate(run, submissionChannel));
    }

    private String boundedJson(Object value, String label) {
        try {
            String encoded = json.writeValueAsString(value);
            if (bytes(encoded) > MAX_SAFE_JSON_BYTES) throw new IllegalStateException(label + " exceeds 16 KiB");
            return encoded;
        } catch (JacksonException failure) {
            throw new IllegalStateException(label + " cannot be serialized", failure);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static int bytes(String value) { return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    private record ValidatedDecision(boolean accepted, String canonicalCandidateJson,
                                     boolean retryable, boolean fallbackEligible, List<Problem> problems) { }
}
