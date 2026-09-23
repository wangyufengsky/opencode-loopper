package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** The complete inventory is persisted before dispatch, so an empty window never means an empty scope. */
@Service
public class SourceDesignPlan {
    private final SourceTemplateMapper runs;
    private final SourceTemplateModelMapper models;
    private final SourceModelStore store;
    private final ObjectMapper json;
    public SourceDesignPlan(SourceTemplateMapper runs, SourceTemplateModelMapper models, SourceModelStore store, ObjectMapper json) {
        this.runs = runs; this.models = models; this.store = store; this.json = json;
    }
    @Transactional
    public SourceDesign.Plan prepare(SourceTemplateRunRow run) {
        var previous = models.progress(run.id());
        if (previous.isPresent()) return require(run.id());
        var files = runs.files(run.id()).stream().filter(f -> f.target() == 1 && f.exclusion() == null).toList();
        var batches = new ArrayList<SourceDesign.Batch>();
        var batch = new ArrayList<String>();
        String directory = null; long bytes = 0;
        for (var file : files) {
            String parent = Objects.toString(Path.of(file.path()).getParent(), ".");
            if (!batch.isEmpty() && (batch.size() >= 12 || bytes + file.sizeBytes() > 160000 || !parent.equals(directory))) {
                batches.add(new SourceDesign.Batch(batches.size(), directory, List.copyOf(batch))); batch.clear(); bytes = 0;
            }
            directory = parent; batch.add(file.path()); bytes += file.sizeBytes();
        }
        if (!batch.isEmpty()) batches.add(new SourceDesign.Batch(batches.size(), directory, List.copyOf(batch)));
        if (batches.isEmpty()) throw new BadRequestException("SOURCE_NO_APPLICABLE_FILES", "没有适用源码，不能标记为编写成功");
        String body = json.writeValueAsString(new SourceDesign.Plan(List.copyOf(batches)));
        if (models.insertProgress(new SourceTemplateModelMapper.Progress(run.id(), 1, body, DocumentModelStore.hash(body),
                Instant.now().toString())) != 1) throw SourceTemplateAdmission.conflict();
        var snapshot = json.readValue(run.snapshotJson(), SourceSnapshot.class);
        var parameters = json.readValue(run.parametersJson(), SourceTemplateParameters.class);
        for (var item : batches) store.create(run.id(), MachineCandidateKind.SOURCE_DETAILED_DESIGN_V1, item.ordinal(), 1,
                new SourceDesign.Input(snapshot.manifestSha256(), item.paths(), parameters.requirements(), null, null, null));
        return require(run.id());
    }
    public SourceDesign.Plan require(String id) {
        var progress = models.progress(id).orElseThrow(SourceTemplateAdmission::conflict);
        if (!DocumentModelStore.hash(progress.planJson()).equals(progress.planSha256())) throw SourceTemplateAdmission.conflict();
        return json.readValue(progress.planJson(), SourceDesign.Plan.class);
    }
}
