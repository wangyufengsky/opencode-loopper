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
    public Object index(String grant, int after) {
        var scope = scopes.authorize(grant);
        if (after < -1) throw new BadRequestException("DOCUMENT_REQUIREMENT_CURSOR", "需求分页位置无效");
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
        scopes.authorize(grant); return result;
    }
}
