package io.opencode.loopper.service;

import io.opencode.loopper.persistence.DocumentRequirementMapper;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Bounded frozen context for software design, implementation and judges; never reads the live upload set. */
@Service
public final class DocumentDevelopmentReads {
    private final DocumentDevelopmentScope scopes;
    private final DocumentRequirementMapper requirements;
    private final DocumentTemplateReadService reads;
    public DocumentDevelopmentReads(DocumentDevelopmentScope scopes, DocumentRequirementMapper requirements, DocumentTemplateReadService reads) {
        this.scopes = scopes; this.requirements = requirements; this.reads = reads;
    }
    public Map<String, Object> guide(String grant) { return scopes.guide(grant); }
    public Object index(String grant, int after) {
        var scope = scopes.authorize(grant);
        if (after < -1) throw new BadRequestException("DOCUMENT_REQUIREMENT_CURSOR", "需求分页位置无效");
        if (requirements.basis(scope.runId(), scope.requirementRevision()).orElseThrow().sourceKind().equals("DOCUMENT_SOURCE")) return documents(grant);
        var rows = requirements.summaries(scope.runId(), scope.requirementRevision(), after, 101, false);
        var page = rows.stream().limit(100).toList(); scopes.authorize(grant);
        return Map.of("revision", scope.requirementRevision(), "manifestSha256", scope.manifestSha256(),
                "items", page, "hasMore", rows.size() > 100, "nextOffset", page.isEmpty() ? after : page.getLast().ordinal(),
                "files", scopes.files(scope));
    }
    public Object requirement(String grant, String key) {
        var scope = scopes.authorize(grant);
        var result = reads.requirement(scope.runId(), scope.requirementRevision(), key);
        scopes.authorize(grant); return result;
    }
    public Object source(String grant, String fileId, int section, String expectedSha) {
        var scope = scopes.authorize(grant);
        if (scopes.files(scope).stream().noneMatch(file -> file.id().equals(fileId) && file.sha256().equals(expectedSha)
                && section >= 0 && section < file.sections()))
            throw new BadRequestException("DOCUMENT_SOURCE_SCOPE_INVALID", "该文档分段不属于当前冻结需求许可");
        var result = reads.section(scope.runId(), fileId, section, expectedSha);
        scopes.authorize(grant);
        if (requirements.basis(scope.runId(), scope.requirementRevision()).orElseThrow().sourceKind().equals("DOCUMENT_SOURCE"))
            requirements.recordSourceRead(scope.externalSessionId(), scope.runId(), scope.requirementRevision(), fileId, section, result.sha256());
        return result;
    }
    public Object documents(String grant) {
        var scope = scopes.authorize(grant);
        var files = requirements.sourceFiles(scope.runId(), scope.requirementRevision());
        if (files.isEmpty()) throw new BadRequestException("DOCUMENT_SOURCE_MODE_REQUIRED", "此任务使用历史需求清单，请使用原读取入口");
        var items = files.stream().map(file -> Map.of("sourceRef", "DOC-" + (file.ordinal() + 1), "id", file.id(),
                "filename", file.filename(), "sha256", file.sha256(), "sections", file.sectionCount(),
                "limitations", file.limitationsJson(), "resourceUri", "loopper-document://development/" + grant + "/" + file.id() + "/index")).toList();
        scopes.authorize(grant);
        return Map.of("sourceRevision", scope.requirementRevision(), "manifestSha256", scope.manifestSha256(),
                "sourceKind", "DOCUMENT_SOURCE", "items", items);
    }
    public Object sections(String grant, String fileId, int offset) {
        var scope = scopes.authorize(grant);
        if (scopes.files(scope).stream().noneMatch(file -> file.id().equals(fileId)))
            throw new BadRequestException("DOCUMENT_SOURCE_SCOPE_INVALID", "该文档不属于当前冻结原文许可");
        var result = reads.sections(scope.runId(), fileId, offset);
        scopes.authorize(grant); return result;
    }
    public Object resource(String grant, String fileId, String section) {
        if (fileId.equals("index")) return documents(grant);
        if (section.startsWith("index")) return sections(grant, fileId, section.equals("index") ? 0 : Integer.parseInt(section.substring(5)));
        var scope = scopes.authorize(grant);
        var file = scopes.files(scope).stream().filter(value -> value.id().equals(fileId)).findFirst().orElseThrow();
        return source(grant, fileId, Integer.parseInt(section), file.sha256());
    }
}
