package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Freezes the exact approved source mappings and execution proof before report generation can start. */
@Service
public final class DocumentDevelopmentEvidence {
    private final DocumentDevelopmentEvidenceMapper evidence;
    private final DocumentDevelopmentCompletion completion;
    private final DocumentTemplateAdmission admission;
    private final DocumentRequirementMapper requirements;
    private final LoopperMapper domain;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    public DocumentDevelopmentEvidence(DocumentDevelopmentEvidenceMapper evidence, DocumentDevelopmentCompletion completion,
            DocumentTemplateAdmission admission, DocumentRequirementMapper requirements, LoopperMapper domain,
            ObjectMapper json, PlatformTransactionManager manager) {
        this.evidence = evidence; this.completion = completion; this.admission = admission;
        this.requirements = requirements; this.domain = domain; this.json = json;
        this.transactions = new TransactionTemplate(manager);
    }
    public void freeze(DocumentTemplateRunRow run) {
        var proof = completion.require(run.taskId());
        var mappings = mappings(run, proof);
        var keys = new LinkedHashSet<String>(); int after = -1;
        if (run.directDocuments()) for (var file : requirements.sourceFiles(run.id(), run.basisRevision())) {
            String key = "DOC-" + (file.ordinal() + 1);
            if (mappings.getOrDefault(key, List.of()).stream().noneMatch(Mapping::appliesToCurrentRevision)) throw incomplete();
            keys.add(key);
        }
        while (!run.directDocuments()) {
            var page = requirements.page(run.id(), run.basisRevision(), after, 100);
            for (var item : page) {
                if (!json.readTree(item.issuesJson()).isEmpty() || mappings.getOrDefault(item.requirementKey(), List.of()).stream()
                        .noneMatch(Mapping::appliesToCurrentRevision)) throw incomplete();
                keys.add(item.requirementKey());
            }
            if (page.size() < 100) break; after = page.getLast().ordinal();
        }
        if (keys.isEmpty() || mappings.entrySet().stream().anyMatch(entry -> !keys.contains(entry.getKey())
                && !entry.getKey().equals(DocumentRequirementContext.FINAL_REGRESSION)
                && entry.getValue().stream().anyMatch(Mapping::appliesToCurrentRevision))) throw incomplete();
        String lastStage = proof.stages().getLast().id();
        if (mappings.getOrDefault(DocumentRequirementContext.FINAL_REGRESSION, List.of()).stream()
                .noneMatch(mapping -> mapping.stageId().equals(lastStage) && mapping.appliesToCurrentRevision())) throw incomplete();
        var revision = requirements.basis(run.id(), run.basisRevision()).orElseThrow(DocumentDevelopmentEvidence::incomplete);
        String encoded = json.writeValueAsString(new Snapshot(run.basisRevision(), revision.manifestSha256(), proof, mappings));
        transactions.executeWithoutResult(ignored -> {
            var current = admission.require(run.id()); var task = domain.findTask(run.taskId()).orElseThrow();
            if (!current.state().equals("EXECUTING") || current.version() != run.version()
                    || task.version() != proof.taskVersion() || !Objects.equals(current.taskId(), proof.taskId())
                    || domain.latestTaskExecutionCycle(task.id()).orElseThrow().version() != proof.cycleVersion()) throw incomplete();
            if (evidence.insert(new DocumentDevelopmentEvidenceMapper.Evidence(run.id(), run.basisRevision(), task.id(),
                    proof.cycleId(), encoded, DocumentModelStore.hash(encoded), Instant.now().toString())) != 1) throw incomplete();
            admission.transition(current, DocumentTemplateState.REPORTING, LifecycleEvent.RENDER_REQUIREMENT_REPORT, null, null);
        });
    }
    public Snapshot read(DocumentTemplateRunRow run) {
        var row = evidence.find(run.id()).orElseThrow(DocumentDevelopmentEvidence::incomplete);
        if (row.requirementRevision() != run.basisRevision() || !row.taskId().equals(run.taskId())
                || !DocumentModelStore.hash(row.contentJson()).equals(row.sha256())) throw incomplete();
        return json.readValue(row.contentJson(), Snapshot.class);
    }
    private Map<String, List<Mapping>> mappings(DocumentTemplateRunRow run, DocumentDevelopmentCompletion.Proof proof) {
        var result = new LinkedHashMap<String, List<Mapping>>();
        var packages = domain.listTaskPackageRuns(run.taskId());
        if (packages.isEmpty()) {
            var revision = domain.findCurrentDesignRequirementRevision(run.designerId()).orElseThrow(DocumentDevelopmentEvidence::incomplete);
            for (var pack : domain.listDesignWorkPackages(revision.id())) {
                var stages = proof.stages().stream().filter(stage -> pack.packageId().equals(stage.workPackageId())).toList();
                if (!stages.isEmpty()) append(result, run, pack, pack.approvedDesignRevision(), stages);
            }
        } else {
            for (var pack : packages) {
                var stages = proof.stages().stream().filter(stage -> pack.id().equals(stage.packageRunId())).toList();
                if (stages.isEmpty()) continue;
                var design = domain.findDesignWorkPackage(pack.designWorkPackageId()).orElseThrow(DocumentDevelopmentEvidence::incomplete);
                append(result, run, design, pack.acceptedDesignRevision(), stages);
            }
        }
        var mappedStages = result.values().stream().flatMap(Collection::stream).map(Mapping::stageId).collect(java.util.stream.Collectors.toSet());
        if (proof.stages().stream().anyMatch(stage -> !mappedStages.contains(stage.id()))) throw incomplete();
        return result;
    }
    private void append(Map<String, List<Mapping>> result, DocumentTemplateRunRow run, DesignWorkPackageRow pack, Integer revision,
                        List<DocumentDevelopmentCompletion.Stage> stages) {
        if (revision == null) throw incomplete();
        var source = domain.documentPackageDesign(pack.id(), run.designerId()).orElseThrow(DocumentDevelopmentEvidence::incomplete);
        if (!source.runId().equals(run.id())) throw incomplete();
        var accepted = evidence.accepted(pack.id(), revision).orElseThrow(DocumentDevelopmentEvidence::incomplete);
        var candidate = json.readValue(accepted.canonicalCandidateJson(), PackageDesignV2Document.class);
        if (candidate.stages().size() != stages.size()) throw incomplete();
        DocumentPackageAcceptance.coverage(candidate).forEach((key, scenarios) -> {
            boolean applicable = currentSource(run, source, key);
            for (var scenario : scenarios) {
                var stage = stages.get(scenario.stageIndex());
                result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Mapping(pack.packageId(), revision,
                        accepted.candidateRunId(), scenario.key(), scenario.title(), stage.id(), stage.attemptId(),
                        stage.tests().stream().map(DocumentDevelopmentCompletion.TestEvidence::id).toList(),
                        source.documentRevision(), source.manifestSha256(), applicable));
            }
        });
    }
    private boolean currentSource(DocumentTemplateRunRow run, DocumentDevelopmentMapper.Design source, String key) {
        if (key.equals(DocumentRequirementContext.FINAL_REGRESSION)) return source.documentRevision() == run.basisRevision();
        if (run.directDocuments()) {
            var original = requirements.sourceFiles(run.id(), source.documentRevision()).stream()
                    .filter(file -> key.equals("DOC-" + (file.ordinal() + 1))).findFirst().orElseThrow(DocumentDevelopmentEvidence::incomplete);
            return requirements.sourceFiles(run.id(), run.basisRevision()).stream().anyMatch(file -> file.id().equals(original.id())
                    && file.sha256().equals(original.sha256()) && file.representationSha256().equals(original.representationSha256()));
        }
        var original = requirements.item(run.id(), source.documentRevision(), key).orElseThrow(DocumentDevelopmentEvidence::incomplete);
        var current = requirements.item(run.id(), run.basisRevision(), key).orElse(null);
        return current != null && original.title().equals(current.title()) && original.kind().equals(current.kind())
                && original.statement().equals(current.statement()) && original.acceptanceJson().equals(current.acceptanceJson())
                && original.sourcesJson().equals(current.sourcesJson()) && original.issuesJson().equals(current.issuesJson());
    }
    private static ConflictException incomplete() {
        return new ConflictException("DOCUMENT_DEVELOPMENT_MAPPING_INCOMPLETE", "冻结需求、批准设计与真实验收证据尚未逐项对应，已保留当前执行结果供恢复");
    }
    public record Snapshot(int requirementRevision, String manifestSha256, DocumentDevelopmentCompletion.Proof execution,
                           Map<String, List<Mapping>> requirements) { }
    public record Mapping(String packageKey, int designRevision, String candidateRunId, String scenarioKey,
                          String scenario, String stageId, String attemptId, List<String> testResultIds,
                          int requirementRevision, String manifestSha256, boolean appliesToCurrentRevision) { }
}
