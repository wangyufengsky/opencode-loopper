package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.template.DocumentModelInput;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Short persisted checkpoints around remote calls. Role state remains independent of template completion. */
@Service
public class DocumentModelStore {
    private final DocumentTemplateModelMapper models;
    private final DocumentTemplateMapper runs;
    private final LifecycleTransitionService lifecycle;
    private final ObjectMapper json;
    public DocumentModelStore(DocumentTemplateModelMapper models, DocumentTemplateMapper runs,
            LifecycleTransitionService lifecycle, ObjectMapper json) {
        this.models = models; this.runs = runs; this.lifecycle = lifecycle; this.json = json;
    }
    @Transactional
    public DocumentTemplateModelRow create(String runId, MachineCandidateKind kind, int ordinal,
            int generation, DocumentModelInput input) {
        requireActive(runId);
        var existing = models.exact(runId, kind.name(), ordinal, generation);
        String body = json.writeValueAsString(input), hash = hash(body);
        if (existing.isPresent()) {
            if (!existing.get().inputSha256().equals(hash)) throw conflict();
            return existing.get();
        }
        String now = Instant.now().toString();
        var row = new DocumentTemplateModelRow(UUID.randomUUID().toString(), runId, kind.name(), ordinal, generation,
                "PREPARED", body, hash, null, null, null, null, null, null, null, now, now, 0);
        lifecycle.create(subject(row), row.state(), Map.of("role", kind.name()), () -> models.insert(row), DocumentModelStore::conflict);
        return require(row.id());
    }
    @Transactional
    public DocumentTemplateModelRow prepare(DocumentTemplateModelRow row, OpenCodeClient.SessionCreationPlan plan, FrozenPrompt prompt) {
        requireActive(row.runId());
        if (models.prepare(row.id(), row.version(), json.writeValueAsString(plan), json.writeValueAsString(prompt),
                OpenCodeClient.promptRequestSha256(prompt.request()), Instant.now().toString()) != 1) throw conflict();
        return transition(require(row.id()), TemplateBatchState.CREATING, LifecycleEvent.PREPARE, null);
    }
    @Transactional
    public DocumentTemplateModelRow attach(DocumentTemplateModelRow row, OpenCodeClient.SessionAttestation attestation) {
        requireActive(row.runId());
        var plan = json.readValue(row.creationPlanJson(), OpenCodeClient.SessionCreationPlan.class);
        if (attestation == null || !plan.equals(attestation.plan())
                || attestation.attestationKind() != OpenCodeClient.SessionAttestationKind.LOCAL_REQUEST_ATTESTED) throw conflict();
        if (models.attach(row.id(), row.version(), attestation.remoteId(), Instant.now().toString()) != 1) throw conflict();
        return transition(require(row.id()), TemplateBatchState.PROMPT_READY, LifecycleEvent.PREPARATION_SUCCEEDED, null);
    }
    public DocumentTemplateModelRow transition(DocumentTemplateModelRow row, TemplateBatchState next, LifecycleEvent event, String code) {
        lifecycle.transition(subject(row), row.state(), next.name(), event, code, Map.of(),
                () -> models.transition(row.id(), row.version(), next.name(), code, Instant.now().toString()), DocumentModelStore::conflict);
        return require(row.id());
    }
    public DocumentTemplateModelRow require(String id) { return models.find(id).orElseThrow(DocumentModelStore::conflict); }
    /** Only a proved stopped attempt can be replaced; all prior transport and accepted bytes remain immutable. */
    @Transactional
    public void recover(String runId, DocumentTemplateService.Contract contract) {
        var run = runs.find(runId).orElseThrow(DocumentModelStore::conflict);
        if (!run.state().equals("WAITING_INPUT") || !models.active(runId).isEmpty()) throw conflict();
        var stopped = models.stoppedLatest(runId);
        if (stopped.size() >= 100) throw new BadRequestException("DOCUMENT_RECOVERY_RANGE_EXCEEDED", "待恢复批次数量异常，请检查停止记录");
        for (var previous : stopped) {
            if (previous.attempt() + 1 >= contract.maxStageAttempts())
                throw new BadRequestException("DOCUMENT_MODEL_RECOVERY_EXHAUSTED", "本批分析尝试预算已耗尽，保留已有证据，请调整输入后重新发起");
            String now = Instant.now().toString();
            var row = new DocumentTemplateModelRow(UUID.randomUUID().toString(), runId, previous.candidateKind(),
                    previous.ordinal(), previous.generation(), "PREPARED", previous.inputJson(), previous.inputSha256(),
                    null, null, null, null, null, null, null, now, now, 0, previous.attempt() + 1);
            lifecycle.create(subject(row), row.state(), Map.of("replaces", previous.id()),
                    () -> models.insert(row), DocumentModelStore::conflict);
        }
    }
    @Transactional
    public DocumentTemplateModelRow retry(String id, long expectedVersion, boolean manual, DocumentTemplateService.Contract contract) {
        var previous = require(id);
        requireActive(previous.runId());
        if (previous.version() != expectedVersion || !java.util.Set.of("FAILED", "STOPPED").contains(previous.state())) throw conflict();
        var current = models.exact(previous.runId(), previous.candidateKind(), previous.ordinal(), previous.generation()).orElseThrow();
        if (!current.id().equals(id)) return current;
        if (!manual && previous.attempt() >= Math.min(2, Math.max(0, contract.maxStageAttempts() - 1))) return previous;
        String now = Instant.now().toString();
        var next = new DocumentTemplateModelRow(UUID.randomUUID().toString(), previous.runId(), previous.candidateKind(),
                previous.ordinal(), previous.generation(), "PREPARED", previous.inputJson(), previous.inputSha256(),
                null, null, null, null, null, null, null, now, now, 0, previous.attempt() + 1);
        lifecycle.create(subject(next), next.state(), Map.of("replaces", id, "manual", manual),
                () -> models.insert(next), DocumentModelStore::conflict);
        return require(next.id());
    }

    public DocumentTemplateRunRow requireActive(String id) {
        var run = runs.find(id).orElseThrow(DocumentModelStore::conflict);
        if (DocumentTemplateState.valueOf(run.state()).terminal() || run.state().equals("WAITING_INPUT")
                || run.state().equals("STOPPING")) throw conflict();
        return run;
    }
    private LifecycleTransitionService.Subject subject(DocumentTemplateModelRow row) {
        var run = runs.find(row.runId()).orElseThrow(DocumentModelStore::conflict);
        return new LifecycleTransitionService.Subject(LifecycleMachineType.DOCUMENT_MODEL_RUN, row.id(), LifecycleScopeType.PROJECT, run.projectId());
    }
    static String hash(String text) { return DocumentTemplateStorage.hash(text.getBytes(StandardCharsets.UTF_8)); }
    private static ConflictException conflict() { return new ConflictException("DOCUMENT_MODEL_VERSION_CONFLICT", "文档角色运行已变化，请刷新状态"); }
    public record FrozenPrompt(String text, String messageId) {
        public OpenCodeClient.PromptRequest request() {
            return new OpenCodeClient.PromptRequest(text, null, null, new OpenCodeClient.ResponseFormat.Text(), messageId, java.util.List.of());
        }
    }
}
