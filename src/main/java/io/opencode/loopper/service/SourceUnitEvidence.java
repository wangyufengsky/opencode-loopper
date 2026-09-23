package io.opencode.loopper.service;

import io.opencode.loopper.domain.SourceTemplateState;
import io.opencode.loopper.persistence.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Source coverage closes only against approved scenarios, executed tests and one completed dual-Judge batch. */
@Service
public final class SourceUnitEvidence {
    private final DocumentDevelopmentCompletion completion;
    private final DocumentDevelopmentEvidenceMapper designs;
    private final SourceUnitEvidenceMapper evidence;
    private final SourceTemplateMapper runs;
    private final SourceTemplateAdmission admission;
    private final LoopperMapper domain;
    private final SourceTestProfileService profiles;
    private final SourceUnitScopeGuard guard;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    public SourceUnitEvidence(DocumentDevelopmentCompletion completion, DocumentDevelopmentEvidenceMapper designs,
            SourceUnitEvidenceMapper evidence, SourceTemplateMapper runs, SourceTemplateAdmission admission,
            LoopperMapper domain, SourceTestProfileService profiles, SourceUnitScopeGuard guard,
            TransactionTemplate transactions, ObjectMapper json) {
        this.completion = completion; this.designs = designs; this.evidence = evidence; this.runs = runs;
        this.admission = admission; this.domain = domain; this.profiles = profiles; this.guard = guard; this.transactions = transactions; this.json = json;
    }
    public void freeze(SourceTemplateRunRow run) {
        var proof = completion.require(run.taskId()); var task = domain.findTask(run.taskId()).orElseThrow();
        guard.check(run, Path.of(task.worktreePath()));
        var mappings = mappings(run, proof);
        var profile = profiles.require(run.id());
        // Recheck the executed frozen contract, independently of compiler injection and candidate claims.
        var finalStage = domain.findStage(proof.stages().getLast().id()).orElseThrow(SourceUnitEvidence::incomplete);
        var verifiers = List.of(json.readValue(finalStage.verifiersJson(), io.opencode.loopper.domain.LoopSpec.VerifierSpec[].class));
        for (var module : profile.modules()) if (verifiers.stream().noneMatch(v -> v.type().equals("PROCESS")
                && "TEST".equals(v.processPurpose()) && v.command().equals(module.command())
                && v.testTargets().containsAll(module.testRoots()))) throw incomplete();
        var paths = profile.modules().stream().flatMap(m -> m.sourcePaths().stream()).toList();
        var sources = runs.files(run.id());
        if (sources.stream().anyMatch(f -> f.target() == 1 && SourceTreeCapture.unresolved(f.exclusion()))) throw incomplete();
        for (var path : paths) {
            var file = sources.stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
            if (mappings.getOrDefault(SourceRequirementContext.reference(file.ordinal()), List.of()).isEmpty()) throw incomplete();
        }
        if (mappings.getOrDefault(DocumentRequirementContext.FINAL_REGRESSION, List.of()).stream()
                .noneMatch(m -> m.stageId().equals(proof.stages().getLast().id()))) throw incomplete();
        String body = json.writeValueAsString(new Snapshot(profile.manifestSha256(), proof, mappings));
        transactions.executeWithoutResult(ignored -> {
            var current = admission.require(run.id()); var latest = domain.findTask(run.taskId()).orElseThrow();
            if (current.version() != run.version() || !current.state().equals("EXECUTING")
                    || latest.version() != proof.taskVersion() || domain.latestTaskExecutionCycle(task.id()).orElseThrow().version() != proof.cycleVersion()) throw incomplete();
            if (evidence.insert(new SourceUnitEvidenceMapper.Evidence(run.id(), task.id(), proof.cycleId(), body,
                    DocumentModelStore.hash(body), Instant.now().toString())) != 1) throw incomplete();
            for (var path : paths) {
                var file = sources.stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
                runs.coverageResult(run.id(), path, "TESTED", json.writeValueAsString(mappings.get(SourceRequirementContext.reference(file.ordinal()))), Instant.now().toString());
            }
            admission.transition(current, SourceTemplateState.REPORTING, null, null, null);
        });
    }
    public void complete(SourceTemplateRunRow run) {
        var stored = evidence.find(run.id()).orElseThrow(SourceUnitEvidence::incomplete);
        if (!stored.taskId().equals(run.taskId()) || !DocumentModelStore.hash(stored.contentJson()).equals(stored.sha256())) throw incomplete();
        admission.transition(run, SourceTemplateState.COMPLETED, null, null, null);
    }
    private Map<String, List<Mapping>> mappings(SourceTemplateRunRow run, DocumentDevelopmentCompletion.Proof proof) {
        var result = new LinkedHashMap<String, List<Mapping>>();
        var packages = domain.listTaskPackageRuns(run.taskId());
        if (packages.isEmpty()) {
            var revision = domain.findCurrentDesignRequirementRevision(run.designerId()).orElseThrow();
            for (var pack : domain.listDesignWorkPackages(revision.id())) {
                var stages = proof.stages().stream().filter(s -> pack.packageId().equals(s.workPackageId())).toList();
                if (!stages.isEmpty()) append(result, run, pack, pack.approvedDesignRevision(), stages);
            }
        } else for (var pack : packages) {
            var stages = proof.stages().stream().filter(s -> pack.id().equals(s.packageRunId())).toList();
            if (!stages.isEmpty()) append(result, run, domain.findDesignWorkPackage(pack.designWorkPackageId()).orElseThrow(), pack.acceptedDesignRevision(), stages);
        }
        if (!result.values().stream().flatMap(Collection::stream).map(Mapping::stageId).collect(java.util.stream.Collectors.toSet())
                .containsAll(proof.stages().stream().map(DocumentDevelopmentCompletion.Stage::id).toList())) throw incomplete();
        return result;
    }
    private void append(Map<String, List<Mapping>> result, SourceTemplateRunRow run, DesignWorkPackageRow pack,
            Integer revision, List<DocumentDevelopmentCompletion.Stage> stages) {
        if (revision == null) throw incomplete();
        var binding = domain.sourceDevelopmentPackage(pack.id(), run.designerId()).orElseThrow(SourceUnitEvidence::incomplete);
        if (!binding.runId().equals(run.id())) throw incomplete();
        var accepted = designs.accepted(pack.id(), revision).orElseThrow(SourceUnitEvidence::incomplete);
        var candidate = json.readValue(accepted.canonicalCandidateJson(), PackageDesignV2Document.class);
        if (candidate.stages().size() != stages.size()) throw incomplete();
        DocumentPackageAcceptance.coverage(candidate).forEach((ref, scenarios) -> {
            for (var scenario : scenarios) {
                var stage = stages.get(scenario.stageIndex());
                result.computeIfAbsent(ref, ignored -> new ArrayList<>()).add(new Mapping(pack.id(), revision, accepted.candidateRunId(),
                        scenario.key(), scenario.title(), stage.id(), stage.attemptId(),
                        stage.tests().stream().map(DocumentDevelopmentCompletion.TestEvidence::id).toList()));
            }
        });
    }
    private static ConflictException incomplete() { return new ConflictException("SOURCE_TEST_EVIDENCE_INCOMPLETE", "尚缺少完整源码场景映射、正式测试或同批双评审通过证据"); }
    record Snapshot(String manifestSha256, DocumentDevelopmentCompletion.Proof execution, Map<String, List<Mapping>> coverage) { }
    record Mapping(String packageId, int designRevision, String candidateId, String scenario, String title,
                   String stageId, String attemptId, List<String> verifications) { }
}
