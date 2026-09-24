package io.opencode.loopper.service;

import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.domain.SessionState;
import io.opencode.loopper.domain.TemplateBatchState;
import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TemplateTaskBatchRow;
import io.opencode.loopper.persistence.TemplateTaskMapper;
import io.opencode.loopper.persistence.TemplateCandidateSubmissionMapper;
import io.opencode.loopper.runtime.InternalMcpContractCatalog;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.template.TemplateAnalysis;
import io.opencode.loopper.template.TemplateContributionFacts;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Drives one frozen report batch. Every retry of remote I/O first uses exact persisted identity lookup. */
@Service
public class TemplateBatchExecution {
    @org.springframework.beans.factory.annotation.Autowired(required = false) private io.opencode.loopper.service.RoleSessions roleSessions;
    private final TemplateBatchStore store;
    private final TemplateTaskMapper templates;
    private final LoopperMapper mapper;
    private final OpenCodeClient openCode;
    private final TemplateAnalysisPromptFactory prompts;
    private final TemplateCandidateCodec codec;
    private final ObjectMapper json;
    private final TemplateCandidateSubmissionMapper submissions;
    private final TemplateBatchFinalization finalization;
    private final TemplateBatchResilience resilience;

    TemplateBatchExecution(TemplateBatchStore store, TemplateTaskMapper templates, LoopperMapper mapper,
                           OpenCodeClient openCode, TemplateAnalysisPromptFactory prompts,
                           TemplateCandidateCodec codec, ObjectMapper json, TemplateCandidateSubmissionMapper submissions,
                           TemplateBatchFinalization finalization, TemplateBatchResilience resilience) {
        this.store = store; this.templates = templates; this.mapper = mapper; this.openCode = openCode;
        this.prompts = prompts; this.codec = codec; this.json = json; this.submissions = submissions;
        this.finalization = finalization;
        this.resilience = resilience;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TemplateTaskBatchRow advance(TemplateTaskBatchRow batch, TemplateTaskContractFactory.Frozen contract) {
        TemplateTaskBatchRow row = store.require(batch.id());
        if (TemplateBatchState.valueOf(row.state()).terminal()) return row;
        store.requireRunning(row.taskId(), row.attemptId());
        if (row.state().equals("STOPPING")) { stop(row); return store.require(row.id()); }
        if (!resilience.due(row, row.state())) return row;
        if (row.state().equals("PREPARED") && resilience.dispatchBlocked(row)) return row;
        try {
            var next = switch (TemplateBatchState.valueOf(row.state())) {
                case PREPARED -> prepare(row, contract);
                case CREATING -> createRemote(row);
                case PROMPT_READY -> store.transition(row, TemplateBatchState.DISPATCHING, LifecycleEvent.DISPATCH);
                case DISPATCHING -> dispatch(row);
                case RUNNING -> poll(row, contract);
                default -> row;
            };
            resilience.succeeded(row, row.state());
            return next;
        } catch (RuntimeException failure) {
            resilience.failed(row, row.state(), failure);
            if (TemplateBatchFailurePolicy.transport(failure)) return store.require(row.id());
            throw failure;
        }
    }

    private TemplateTaskBatchRow prepare(TemplateTaskBatchRow row, TemplateTaskContractFactory.Frozen contract) {
        var run = templates.findRun(row.taskId()).orElseThrow();
        if (!io.opencode.loopper.template.SnapshotReview.batch(row.purpose()) && run.bypassCache() == 0 && run.repairRound() == 0 && row.generation() == 0) {
            String cached = templates.acceptedCachedOutput(row.inputSha256()).orElse(null);
            if (cached != null) return store.validated(row, validate(row, cached), true);
        }
        Input input = input(row);
        var configured = contract.spec().model();
        var model = new OpenCodeClient.OpenCodeModel(configured.providerId(), configured.modelId(), configured.thinking());
        // Text JSON keeps all repair authority on the server. Some OpenCode versions reject retryCount=0,
        // while using their default would authorize hidden retries outside the frozen two-round policy.
        Path root = Path.of(mapper.findTask(row.taskId()).orElseThrow().worktreePath());
        byte[] nonce = new byte[32]; new SecureRandom().nextBytes(nonce);
        var profile = input.snapshot() != null ? OpenCodeClient.SessionProfile.SNAPSHOT_CODE_REVIEW_NO_TOOLS : List.of("5", "6", "7", "8", "9", "10").contains(contract.definition().version())
                ? OpenCodeClient.SessionProfile.TEMPLATE_ANALYSIS_CANDIDATE_NO_TOOLS
                : OpenCodeClient.SessionProfile.TEMPLATE_ANALYSIS_NO_TOOLS;
        String text = RoleSessions.render(roleSessions, "TASK", row.taskId(), profile, null, () -> input.snapshot() != null ? codec.snapshotPrompt(row) : row.purpose().equals("REVIEW") ? prompts.review(input.units(), input.feedback())
                : prompts.contributor(input.person(), input.reviews(), input.units(), input.feedback()));
        var plan = openCode.prepareSessionCreation(root, "模板报告分析 " + (row.ordinal() + 1), model,
                profile, Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
        plan = RoleSessions.prepare(roleSessions, plan, "TASK", row.taskId(), null);
        if ((profile == OpenCodeClient.SessionProfile.TEMPLATE_ANALYSIS_CANDIDATE_NO_TOOLS || profile == OpenCodeClient.SessionProfile.SNAPSHOT_CODE_REVIEW_NO_TOOLS)) {
            if (!plan.managed()) throw unavailable("TEMPLATE_MCP_REQUIRED", "新模板分析需要托管 OpenCode 的专用 MCP 提交工具");
            text = prompts.internal(text, row.id(), plan.internalMcpServer() + "_"
                    + InternalMcpContractCatalog.TEMPLATE_TOOL);
            if (input.snapshot() != null) text = text.replace("不调用其他工具", "仅调用本合同允许的快照读取、合同查询和候选提交工具");
        }
        var prompt = new TemplateBatchStore.FrozenPrompt(text, "msg_" + row.id().replace("-", ""), null, null);
        return store.prepareSession(row, plan, prompt);
    }

    private TemplateTaskBatchRow createRemote(TemplateTaskBatchRow row) {
        var plan = plan(row);
        var lookup = openCode.findSessionsByExactTitle(plan);
        if (!lookup.supported()) throw unavailable("TEMPLATE_SESSION_LOOKUP_UNAVAILABLE", "当前运行环境无法核对已发起的会话，请检查运行环境");
        if (lookup.matches().size() > 1) {
            store.attachForStop(row, lookup.matches());
            throw unavailable("TEMPLATE_SESSION_AMBIGUOUS", "发现多个同源分析会话，须先确认全部停止");
        }
        resilience.succeeded(row, row.state());
        if (lookup.matches().isEmpty() && resilience.dispatchBlocked(row.taskId())) return row;
        store.requireRunning(row.taskId(), row.attemptId());
        var attestation = lookup.matches().isEmpty() ? openCode.createSession(plan) : lookup.matches().getFirst();
        return store.attachRemote(row, attestation);
    }

    private TemplateTaskBatchRow dispatch(TemplateTaskBatchRow row) {
        var remote = remote(row);
        if (store.hasContinuation(row.id())) {
            var accepted = submissions.accepted(row.id());
            if (accepted.isPresent()) {
                // A late receipt can make the persisted continuation unnecessary. Stop proof still applies.
                var running = store.transition(row, TemplateBatchState.RUNNING, LifecycleEvent.START);
                return poll(running, null);
            }
        }
        var request = prompt(row).request();
        openCode.restoreDesignTurn(remote, plan(row).profile(), plan(row).model(), request.messageId());
        var lookup = openCode.findPromptMessage(remote, request, row.promptSha256());
        if (!lookup.supported()) throw unavailable("TEMPLATE_PROMPT_LOOKUP_UNAVAILABLE", "无法核对分析请求是否已送达，已保留本次请求，请检查运行环境");
        if (lookup.exists() && !row.promptSha256().equals(lookup.verifiedRequestSha256())) {
            throw unavailable("TEMPLATE_PROMPT_IDENTITY_CHANGED", "远端分析请求与冻结内容不一致");
        }
        resilience.succeeded(row, row.state());
        if (!lookup.exists()) {
            if (resilience.dispatchBlocked(row.taskId())) return row;
            if (store.hasContinuation(row.id())) {
                var previous = store.previousPrompt(row.id());
                openCode.restoreDesignTurn(remote, plan(row).profile(), plan(row).model(), previous.messageId());
                var status = openCode.sessionStatus(remote);
                if (status.failed()) throw unavailable("TEMPLATE_MODEL_FAILED", "上次分析会话已失败，不能自动续接");
                if (status.retrying() || !status.completed()) return row;
                openCode.restoreDesignTurn(remote, plan(row).profile(), plan(row).model(), request.messageId());
            }
            store.requireRunning(row.taskId(), row.attemptId());
            openCode.promptAsync(remote, request);
        }
        return store.transition(row, TemplateBatchState.RUNNING, LifecycleEvent.START);
    }

    private TemplateTaskBatchRow poll(TemplateTaskBatchRow row, TemplateTaskContractFactory.Frozen contract) {
        var remote = remote(row);
        var request = prompt(row).request();
        openCode.restoreDesignTurn(remote, plan(row).profile(), plan(row).model(), request.messageId());
        var observation = finalization.poll(row, remote, output -> validate(row, output));
        if (observation.handled() != null) return observation.handled();
        var status = observation.status();
        if (status.retrying() || !status.completed() && !status.failed()) return row;
        if (status.failed()) {
            return store.candidateFailed(row, "TEMPLATE_MODEL_FAILED", "分析会话已失败，其他批次完成后可选择重试");
        }
        if ((plan(row).profile() == OpenCodeClient.SessionProfile.TEMPLATE_ANALYSIS_CANDIDATE_NO_TOOLS || plan(row).profile() == OpenCodeClient.SessionProfile.SNAPSHOT_CODE_REVIEW_NO_TOOLS)) {
            var accepted = submissions.accepted(row.id());
            if (accepted.isPresent()) return store.validated(row, validate(row, accepted.get()), false);
            var missing = openCode.sessionResult(remote);
            if ((io.opencode.loopper.template.SnapshotReview.batch(row.purpose()) || List.of("6", "7", "8", "9", "10").contains(contract.definition().version()))
                    && "OPENCODE_OUTPUT_LENGTH_EXHAUSTED".equals(missing.errorType())) {
                try { return store.prepareContinuation(row); }
                catch (SessionFailure failure) {
                    if ("TEMPLATE_ANALYSIS_STALLED".equals(failure.code()))
                        return store.candidateFailed(row, failure.code(), failure.getMessage());
                    throw failure;
                }
            }
            return store.candidateFailed(row, "TEMPLATE_SUBMISSION_MISSING",
                    "模型会话已结束，但没有通过 MCP 提交有效分析结果；其他批次完成后可选择重试");
        }
        var result = openCode.sessionResult(remote);
        if ("OPENCODE_OUTPUT_LENGTH_EXHAUSTED".equals(result.errorType())) {
            return store.candidateFailed(row, "OPENCODE_OUTPUT_LENGTH_EXHAUSTED", "模型生成长度耗尽，未返回完整分析结果；其他批次完成后可选择重试");
        }
        if (result.structuredRetryCount() != 0) throw unavailable("TEMPLATE_UNBUDGETED_RETRY", "运行环境发生未授权的结构化重试");
        String output = result.hasStructured() ? json.writeValueAsString(result.structured()) : openCode.sessionOutput(remote);
        try { return store.validated(row, validate(row, output), false); }
        catch (BadRequestException invalid) { return store.candidateFailed(row, invalid.code(), invalid.getMessage()); }
    }

    private String validate(TemplateTaskBatchRow row, String output) {
        Input input = input(row);
        if (input.snapshot() != null) return codec.snapshot(row, output);
        return row.purpose().equals("REVIEW") ? codec.review(output, input.units())
                : codec.contributor(output, input.person().author().identity(), input.person().evidenceIds());
    }

    /** Called only after any in-flight local batch call has returned or been interrupted and joined. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean stop(TemplateTaskBatchRow batch) {
        TemplateTaskBatchRow row = store.require(batch.id());
        if (TemplateBatchState.valueOf(row.state()).terminal()) return true;
        if (!row.state().equals("STOPPING")) row = store.transition(row, TemplateBatchState.STOPPING, LifecycleEvent.CANCEL);
        if (!resilience.due(row, "STOP")) return false;
        try {
            if (row.creationPlanJson() != null && !stopRemoteSessions(row)) return false;
            store.transition(store.require(row.id()), TemplateBatchState.STOPPED, LifecycleEvent.ABORT);
            resilience.succeeded(row, "STOP");
            return true;
        } catch (RuntimeException unavailable) {
            resilience.failed(store.require(row.id()), "STOP", unavailable);
            return false;
        }
    }

    private boolean stopRemoteSessions(TemplateTaskBatchRow row) {
        var primary = mapper.findSession(row.sessionId()).orElseThrow();
        // Exact listing is needed only while creation identity is unknown. An attached Session can be stopped directly.
        if (primary.externalSessionId() == null && !SessionState.valueOf(primary.state()).terminal()) {
            var lookup = openCode.findSessionsByExactTitle(plan(row));
            if (!lookup.supported()) throw unavailable("TEMPLATE_SESSION_LOOKUP_UNAVAILABLE", "无法核对创建中的会话");
            store.attachForStop(row, lookup.matches());
            if (lookup.matches().isEmpty()) store.sessionStopped(primary);
        }
        boolean stopped = true;
        for (var session : mapper.listSessions(row.taskId())) {
                if (!session.id().equals(row.sessionId()) && !session.id().startsWith(row.id() + "-cleanup-")) continue;
                if (!session.attemptId().equals(row.attemptId()) || SessionState.valueOf(session.state()).terminal()) continue;
                if (session.externalSessionId() == null || !stopSession(row, session)) stopped = false;
        }
        return stopped;
    }

    private boolean stopSession(TemplateTaskBatchRow row, ExecutionSessionRow session) {
        var plan = plan(row);
        var remote = new OpenCodeClient.OpenCodeSession(session.externalSessionId(), plan.canonicalDirectory(),
                plan.managed() ? plan.runtimeGenerationId() : null, plan.internalMcpServer());
        try {
            OpenCodeClient.SessionStatus status = null;
            try { status = openCode.sessionStatus(remote); }
            catch (RuntimeException unreadable) { /* Status and confirmed abort are independent evidence paths. */ }
            if (status == null || !status.completed() && !status.failed())
                CandidateSessionTerminationProof.from(openCode.abortWithConfirmation(remote));
            store.sessionStopped(session);
            return true;
        } catch (RuntimeException unknown) {
            resilience.failed(store.require(row.id()), "STOP", unknown);
            return false;
        }
    }

    private Input input(TemplateTaskBatchRow row) { return json.readValue(row.inputJson(), Input.class); }
    private OpenCodeClient.SessionCreationPlan plan(TemplateTaskBatchRow row) { return json.readValue(row.creationPlanJson(), OpenCodeClient.SessionCreationPlan.class); }
    private TemplateBatchStore.FrozenPrompt prompt(TemplateTaskBatchRow row) {
        var value = json.readValue(row.promptJson(), TemplateBatchStore.FrozenPrompt.class);
        if (!row.promptSha256().equals(OpenCodeClient.promptRequestSha256(value.request()))) {
            throw unavailable("TEMPLATE_PROMPT_CORRUPTED", "冻结分析请求校验失败");
        }
        return value;
    }
    private OpenCodeClient.OpenCodeSession remote(TemplateTaskBatchRow row) {
        var plan = plan(row);
        var session = mapper.findSession(row.sessionId()).orElseThrow();
        return new OpenCodeClient.OpenCodeSession(session.externalSessionId(), plan.canonicalDirectory(),
                plan.managed() ? plan.runtimeGenerationId() : null, plan.internalMcpServer());
    }
    private static SessionFailure unavailable(String code, String message) { return new SessionFailure(code, message); }
    public record Input(List<TemplateAnalysis.Unit> units, TemplateContributionFacts.Person person,
                         List<TemplateAnalysis.UnitReview> reviews, String feedback,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                         io.opencode.loopper.template.SnapshotReview.Input snapshot) {
        public Input(List<TemplateAnalysis.Unit> units, TemplateContributionFacts.Person person, List<TemplateAnalysis.UnitReview> reviews, String feedback) {
            this(units, person, reviews, feedback, null);
        }
    }
}
