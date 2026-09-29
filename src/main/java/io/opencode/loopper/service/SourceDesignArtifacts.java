package io.opencode.loopper.service;

import io.opencode.loopper.domain.SourceTemplateState;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Only fully reviewed source coverage can become a server-owned final document package. */
@Service
public final class SourceDesignArtifacts {
    private final SourceTemplateAdmission admission;
    private final SourceTemplateMapper runs;
    private final SourceTemplateModelMapper models;
    private final SourceArtifactMapper artifacts;
    private final SourceDesignPlan plans;
    private final SourceArtifactFiles files;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    public SourceDesignArtifacts(SourceTemplateAdmission admission, SourceTemplateMapper runs,
            SourceTemplateModelMapper models, SourceArtifactMapper artifacts, SourceDesignPlan plans,
            SourceArtifactFiles files, TransactionTemplate transactions, ObjectMapper json) {
        this.admission = admission; this.runs = runs; this.models = models; this.artifacts = artifacts;
        this.plans = plans; this.files = files; this.transactions = transactions; this.json = json;
    }
    public void publish(SourceTemplateRunRow run) {
        var plan = plans.require(run.id());
        var source = runs.files(run.id());
        if (source.stream().anyMatch(f -> f.target() == 1 && SourceTreeCapture.unresolved(f.exclusion())))
            throw new BadRequestException("SOURCE_COVERAGE_INCOMPLETE", "部分目标源码无法读取或解析，文档包尚未完成；请查看逐文件原因");
        int generation = models.progress(run.id()).orElseThrow().generation();
        var drafts = models.current(run.id(), "SOURCE_DETAILED_DESIGN_V1", generation);
        var reviews = models.current(run.id(), "SOURCE_DESIGN_REVIEW_V1", generation);
        if (drafts.size() != plan.batches().size() || reviews.size() != drafts.size()) throw SourceTemplateAdmission.conflict();
        var documents = new ArrayList<SourceDesignDocuments.Draft>();
        for (var draft : drafts) {
            var review = reviews.stream().filter(r -> r.ordinal() == draft.ordinal()).findFirst().orElseThrow();
            var reviewInput = json.readValue(review.inputJson(), SourceDesign.Input.class);
            if (!draft.state().equals("VALIDATED") || !review.state().equals("VALIDATED")
                    || !reviewInput.draftModelId().equals(draft.id()) || !reviewInput.draftSha256().equals(draft.outputSha256())
                    || !json.readValue(review.outputJson(), SourceDesign.Review.class).verdict().equals("PASS"))
                throw new BadRequestException("SOURCE_REVIEW_INCOMPLETE", "文档尚未全部通过独立复核");
            documents.add(new SourceDesignDocuments.Draft(draft.ordinal(),json.readValue(draft.outputJson(),SourceDesign.Candidate.class),"PASS"));
        }
        var rendered = SourceDesignDocuments.render(json.readValue(run.snapshotJson(),SourceSnapshot.class).manifestSha256(),
                source.stream().map(f->new SourceManifest.File(f.path(),f.target()==1,f.sizeBytes(),f.sha256(),f.exclusion())).toList(),documents);
        var output=rendered.files();var coverage=rendered.coverage();
        transactions.executeWithoutResult(ignored -> {
            var current = admission.require(run.id());
            if (!current.state().equals("REPORTING") || current.version() != run.version()) throw SourceTemplateAdmission.conflict();
            var stored = artifacts.all(run.id());
            String now = Instant.now().toString();
            output.forEach((name, content) -> {
                var previous = stored.stream().filter(a -> a.name().equals(name)).findFirst();
                if (previous.isPresent()) {
                    if (!previous.get().content().equals(content)) throw SourceTemplateAdmission.conflict();
                } else artifacts.insert(new SourceArtifactMapper.Artifact(UUID.randomUUID().toString(), run.id(), name,
                        "DETAILED_DESIGN", content, DocumentModelStore.hash(content), now));
            });
            coverage.forEach((path, sections) -> runs.coverageResult(run.id(), path, "REVIEWED",
                    json.writeValueAsString(Map.of("documents", sections)), now));
        });
        files.materialize(run.id());
        admission.transition(admission.require(run.id()), SourceTemplateState.COMPLETED, null, null, null);
    }
}
