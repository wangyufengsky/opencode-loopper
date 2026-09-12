package io.opencode.loopper.service;

import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.SessionState;
import io.opencode.loopper.domain.TemplateBatchState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.AttemptRow;
import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TemplateTaskBatchRow;
import io.opencode.loopper.persistence.TemplateTaskMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Short, audited CAS checkpoints for remote boundaries. No method calls the provider or filesystem. */
@Service
public class TemplateBatchStore {
    private final TemplateTaskMapper templates;
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final TaskStateStore states;
    private final ObjectMapper json;
    private final io.opencode.loopper.persistence.TemplateContinuationMapper continuations;
    private final io.opencode.loopper.persistence.TemplateCandidateSubmissionMapper submissions;

    TemplateBatchStore(TemplateTaskMapper templates, LoopperMapper mapper, LifecycleTransitionService lifecycle,
                       TaskStateStore states, ObjectMapper json,
                       io.opencode.loopper.persistence.TemplateContinuationMapper continuations,
                       io.opencode.loopper.persistence.TemplateCandidateSubmissionMapper submissions) {
        this.templates = templates; this.mapper = mapper; this.lifecycle = lifecycle; this.states = states; this.json = json;
        this.continuations = continuations; this.submissions = submissions;
    }

    /** Called only after the current exact prompt has reached a proven length terminal. */
    @Transactional
    public TemplateTaskBatchRow prepareContinuation(TemplateTaskBatchRow row) {
        requireRunning(row.taskId(), row.attemptId());
        requireState(row, TemplateBatchState.RUNNING);
        if (require(row.id()).version() != row.version()) throw conflict();
        if (submissions.accepted(row.id()).isPresent()) return row;
        var previous = continuations.latest(row.id()).orElse(null);
        int distinct = continuations.distinctSubmissions(row.id());
        int stagnant = distinct > (previous == null ? 0 : previous.distinctSubmissions()) ? 0
                : (previous == null ? 0 : previous.stagnantLengths()) + 1;
        if (stagnant >= 3) throw new io.opencode.loopper.domain.SessionFailure("TEMPLATE_ANALYSIS_STALLED",
                "分析连续三次达到生成长度上限，期间没有新的不同内容 MCP 提交；已停止自动续接，请检查模型推理与输出额度后重新发起");
        int ordinal = previous == null ? 1 : previous.ordinal() + 1;
        var plan = json.readValue(row.creationPlanJson(), OpenCodeClient.SessionCreationPlan.class);
        String text = TemplateAnalysisPromptFactory.continuation(row.id(), plan.internalMcpServer(), submissions.revision(row.id()));
        var prompt = new FrozenPrompt(text, "msg_" + row.id().replace("-", "") + "_continue_" + ordinal, null, null);
        String value = json.writeValueAsString(prompt);
        String hash = OpenCodeClient.promptRequestSha256(prompt.request());
        var intent = new io.opencode.loopper.persistence.TemplateContinuationMapper.Continuation(row.id(), ordinal,
                row.promptJson(), value, hash, distinct, stagnant, Instant.now().toString());
        if (continuations.insert(intent) != 1) throw conflict();
        var updated = transport(row, row.sessionId(), row.creationPlanJson(), value, hash, null, null, null);
        return transition(updated, TemplateBatchState.DISPATCHING, LifecycleEvent.RETRY);
    }

    public boolean hasContinuation(String batchId) { return continuations.latest(batchId).isPresent(); }
    public FrozenPrompt previousPrompt(String batchId) {
        return json.readValue(continuations.latest(batchId).orElseThrow().priorPromptJson(), FrozenPrompt.class);
    }

    @Transactional
    public void plan(String taskId, int reviews, int contributors) {
        templates.insertPlan(taskId, reviews, contributors);
    }

    @Transactional
    public TemplateTaskBatchRow create(AttemptRow attempt, int ordinal, String purpose, String input, String digest) {
        requireRunning(attempt.taskId(), attempt.id());
        var existing = templates.findBatchOrdinal(attempt.taskId(), attempt.id(), purpose, ordinal).orElse(null);
        if (existing != null) {
            if (!existing.inputSha256().equals(digest)) throw new ConflictException("TEMPLATE_BATCH_INPUT_CHANGED", "冻结批次证据发生变化");
            return existing;
        }
        String now = Instant.now().toString();
        var row = new TemplateTaskBatchRow(UUID.randomUUID().toString(), attempt.taskId(), attempt.id(), null, ordinal, purpose,
                input, digest, "PREPARED", null, null, null, null, null, null, now, now, 0);
        lifecycle.create(subject(row), row.state(), Map.of("purpose", purpose, "ordinal", ordinal),
                () -> templates.insertBatch(row), TemplateBatchStore::conflict);
        return row;
    }

    @Transactional
    public TemplateTaskBatchRow prepareSession(TemplateTaskBatchRow row, OpenCodeClient.SessionCreationPlan plan, FrozenPrompt prompt) {
        requireRunning(row.taskId(), row.attemptId());
        requireState(row, TemplateBatchState.PREPARED);
        var attempt = mapper.findAttempt(row.attemptId()).orElseThrow();
        String id = UUID.randomUUID().toString();
        states.createSession(new ExecutionSessionRow(id, row.taskId(), attempt.stageId(), attempt.id(), null,
                SessionState.CREATING.name(), Instant.now().toString(), null, 0));
        var updated = transport(row, id, json.writeValueAsString(plan), json.writeValueAsString(prompt),
                OpenCodeClient.promptRequestSha256(prompt.request()), null, null, null);
        return transition(updated, TemplateBatchState.CREATING, LifecycleEvent.PREPARE);
    }

    @Transactional
    public TemplateTaskBatchRow attachRemote(TemplateTaskBatchRow row, OpenCodeClient.SessionAttestation attestation) {
        requireState(row, TemplateBatchState.CREATING);
        var expected = json.readValue(row.creationPlanJson(), OpenCodeClient.SessionCreationPlan.class);
        if (!expected.equals(attestation.plan())) throw new ConflictException("TEMPLATE_SESSION_ATTESTATION_CHANGED", "远端会话与冻结身份不一致");
        ExecutionSessionRow session = mapper.findSession(row.sessionId()).orElseThrow();
        states.updateSession(new ExecutionSessionRow(session.id(), session.taskId(), session.stageId(), session.attemptId(),
                attestation.remoteId(), SessionState.RUNNING.name(), session.createdAt(), null, session.version(), "UNAVAILABLE"));
        return transition(row, TemplateBatchState.PROMPT_READY, LifecycleEvent.PREPARATION_SUCCEEDED);
    }

    @Transactional
    public TemplateTaskBatchRow validated(TemplateTaskBatchRow row, String output, boolean cached) {
        requireRunning(row.taskId(), row.attemptId());
        requireState(row, cached ? TemplateBatchState.PREPARED : TemplateBatchState.RUNNING);
        TemplateTaskBatchRow saved = transport(row, row.sessionId(), row.creationPlanJson(), row.promptJson(), row.promptSha256(), output, null, null);
        if (!cached) {
            var session = mapper.findSession(row.sessionId()).orElseThrow();
            states.updateSession(states.sessionState(session, SessionState.COMPLETED));
        }
        return transition(saved, TemplateBatchState.VALIDATED, LifecycleEvent.COMPLETE);
    }

    @Transactional
    public TemplateTaskBatchRow candidateFailed(TemplateTaskBatchRow row, String code, String message) {
        requireState(row, TemplateBatchState.RUNNING);
        TemplateTaskBatchRow saved = transport(row, row.sessionId(), row.creationPlanJson(), row.promptJson(), row.promptSha256(), null, code, message);
        var session = mapper.findSession(row.sessionId()).orElseThrow();
        states.updateSession(states.sessionState(session, SessionState.COMPLETED));
        return transition(saved, TemplateBatchState.FAILED, LifecycleEvent.VERIFICATION_FAIL);
    }

    @Transactional
    public void attachForStop(TemplateTaskBatchRow row, List<OpenCodeClient.SessionAttestation> attestations) {
        var expected = json.readValue(row.creationPlanJson(), OpenCodeClient.SessionCreationPlan.class);
        var primary = mapper.findSession(row.sessionId()).orElseThrow();
        for (var attestation : attestations) {
            if (!expected.equals(attestation.plan())) throw new ConflictException("TEMPLATE_SESSION_ATTESTATION_CHANGED", "待停止会话身份不一致");
            if (mapper.listSessions(row.taskId()).stream().anyMatch(session -> attestation.remoteId().equals(session.externalSessionId()))) continue;
            if (primary.externalSessionId() == null) {
                states.updateSession(new ExecutionSessionRow(primary.id(), primary.taskId(), primary.stageId(), primary.attemptId(),
                        attestation.remoteId(), "RUNNING", primary.createdAt(), null, primary.version(), "UNAVAILABLE"));
                primary = mapper.findSession(primary.id()).orElseThrow();
            } else {
                String cleanupId = row.id() + "-cleanup-" + TemplateGitEvidenceCollector.hash(attestation.remoteId()).substring(0, 24);
                var extra = new ExecutionSessionRow(cleanupId, primary.taskId(), primary.stageId(), primary.attemptId(),
                        null, "CREATING", Instant.now().toString(), null, 0);
                states.createSession(extra);
                states.updateSession(new ExecutionSessionRow(extra.id(), extra.taskId(), extra.stageId(), extra.attemptId(),
                        attestation.remoteId(), "RUNNING", extra.createdAt(), null, 0, "UNAVAILABLE"));
            }
        }
    }

    public void sessionStopped(ExecutionSessionRow session) {
        if (!SessionState.valueOf(session.state()).terminal()) states.updateSession(states.sessionState(session, SessionState.ABORTED));
    }

    @Transactional
    public TemplateTaskBatchRow transition(TemplateTaskBatchRow row, TemplateBatchState state, LifecycleEvent event) {
        var changed = new TemplateTaskBatchRow(row.id(), row.taskId(), row.attemptId(), row.sessionId(), row.ordinal(), row.purpose(),
                row.inputJson(), row.inputSha256(), state.name(), row.creationPlanJson(), row.promptJson(), row.promptSha256(),
                row.outputJson(), row.errorCode(), row.errorMessage(), row.createdAt(), Instant.now().toString(), row.version());
        lifecycle.transition(subject(row), row.state(), state.name(), event, null,
                Map.of("ordinal", row.ordinal(), "purpose", row.purpose()), () -> templates.updateBatchState(changed), TemplateBatchStore::conflict);
        return require(row.id());
    }

    private TemplateTaskBatchRow transport(TemplateTaskBatchRow row, String sessionId, String plan, String prompt, String promptHash,
                                             String output, String errorCode, String errorMessage) {
        var changed = new TemplateTaskBatchRow(row.id(), row.taskId(), row.attemptId(), sessionId, row.ordinal(), row.purpose(),
                row.inputJson(), row.inputSha256(), row.state(), plan, prompt, promptHash, output, errorCode, errorMessage,
                row.createdAt(), Instant.now().toString(), row.version());
        lifecycle.mutateWithoutTransition(() -> templates.updateBatchTransport(changed), TemplateBatchStore::conflict);
        return require(row.id());
    }

    public TemplateTaskBatchRow require(String id) { return templates.findBatch(id).orElseThrow(() -> new NotFoundException("模板批次不存在")); }
    public void requireRunning(String taskId, String attemptId) {
        var task = mapper.findTask(taskId).orElseThrow();
        var attempt = mapper.findAttempt(attemptId).orElseThrow();
        if (!task.state().equals("RUNNING") || !attempt.state().equals("RUNNING") || !attempt.taskId().equals(taskId)) {
            throw new ConflictException("TEMPLATE_EXECUTION_CHANGED", "任务状态已变化，旧批次不得继续执行");
        }
    }
    private static void requireState(TemplateTaskBatchRow row, TemplateBatchState expected) {
        if (!row.state().equals(expected.name())) throw conflict();
    }
    private static LifecycleTransitionService.Subject subject(TemplateTaskBatchRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.TEMPLATE_BATCH, row.id(), LifecycleScopeType.TASK, row.taskId());
    }
    private static ConflictException conflict() { return new ConflictException("TEMPLATE_BATCH_CONFLICT", "模板批次状态已变化，请刷新后重试"); }
    public record FrozenPrompt(String text, String messageId, String schemaId, Map<String, Object> schema) {
        public OpenCodeClient.PromptRequest request() {
            OpenCodeClient.ResponseFormat format = schemaId == null ? new OpenCodeClient.ResponseFormat.Text()
                    : new OpenCodeClient.ResponseFormat.JsonSchema(schemaId, schema, 0);
            return new OpenCodeClient.PromptRequest(text, null, null, format, messageId, List.of());
        }
    }
}
