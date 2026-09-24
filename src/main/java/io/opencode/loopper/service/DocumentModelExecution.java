package io.opencode.loopper.service;

import static io.opencode.loopper.domain.TemplateBatchState.*;
import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.DocumentTemplateModelRow;
import io.opencode.loopper.runtime.DocumentTemplateProfiles;
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
public class DocumentModelExecution {
    @org.springframework.beans.factory.annotation.Autowired(required = false) private io.opencode.loopper.service.RoleSessions roleSessions;
    private final DocumentModelStore store;
    private final OpenCodeClient runtime;
    private final org.springframework.beans.factory.ObjectProvider<CandidateRuntimeBindingService> bindings;
    private final MachineCandidateSubmission submissions;
    private final DocumentModelPrompt prompts;
    private final DocumentTemplateStorage storage;
    private final ObjectMapper json;
    public DocumentModelExecution(DocumentModelStore store, OpenCodeClient runtime,
            org.springframework.beans.factory.ObjectProvider<CandidateRuntimeBindingService> bindings, MachineCandidateSubmission submissions,
            DocumentModelPrompt prompts, DocumentTemplateStorage storage, ObjectMapper json) {
        this.store = store; this.runtime = runtime; this.bindings = bindings; this.submissions = submissions;
        this.prompts = prompts; this.storage = storage; this.json = json;
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DocumentTemplateModelRow advance(String id, DocumentTemplateService.Contract contract) {
        var row = store.require(id);
        if (TemplateBatchState.valueOf(row.state()).terminal()) return row;
        store.requireActive(row.runId());
        if (contract.timeoutEnabled() && Instant.now().isAfter(Instant.parse(row.createdAt()).plusSeconds(contract.attemptTimeoutSeconds())))
            throw failure("DOCUMENT_MODEL_TIMEOUT", "本批分析时限已耗尽，已保留冻结输入和已接受结果");
        return switch (TemplateBatchState.valueOf(row.state())) {
            case PREPARED -> prepare(row, contract);
            case CREATING -> create(row);
            case PROMPT_READY -> store.transition(row, DISPATCHING, LifecycleEvent.DISPATCH, null);
            case DISPATCHING -> dispatch(row);
            case RUNNING -> poll(row, contract);
            default -> row;
        };
    }
    private DocumentTemplateModelRow prepare(DocumentTemplateModelRow row, DocumentTemplateService.Contract contract) {
        var parts = contract.model().split("/", 2);
        var model = new OpenCodeClient.OpenCodeModel(parts[0], parts[1], null);
        byte[] nonce = new byte[32]; new SecureRandom().nextBytes(nonce);
        var plan = runtime.prepareSessionCreation(storage.runtimeDirectory(row.runId()),
                "需求模板 " + row.candidateKind() + " " + row.ordinal(), model,
                DocumentTemplateProfiles.profile(MachineCandidateKind.valueOf(row.candidateKind())),
                Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
        plan = RoleSessions.prepare(roleSessions, plan, "DOCUMENT_TEMPLATE_RUN", row.runId(), null);
        if (!plan.managed() || plan.internalMcpServer() == null) throw failure("DOCUMENT_MCP_REQUIRED", "需求模板需要托管模型环境的角色专属 MCP");
        return store.prepare(row, plan, new DocumentModelStore.FrozenPrompt(prompts.build(row, plan.internalMcpServer()),
                "msg_" + row.id().replace("-", "")));
    }
    private DocumentTemplateModelRow create(DocumentTemplateModelRow row) {
        var plan = plan(row);
        var lookup = runtime.findSessionsByExactTitle(plan);
        if (!lookup.supported()) throw failure("DOCUMENT_SESSION_LOOKUP_UNAVAILABLE", "无法核对已创建的分析会话，不能重复创建");
        if (lookup.matches().size() > 1) throw failure("DOCUMENT_SESSION_AMBIGUOUS", "发现多个同源会话，必须确认全部停止后恢复");
        store.requireActive(row.runId());
        var attestation = lookup.matches().isEmpty() ? runtime.createSession(plan) : lookup.matches().getFirst();
        var binding = bindings.getIfAvailable();
        if (binding == null) throw failure("DOCUMENT_RUNTIME_GUARD_REQUIRED", "需求模板需要启用运行时代际校验");
        binding.bindInternalAttested(attestation, plan);
        return store.attach(row, attestation);
    }
    private DocumentTemplateModelRow dispatch(DocumentTemplateModelRow row) {
        var owner = store.requireActive(row.runId());
        var plan = plan(row);
        var request = prompt(row).request();
        var remote = remote(row);
        var kind = MachineCandidateKind.valueOf(row.candidateKind());
        if (submissions.find(row.id()).isEmpty()) {
            submissions.open(new MachineCandidateSubmission.OpenCommand(row.id(),
                    MachineCandidateSubmission.CandidateScope.project(owner.projectId()),
                    new MachineCandidateSubmission.CandidateOwnerRef(MachineCandidateSubmission.CandidateOwnerType.DOCUMENT_TEMPLATE_MODEL_RUN, row.id()),
                    kind, kind.name(), row.generation(), row.version(), MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                    kind.name(), plan.runtimeGenerationId(), row.externalSessionId(), kind.maximumAttempts()));
        }
        runtime.restoreDesignTurn(remote, plan.profile(), plan.model(), request.messageId());
        var lookup = runtime.findPromptMessage(remote, request, row.promptSha256());
        if (!lookup.supported()) throw failure("DOCUMENT_PROMPT_LOOKUP_UNAVAILABLE", "分析请求送达状态未知，保留原请求等待恢复");
        if (lookup.exists() && !row.promptSha256().equals(lookup.verifiedRequestSha256()))
            throw failure("DOCUMENT_PROMPT_CHANGED", "远端消息与冻结分析请求不一致");
        if (!lookup.exists()) { store.requireActive(row.runId()); runtime.promptAsync(remote, request); }
        return store.transition(row, RUNNING, LifecycleEvent.START, null);
    }
    private DocumentTemplateModelRow poll(DocumentTemplateModelRow row, DocumentTemplateService.Contract contract) {
        var plan = plan(row); var remote = remote(row);
        runtime.restoreDesignTurn(remote, plan.profile(), plan.model(), prompt(row).messageId());
        var status = runtime.sessionStatus(remote);
        if (status.retrying() || !status.completed() && !status.failed()) return row;
        row = store.require(row.id());
        if ((status.failed() || row.outputJson() == null) && "3".equals(contract.version()) && !contract.autoDevelopment()) {
            var candidate = submissions.find(row.id());
            if (candidate.isPresent() && !candidate.get().state().terminal())
                submissions.close(new MachineCandidateSubmission.CloseCommand(row.id(), candidate.get().version()));
            return store.transition(row, FAILED, LifecycleEvent.VERIFICATION_FAIL,
                    status.failed() ? "DOCUMENT_MODEL_FAILED" : "DOCUMENT_SUBMISSION_MISSING");
        }
        if (status.failed()) throw failure("DOCUMENT_MODEL_FAILED", "分析会话失败，已保留本次输入和输出证据");
        if (row.outputJson() == null) throw failure("DOCUMENT_SUBMISSION_MISSING", "模型已结束但没有提交有效候选，请检查预算或模型配置后恢复");
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
    private OpenCodeClient.SessionCreationPlan plan(DocumentTemplateModelRow row) {
        return json.readValue(row.creationPlanJson(), OpenCodeClient.SessionCreationPlan.class);
    }
    private DocumentModelStore.FrozenPrompt prompt(DocumentTemplateModelRow row) {
        var prompt = json.readValue(row.promptJson(), DocumentModelStore.FrozenPrompt.class);
        if (!OpenCodeClient.promptRequestSha256(prompt.request()).equals(row.promptSha256()))
            throw failure("DOCUMENT_PROMPT_CHANGED", "冻结分析请求校验失败");
        return prompt;
    }
    private OpenCodeClient.OpenCodeSession remote(DocumentTemplateModelRow row) {
        var plan = plan(row);
        return new OpenCodeClient.OpenCodeSession(row.externalSessionId(), plan.canonicalDirectory(), plan.runtimeGenerationId(), plan.internalMcpServer());
    }
    private static SessionFailure failure(String code, String message) { return new SessionFailure(code, message); }
}
