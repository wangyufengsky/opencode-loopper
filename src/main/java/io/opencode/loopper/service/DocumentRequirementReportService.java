package io.opencode.loopper.service;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Immutable report bytes and paged metadata; generation and body access are explicit operations. */
@Service
public final class DocumentRequirementReportService {
    private final DocumentTemplateMapper runs;
    private final DocumentRequirementMapper requirements;
    private final DocumentAssessmentMapper assessments;
    private final DocumentArtifactMapper artifacts;
    private final DocumentRequirementLedger ledger;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    public DocumentRequirementReportService(DocumentTemplateMapper runs, DocumentRequirementMapper requirements,
            DocumentAssessmentMapper assessments, DocumentArtifactMapper artifacts, DocumentRequirementLedger ledger,
            ObjectMapper json, PlatformTransactionManager manager) {
        this.runs = runs; this.requirements = requirements; this.assessments = assessments; this.artifacts = artifacts;
        this.ledger = ledger; this.json = json; this.transactions = new TransactionTemplate(manager);
    }
    public void review(DocumentTemplateRunRow run) {
        if (!run.state().equals("REPORTING") || !run.templateId().equals("REQUIREMENT_CODE_REVIEW")) throw changed();
        var items = new LinkedHashMap<String, RequirementCodeAssessment.Item>();
        var findings = new LinkedHashMap<String, RequirementCodeAssessment.Finding>(); var limitations = new ArrayList<String>();
        int after = -1;
        while (true) {
            var page = assessments.batches(run.id(), run.requirementRevision(), after);
            for (var batch : page) {
                if (!DocumentModelStore.hash(batch.candidateJson()).equals(batch.candidateSha256())) throw changed();
                var candidate = json.readValue(batch.candidateJson(), RequirementCodeAssessment.Candidate.class);
                for (var item : candidate.items()) if (items.putIfAbsent(item.requirementKey(), item) != null) throw changed();
                for (var finding : candidate.findings()) merge(findings, finding);
                limitations.addAll(candidate.limitations());
            }
            if (page.size() < 100) break; after = page.getLast().ordinal();
        }
        var rows = new ArrayList<RequirementReportCompiler.Row>(); after = -1;
        while (true) {
            var page = requirements.page(run.id(), run.requirementRevision(), after, 100);
            for (var requirement : page) {
                var assessment = items.remove(requirement.requirementKey());
                if (assessment == null) throw changed(); rows.add(new RequirementReportCompiler.Row(ledger.item(requirement), assessment));
            }
            if (page.size() < 100) break; after = page.getLast().ordinal();
        }
        if (!items.isEmpty()) throw changed();
        for (var file : runs.files(run.id())) {
            for (String limitation : json.readValue(file.limitationsJson(), String[].class)) limitations.add(file.filename() + "：" + limitation);
        }
        var snapshot = json.readValue(run.snapshotJson(), DocumentCodeSnapshotStore.Snapshot.class);
        var result = RequirementReportCompiler.review(run.title(), snapshot.sha(), rows, List.copyOf(findings.values()), limitations);
        for (var file : result.files()) save(run, file.name(), "REQUIREMENT_REPORT", file.content());
        var matrix = new LinkedHashMap<String, Object>(Map.of(
                "templateVersion", run.templateVersion(), "requirementRevision", run.requirementRevision(), "snapshot", snapshot,
                "reviewCompleted", true, "allRequirementsSatisfied", result.allRequirementsSatisfied(), "conclusions", result.conclusions(),
                "testExecution", "NOT_RUN_STATIC_REVIEW", "requirements", rows, "findings", findings.values(), "limitations", limitations));
        matrix.put("sourceRevision", run.sourceRevision());
        save(run, "matrix.json", "REQUIREMENT_MATRIX", json.writeValueAsString(matrix));
    }
    private void merge(Map<String, RequirementCodeAssessment.Finding> findings, RequirementCodeAssessment.Finding finding) {
        String identity = DocumentModelStore.hash(json.writeValueAsString(List.of(finding.kind(), finding.title(), finding.trigger(),
                finding.impact(), finding.evidence().stream().map(ref -> ref.path() + ":" + ref.blobSha() + ":" + ref.startLine()).sorted().toList())));
        var previous = findings.get(identity);
        if (previous == null) { findings.put(identity, finding); return; }
        var refs = new TreeSet<>(previous.requirementKeys()); refs.addAll(finding.requirementKeys());
        var severity = previous.severity().ordinal() < finding.severity().ordinal() ? previous.severity() : finding.severity();
        findings.put(identity, new RequirementCodeAssessment.Finding(previous.key(), previous.kind(), severity, previous.title(),
                previous.trigger(), previous.impact(), previous.recommendation(), List.copyOf(refs), previous.evidence(), previous.rootCauseKey()));
    }
    public void save(DocumentTemplateRunRow run, String name, String kind, String content) {
        String sha = DocumentModelStore.hash(content);
        transactions.executeWithoutResult(ignored -> {
            var current = runs.find(run.id()).orElseThrow(DocumentRequirementReportService::changed);
            if (!current.state().equals("REPORTING") || current.requirementRevision() != run.requirementRevision() || current.sourceRevision() != run.sourceRevision()) throw changed();
            var previous = artifacts.named(run.id(), run.requirementRevision(), name);
            if (previous.isPresent()) { if (!previous.get().sha256().equals(sha)) throw changed(); return; }
            String id = UUID.nameUUIDFromBytes((run.id() + ":" + run.requirementRevision() + ":" + name).getBytes(StandardCharsets.UTF_8)).toString();
            if (artifacts.insert(new DocumentArtifactMapper.Artifact(id, run.id(), run.requirementRevision(), name, kind, content, sha,
                    Instant.now().toString())) != 1) throw changed();
        });
    }
    public CursorPage<DocumentArtifactMapper.Summary> list(String runId, String after, int limit) {
        var run = require(runId);
        if (limit < 1 || limit > 100 || after == null || after.length() > 1024) throw new BadRequestException("REPORT_PAGE_INVALID", "报告分页参数无效");
        var page = artifacts.list(runId, run.requirementRevision(), after, limit + 1); var items = page.stream().limit(limit).toList();
        return new CursorPage<>(items, page.size() > limit ? items.getLast().name() : null);
    }
    public DocumentArtifactMapper.Artifact read(String runId, String id) {
        require(runId);
        var value = artifacts.find(runId, id).orElseThrow(() -> new NotFoundException("报告不属于当前需求任务"));
        if (!DocumentModelStore.hash(value.content()).equals(value.sha256())) throw changed(); return value;
    }
    public DocumentArtifactMapper.Artifact named(String runId, String name) {
        var run = require(runId);
        if (name == null || name.length() > 1024) throw new BadRequestException("DOCUMENT_REPORT_NAME_INVALID", "报告名称无效");
        return read(runId, artifacts.named(runId, run.requirementRevision(), name)
                .orElseThrow(() -> new NotFoundException("报告不属于当前需求任务版本")).id());
    }
    public TemplateReportDownloadService.Download download(String runId) {
        var run = require(runId);
        if (!run.state().equals("COMPLETED")) throw new ConflictException("DOCUMENT_REPORT_INCOMPLETE", "报告尚未完整生成");
        var entries = new ArrayList<ReportBundleArchive.Entry>(); String after = ""; long bytes = 0;
        while (true) {
            var page = artifacts.list(runId, run.requirementRevision(), after, 100);
            for (var summary : page) {
                bytes += summary.bytes();
                if (bytes > ReportBundleArchive.MAX_BYTES || entries.size() >= ReportBundleArchive.MAX_FILES)
                    throw new ConflictException("TEMPLATE_REPORT_DOWNLOAD_TOO_LARGE", "报告超过整包上限，请下载分项报告");
                entries.add(new ReportBundleArchive.Entry(summary.name(), read(runId, summary.id()).content()));
            }
            if (page.size() < 100) break; after = page.getLast().name();
        }
        if (entries.stream().noneMatch(entry -> entry.name().equals("summary.md"))) throw changed();
        String directory = "需求报告-" + run.id().substring(0, 8) + "-v" + run.requirementRevision();
        return new TemplateReportDownloadService.Download(directory + ".zip", ReportBundleArchive.zip(directory, entries));
    }
    private DocumentTemplateRunRow require(String id) { return runs.find(id).orElseThrow(() -> new NotFoundException("需求模板任务不存在")); }
    private static ConflictException changed() { return new ConflictException("DOCUMENT_REPORT_EVIDENCE_INVALID", "报告所需的完整冻结证据缺失或发生变化"); }
}
