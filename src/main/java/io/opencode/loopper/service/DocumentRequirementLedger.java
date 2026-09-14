package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Requirement revisions become visible only after all independently reviewed batches are persisted. */
@Service
public class DocumentRequirementLedger {
    private final DocumentRequirementMapper requirements;
    private final DocumentTemplateModelMapper models;
    private final DocumentTemplateMapper runs;
    private final DocumentTemplateCandidatePolicy policy;
    private final ObjectMapper json;
    public DocumentRequirementLedger(DocumentRequirementMapper requirements, DocumentTemplateModelMapper models,
            DocumentTemplateMapper runs, DocumentTemplateCandidatePolicy policy, ObjectMapper json) {
        this.requirements = requirements; this.models = models; this.runs = runs; this.policy = policy; this.json = json;
    }
    @Transactional
    public void accept(String extractionId, String reviewId, int round) {
        var extraction = models.find(extractionId).orElseThrow(); var review = models.find(reviewId).orElseThrow();
        if (!extraction.runId().equals(review.runId()) || extraction.ordinal() != review.ordinal()
                || !"VALIDATED".equals(extraction.state()) || !"VALIDATED".equals(review.state())
                || Objects.equals(extraction.externalSessionId(), review.externalSessionId())) throw conflict();
        var input = policy.input(review);
        var candidate = json.readValue(extraction.outputJson(), DocumentRequirements.Candidate.class);
        if (!json.writeValueAsString(input.requirements()).equals(extraction.outputJson())) throw conflict();
        var verified = DocumentRequirementValidation.review(policy.sections(review, input), candidate,
                json.readValue(review.outputJson(), DocumentRequirements.Review.class));
        if (!verified.approved()) throw conflict();
        var existing = requirements.batch(extraction.runId(), extraction.ordinal(), round);
        if (existing.isPresent()) {
            if (!existing.get().extractionModelId().equals(extractionId) || !existing.get().reviewModelId().equals(reviewId)) throw conflict();
            return;
        }
        if (requirements.insertBatch(new DocumentRequirementMapper.Batch(extraction.runId(), extraction.ordinal(), round,
                extractionId, reviewId, extraction.outputJson(), DocumentModelStore.hash(extraction.outputJson()), Instant.now().toString())) != 1) throw conflict();
    }
    public List<DocumentRequirementMapper.Batch> batches(String runId, int round) {
        var result = new ArrayList<DocumentRequirementMapper.Batch>(); int after = -1;
        while (true) {
            var page = requirements.batches(runId, round, after); result.addAll(page);
            if (page.size() < 100) break;
            after = page.getLast().ordinal();
        }
        return List.copyOf(result);
    }
    /** One bounded write batch per call; a crash leaves an unbound revision that is safe to continue. */
    @Transactional
    public boolean publish(String runId, int round, int batchCount) {
        var run = runs.find(runId).orElseThrow();
        if (run.requirementRevision() >= round) return true;
        var batches = batches(runId, round);
        if (batches.size() != batchCount) throw conflict();
        String source = json.writeValueAsString(batches.stream().map(batch -> Map.of(
                "ordinal", batch.ordinal(), "extraction", batch.extractionModelId(), "review", batch.reviewModelId(),
                "sha256", batch.candidateSha256())).toList());
        var revision = requirements.revision(runId, round);
        if (revision.isEmpty()) requirements.insertRevision(new DocumentRequirementMapper.Revision(runId, round,
                DocumentModelStore.hash(source), source, Instant.now().toString()));
        else if (!revision.get().manifestSha256().equals(DocumentModelStore.hash(source))) throw conflict();
        for (var batch : batches) {
            if (!DocumentModelStore.hash(batch.candidateJson()).equals(batch.candidateSha256())) throw conflict();
            var candidate = json.readValue(batch.candidateJson(), DocumentRequirements.Candidate.class);
            int first = batch.ordinal() * 256;
            int existing = requirements.batchCount(runId, round, first, first + 256);
            if (existing == candidate.requirements().size()) continue;
            if (existing != 0) throw conflict();
            for (int index = 0; index < candidate.requirements().size(); index++) {
                var item = candidate.requirements().get(index); int ordinal = first + index;
                if (requirements.insert(new DocumentRequirementMapper.Requirement(runId, round, "RQ-" + (ordinal + 1), ordinal,
                        item.title(), item.group(), item.kind().name(), item.statement(), json.writeValueAsString(item.sources()),
                        json.writeValueAsString(item.acceptance()), json.writeValueAsString(item.issues()))) != 1) throw conflict();
            }
            return false;
        }
        if (requirements.bindRevision(runId, run.version(), round, run.requirementRevision(), Instant.now().toString()) != 1) throw conflict();
        return true;
    }
    public DocumentRequirements.Requirement item(DocumentRequirementMapper.Requirement row) {
        return new DocumentRequirements.Requirement(row.requirementKey(), row.title(), row.groupName(),
                DocumentRequirements.Kind.valueOf(row.kind()), row.statement(),
                json.readValue(row.sourcesJson(), new tools.jackson.core.type.TypeReference<>() { }),
                json.readValue(row.acceptanceJson(), new tools.jackson.core.type.TypeReference<>() { }),
                json.readValue(row.issuesJson(), new tools.jackson.core.type.TypeReference<>() { }));
    }
    private static ConflictException conflict() { return new ConflictException("DOCUMENT_REQUIREMENT_REVISION_CONFLICT", "需求清单缺少独立复核或冻结身份发生变化"); }
}
