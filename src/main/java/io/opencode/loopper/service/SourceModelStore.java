package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.template.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Short checkpoints retain remote identities and accepted output across stop and recovery. */
@Service
public class SourceModelStore {
    private final SourceTemplateModelMapper models;
    private final SourceTemplateAdmission admission;
    private final LifecycleTransitionService lifecycle;
    private final ObjectMapper json;
    public SourceModelStore(SourceTemplateModelMapper models, SourceTemplateAdmission admission,
            LifecycleTransitionService lifecycle, ObjectMapper json) {
        this.models = models; this.admission = admission; this.lifecycle = lifecycle; this.json = json;
    }
    @Transactional
    public SourceTemplateModelRow create(String run, MachineCandidateKind kind, int ordinal, int generation, SourceDesign.Input input) {
        requireActive(run);
        var previous = models.exact(run, kind.name(), ordinal, generation);
        String body = json.writeValueAsString(input);
        if (previous.isPresent()) {
            if (!previous.get().inputSha256().equals(DocumentModelStore.hash(body))) throw conflict();
            return previous.get();
        }
        return insert(run, kind.name(), ordinal, generation, 0, body, Map.of("role", kind.name()));
    }
    private SourceTemplateModelRow insert(String run, String kind, int ordinal, int generation, int attempt,
            String input, Map<String, Object> metadata) {
        String now = Instant.now().toString();
        var row = new SourceTemplateModelRow(UUID.randomUUID().toString(), run, kind, ordinal, generation, attempt,
                "PREPARED", input, DocumentModelStore.hash(input), null, null, null, null, null, null, null, null, now, now, 0, null);
        lifecycle.create(subject(row), row.state(), metadata, () -> models.insert(row), SourceModelStore::conflict);
        return require(row.id());
    }
    @Transactional
    public SourceTemplateModelRow prepare(SourceTemplateModelRow row, OpenCodeClient.SessionCreationPlan plan,
            DocumentModelStore.FrozenPrompt prompt) {
        requireActive(row.runId());
        if (models.prepare(row.id(), row.version(), json.writeValueAsString(plan), json.writeValueAsString(prompt),
                OpenCodeClient.promptRequestSha256(prompt.request()), Instant.now().toString()) != 1) throw conflict();
        return transition(require(row.id()), TemplateBatchState.CREATING, LifecycleEvent.PREPARE, null);
    }
    @Transactional
    public SourceTemplateModelRow attach(SourceTemplateModelRow row, OpenCodeClient.SessionAttestation attestation) {
        requireActive(row.runId());
        var plan = json.readValue(row.creationPlanJson(), OpenCodeClient.SessionCreationPlan.class);
        if (attestation == null || !plan.equals(attestation.plan())
                || attestation.attestationKind() != OpenCodeClient.SessionAttestationKind.LOCAL_REQUEST_ATTESTED) throw conflict();
        if (models.attach(row.id(), row.version(), attestation.remoteId(), Instant.now().toString()) != 1) throw conflict();
        return transition(require(row.id()), TemplateBatchState.PROMPT_READY, LifecycleEvent.PREPARATION_SUCCEEDED, null);
    }
    public SourceTemplateModelRow transition(SourceTemplateModelRow row, TemplateBatchState state, LifecycleEvent event, String code) {
        lifecycle.transition(subject(row), row.state(), state.name(), event, code, Map.of(),
                () -> models.transition(row.id(), row.version(), state.name(), code, Instant.now().toString()), SourceModelStore::conflict);
        return require(row.id());
    }
    @Transactional
    public SourceTemplateModelRow retry(String id, long version, SourceDesign.Input replacement) {
        var previous = require(id); requireActive(previous.runId());
        var contract = json.readValue(admission.require(previous.runId()).contractJson(), SourceTemplateContract.class);
        if (previous.version() != version || !Set.of("FAILED", "STOPPED", "VALIDATED").contains(previous.state())
                || !models.exact(previous.runId(), previous.candidateKind(), previous.ordinal(), previous.generation())
                    .orElseThrow().id().equals(id)) throw conflict();
        if (previous.state().equals("VALIDATED") && replacement == null) return previous;
        boolean reuse = replacement == null && previous.outputJson() != null && previous.state().equals("STOPPED");
        if (!reuse && previous.attempt() + 1 >= contract.maxStageAttempts())
            throw new BadRequestException("SOURCE_MODEL_BUDGET_EXHAUSTED", "该批次执行预算已耗尽，保留原任务与已有结果");
        String input = replacement == null ? previous.inputJson() : json.writeValueAsString(replacement);
        var next = insert(previous.runId(), previous.candidateKind(), previous.ordinal(), previous.generation(),
                previous.attempt() + 1, input, Map.of("replaces", id));
        // An accepted candidate with proved stop is reusable without another Provider call.
        if (reuse) {
            if (models.reuse(next.id(), previous.outputJson(), previous.outputSha256(), previous.acceptedAt(), Instant.now().toString()) != 1) throw conflict();
            next = transition(require(next.id()), TemplateBatchState.VALIDATED, LifecycleEvent.COMPLETE, "SOURCE_ACCEPTED_RESULT_RECOVERED");
        }
        return next;
    }
    public SourceTemplateModelRow require(String id) { return models.find(id).orElseThrow(SourceModelStore::conflict); }
    public SourceTemplateRunRow requireActive(String id) {
        var run = admission.require(id);
        if (!Set.of("WRITING", "REVIEWING").contains(run.state())) throw conflict();
        return run;
    }
    private LifecycleTransitionService.Subject subject(SourceTemplateModelRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.SOURCE_MODEL_RUN, row.id(),
                LifecycleScopeType.PROJECT, admission.require(row.runId()).projectId());
    }
    private static ConflictException conflict() {
        return new ConflictException("SOURCE_MODEL_VERSION_CONFLICT", "源码角色运行已变化，请刷新后重试");
    }
}
