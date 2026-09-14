package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Stable functional grouping, with bounded candidate bodies and no traversal of commit history. */
@Component
public final class DocumentAssessmentPlan {
    private final DocumentRequirementMapper requirements;
    private final DocumentRequirementLedger ledger;
    private final DocumentTemplateMapper documents;
    private final ObjectMapper json;
    public DocumentAssessmentPlan(DocumentRequirementMapper requirements, DocumentRequirementLedger ledger,
            DocumentTemplateMapper documents, ObjectMapper json) {
        this.requirements = requirements; this.ledger = ledger; this.documents = documents; this.json = json;
    }
    public List<DocumentModelInput> batches(DocumentTemplateRunRow run) {
        var groups = new TreeMap<String, List<DocumentRequirements.Requirement>>(); int after = -1;
        while (true) {
            var page = requirements.page(run.id(), run.requirementRevision(), after, 100);
            for (var row : page) groups.computeIfAbsent(row.groupName(), ignored -> new ArrayList<>()).add(ledger.item(row));
            if (page.size() < 100) break; after = page.getLast().ordinal();
        }
        if (groups.isEmpty()) throw new BadRequestException("DOCUMENT_REQUIREMENTS_EMPTY", "文档没有可识别的需求，需补充明确目标后继续");
        String snapshot = json.readValue(run.snapshotJson(), DocumentCodeSnapshotStore.Snapshot.class).sha();
        var result = new ArrayList<DocumentModelInput>();
        for (var group : groups.values()) {
            var batch = new ArrayList<DocumentRequirements.Requirement>(); int size = 0;
            for (var item : group) {
                int length = json.writeValueAsString(item).length();
                if (!batch.isEmpty() && (batch.size() == 12 || size + length > 32000)) {
                    result.add(input(run, snapshot, batch)); batch.clear(); size = 0;
                }
                batch.add(item); size += length;
            }
            if (!batch.isEmpty()) result.add(input(run, snapshot, batch));
        }
        return List.copyOf(result);
    }
    private DocumentModelInput input(DocumentTemplateRunRow run, String snapshot, List<DocumentRequirements.Requirement> batch) {
        var refs = new LinkedHashMap<String, DocumentModelInput.SectionRef>();
        for (var requirement : batch) for (var source : requirement.sources()) {
            documents.file(run.id(), source.fileId()).orElseThrow();
            var section = documents.section(source.fileId(), source.section()).orElseThrow();
            refs.putIfAbsent(source.fileId() + ":" + source.section(), new DocumentModelInput.SectionRef(source.fileId(), source.section(), section.sha256()));
        }
        return new DocumentModelInput(List.copyOf(refs.values()), new DocumentRequirements.Candidate(List.copyOf(batch), List.of()),
                null, snapshot, null, null);
    }
}
