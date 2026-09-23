package io.opencode.loopper.service;

import static io.opencode.loopper.domain.TemplateBatchState.*;
import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.SourceTemplateModelRow;
import io.opencode.loopper.runtime.SourceTemplateProfiles;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Exact persisted Session and message recovery; candidate acceptance alone never proves remote termination. */
@Service
public class SourceModelExecution {
    private final SourceModelStore store;
    private final OpenCodeClient runtime;
    private final org.springframework.beans.factory.ObjectProvider<CandidateRuntimeBindingService> bindings;
    private final MachineCandidateSubmission submissions;
    private final SourceModelPrompt prompts;
    private final SourceSnapshotStorage storage;
    private final ObjectMapper json;
    public SourceModelExecution(SourceModelStore store, OpenCodeClient runtime,
            org.springframework.beans.factory.ObjectProvider<CandidateRuntimeBindingService> bindings, MachineCandidateSubmission submissions,
            SourceModelPrompt prompts, SourceSnapshotStorage storage, ObjectMapper json) {
        this.store = store; this.runtime = runtime; this.bindings = bindings; this.submissions = submissions;
        this.prompts = prompts; this.storage = storage; this.json = json;
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SourceTemplateModelRow advance(String id, io.opencode.loopper.template.SourceTemplateContract contract) {
        var row = store.require(id);
        if (TemplateBatchState.valueOf(row.state()).terminal()) return row;
        store.requireActive(row.runId());
        if (contract.timeoutEnabled() && row.startedAt() != null
                && Instant.now().isAfter(Instant.parse(row.startedAt()).plusSeconds(contract.attemptTimeoutSeconds())))
            throw failure("SOURCE_MODEL_TIMEOUT", "本批分析时限已耗尽，已保留冻结输入和已接受结果");
        return switch (TemplateBatchState.valueOf(row.state())) {
            case PREPARED -> prepare(row, contract);
            case CREATING -> create(row);
            case PROMPT_READY -> store.transition(row, DISPATCHING, LifecycleEvent.DISPATCH, null);
            case DISPATCHING -> dispatch(row);
            case RUNNING -> poll(row, contract);
            default -> row;
        };
    }
    private SourceTemplateModelRow prepare(SourceTemplateModelRow row, io.opencode.loopper.template.SourceTemplateContract contract) {
        var parts = contract.model().split("/", 2);
        var model = new OpenCodeClient.OpenCodeModel(parts[0], parts[1], null);
        byte[] nonce = new byte[32]; new SecureRandom().nextBytes(nonce);
        var plan = runtime.prepareSessionCreation(storage.directory(row.runId()),
                "源码模板 " + row.candidateKind() + " " + row.ordinal(), model,
                SourceTemplateProfiles.profile(MachineCandidateKind.valueOf(row.candidateKind())),
                Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
        if (!plan.managed() || plan.internalMcpServer() == null) throw failure("SOURCE_MCP_REQUIRED", "源码模板需要托管模型环境的角色专属 MCP");
        return store.prepare(row, plan, new DocumentModelStore.FrozenPrompt(prompts.build(row, plan.internalMcpServer()),
                "msg_" + row.id().replace("-", "")));
    }
    private SourceTemplateModelRow create(SourceTemplateModelRow row) {
        var plan = plan(row);
        var lookup = runtime.findSessionsByExactTitle(plan);
        if (!lookup.supported()) throw failure("SOURCE_SESSION_LOOKUP_UNAVAILABLE", "无法核对已创建的分析会话，不能重复创建");
        if (lookup.matches().size() > 1) throw failure("SOURCE_SESSION_AMBIGUOUS", "发现多个同源会话，必须确认全部停止后恢复");
        store.requireActive(row.runId());
        var attestation = lookup.matches().isEmpty() ? runtime.createSession(plan) : lookup.matches().getFirst();
        var binding = bindings.getIfAvailable();
        if (binding == null) throw failure("SOURCE_RUNTIME_GUARD_REQUIRED", "源码模板需要启用运行时代际校验");
        binding.bindInternalAttested(attestation, plan);
        return store.attach(row, attestation);
    }
    private SourceTemplateModelRow dispatch(SourceTemplateModelRow row) {
        var owner = store.requireActive(row.runId());
        var plan = plan(row);
        var request = prompt(row).request();
        var remote = remote(row);
        var kind = MachineCandidateKind.valueOf(row.candidateKind());
        if (submissions.find(row.id()).isEmpty()) {
            submissions.open(new MachineCandidateSubmission.OpenCommand(row.id(),
                    MachineCandidateSubmission.CandidateScope.project(owner.projectId()),
                    new MachineCandidateSubmission.CandidateOwnerRef(MachineCandidateSubmission.CandidateOwnerType.SOURCE_TEMPLATE_MODEL_RUN, row.id()),
                    kind, kind.name(), row.generation(), row.version(), MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                    kind.name(), plan.runtimeGenerationId(), row.externalSessionId(), kind.maximumAttempts()));
        }
        runtime.restoreDesignTurn(remote, plan.profile(), plan.model(), request.messageId());
        var lookup = runtime.findPromptMessage(remote, request, row.promptSha256());
        if (!lookup.supported()) throw failure("SOURCE_PROMPT_LOOKUP_UNAVAILABLE", "分析请求送达状态未知，保留原请求等待恢复");
        if (lookup.exists() && !row.promptSha256().equals(lookup.verifiedRequestSha256()))
            throw failure("SOURCE_PROMPT_CHANGED", "远端消息与冻结分析请求不一致");
        if (!lookup.exists()) { store.requireActive(row.runId()); runtime.promptAsync(remote, request); }
        return store.transition(row, RUNNING, LifecycleEvent.START, null);
    }
    private SourceTemplateModelRow poll(SourceTemplateModelRow row, io.opencode.loopper.template.SourceTemplateContract contract) {
        var plan = plan(row); var remote = remote(row);
        runtime.restoreDesignTurn(remote, plan.profile(), plan.model(), prompt(row).messageId());
        var status = runtime.sessionStatus(remote);
        if (status.retrying() || !status.completed() && !status.failed()) {
            var current = store.require(row.id());
            if (current.acceptedAt() == null || Instant.now().isBefore(Instant.parse(current.acceptedAt()).plusSeconds(60))) return current;
            runtime.abortWithConfirmation(remote);
            return store.transition(current, VALIDATED, LifecycleEvent.COMPLETE, "SOURCE_ACCEPTED_SESSION_STOPPED");
        }
        row = store.require(row.id());
        if ((status.failed() || row.outputJson() == null)) {
            var candidate = submissions.find(row.id());
            if (candidate.isPresent() && !candidate.get().state().terminal())
                submissions.close(new MachineCandidateSubmission.CloseCommand(row.id(), candidate.get().version()));
            return store.transition(row, FAILED, LifecycleEvent.VERIFICATION_FAIL,
                    status.failed() ? "SOURCE_MODEL_FAILED" : "SOURCE_SUBMISSION_MISSING");
        }
        if (status.failed()) throw failure("SOURCE_MODEL_FAILED", "分析会话失败，已保留本次输入和输出证据");
        if (row.outputJson() == null) throw failure("SOURCE_SUBMISSION_MISSING", "模型已结束但没有提交有效候选，请检查预算或模型配置后恢复");
        return store.transition(row, VALIDATED, LifecycleEvent.COMPLETE, null);
    }
    /** Caller serializes local I/O before this method; unknown stop retains the blocking state. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean stop(String id) {
        var row = store.require(id);
        if (TemplateBatchState.valueOf(row.state()).terminal()) return true;
        if (!row.state().equals("STOPPING")) row = store.transition(row, STOPPING, LifecycleEvent.CANCEL, null);
        if (row.creationPlanJson() != null) {
            var lookup = runtime.findSessionsByExactTitle(plan(row));
            if (!lookup.supported()) return false;
            // Exact-title query also covers the create-response-loss window and multiple recovered matches.
            for (var match : lookup.matches()) {
                if (!match.plan().equals(plan(row))) return false;
                runtime.abortWithConfirmation(match.session());
            }
            if (row.externalSessionId() != null && lookup.matches().stream().noneMatch(match -> match.remoteId().equals(store.require(id).externalSessionId())))
                runtime.abortWithConfirmation(remote(row));
        }
        var candidate = submissions.find(row.id());
        if (candidate.isPresent() && !candidate.get().state().terminal())
            submissions.close(new MachineCandidateSubmission.CloseCommand(row.id(), candidate.get().version()));
        store.transition(store.require(id), STOPPED, LifecycleEvent.ABORT, null);
        return true;
    }
    private OpenCodeClient.SessionCreationPlan plan(SourceTemplateModelRow row) {
        return json.readValue(row.creationPlanJson(), OpenCodeClient.SessionCreationPlan.class);
    }
    private DocumentModelStore.FrozenPrompt prompt(SourceTemplateModelRow row) {
        var prompt = json.readValue(row.promptJson(), DocumentModelStore.FrozenPrompt.class);
        if (!OpenCodeClient.promptRequestSha256(prompt.request()).equals(row.promptSha256()))
            throw failure("SOURCE_PROMPT_CHANGED", "冻结分析请求校验失败");
        return prompt;
    }
    private OpenCodeClient.OpenCodeSession remote(SourceTemplateModelRow row) {
        var plan = plan(row);
        return new OpenCodeClient.OpenCodeSession(row.externalSessionId(), plan.canonicalDirectory(), plan.runtimeGenerationId(), plan.internalMcpServer());
    }
    private static SessionFailure failure(String code, String message) { return new SessionFailure(code, message); }
}
