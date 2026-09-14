package io.opencode.loopper.service;

import io.opencode.loopper.domain.DocumentTemplateState;
import io.opencode.loopper.persistence.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Overview never reads document bodies, model prompts, or reports. */
@Service
public final class DocumentTemplateReadService {
    private final DocumentTemplateMapper mapper;
    private final ObjectMapper json;
    private final DocumentProgressMapper progress;
    private final DocumentRequirementMapper requirements;
    private final DocumentRequirementLedger ledger;
    public DocumentTemplateReadService(DocumentTemplateMapper mapper, ObjectMapper json, DocumentProgressMapper progress,
            DocumentRequirementMapper requirements, DocumentRequirementLedger ledger) {
        this.mapper = mapper; this.json = json; this.progress = progress; this.requirements = requirements; this.ledger = ledger;
    }
    public Overview overview(String id) {
        var run = require(id);
        var state = DocumentTemplateState.valueOf(run.state());
        var files = mapper.files(id).stream().map(file -> new FileView(file.id(), file.filename(), file.format(),
                file.sizeBytes(), file.sha256(), file.representationSha256(), file.parserVersion(), file.sectionCount(),
                strings(file.limitationsJson()))).toList();
        return new Overview(run.id(), run.projectId(), run.templateId(), run.templateVersion(), run.title(),
                run.state(), run.waitingReasonCode(), run.waitingMessage(), run.designerId(), run.taskId(),
                run.requirementRevision(), run.version(), run.createdAt(), run.updatedAt(),
                !state.terminal() && state != DocumentTemplateState.STOPPING, state == DocumentTemplateState.WAITING_INPUT,
                run.archived() == 1, files, progress.progress(id), mapper.uploadReady(id) && !mapper.supplementalUploadPending(id),
                run.snapshotJson() == null ? null : json.readValue(run.snapshotJson(), DocumentCodeSnapshotStore.Snapshot.class).sha(),
                progress.taskState(id));
    }
    public Overview request(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,100}"))
            throw new BadRequestException("DOCUMENT_REQUEST_KEY_INVALID", "发起标识无效，请重新选择文件");
        return overview(mapper.findRequest(key).orElseThrow(() -> new NotFoundException("本次上传尚未创建任务，请使用原文件重试")).id());
    }
    public RequirementPage requirements(String id, int revision, int after, boolean issuesOnly) {
        var run = require(id);
        if (revision != run.requirementRevision() || revision < 0 || after < -1) throw revisionChanged();
        var rows = requirements.summaries(id, revision, after, 101, issuesOnly);
        var page = rows.stream().limit(100).toList();
        return new RequirementPage(page, rows.size() > 100 ? page.getLast().ordinal() : null, revision);
    }
    public RequirementDetail requirement(String id, int revision, String key) {
        var run = require(id);
        if (revision < 1 || revision > run.requirementRevision()) throw revisionChanged();
        var row = requirements.item(id, revision, key).orElseThrow(() -> new NotFoundException("该需求不属于当前文档任务或版本"));
        return new RequirementDetail(ledger.item(row), requirements.assessment(id, revision, key)
                .map(body -> json.readValue(body, io.opencode.loopper.template.RequirementCodeAssessment.Item.class)).orElse(null));
    }
    private static ConflictException revisionChanged() { return new ConflictException("DOCUMENT_REQUIREMENT_REVISION_CHANGED", "需求版本发生变化，请重新加载清单"); }
    public SectionPage sections(String runId, String fileId, int offset) {
        requireFile(runId, fileId);
        if (offset < 0) throw new BadRequestException("DOCUMENT_SECTION_CURSOR", "文档分段位置无效");
        var rows = mapper.sections(fileId, offset, 101);
        var page = rows.stream().limit(100).toList();
        return new SectionPage(page, rows.size() > 100 ? page.getLast().ordinal() + 1 : null);
    }
    public DocumentTemplateMapper.Section section(String runId, String fileId, int ordinal, String expectedSha) {
        var file = requireFile(runId, fileId);
        if (expectedSha == null || !file.sha256().equals(expectedSha))
            throw new ConflictException("DOCUMENT_TEMPLATE_CONTENT_CHANGED", "文档版本不一致，请重新读取冻结清单");
        var section = mapper.section(fileId, ordinal).orElseThrow(() -> new NotFoundException("文档分段不存在"));
        if (!DocumentTemplateStorage.hash(section.content().getBytes(StandardCharsets.UTF_8)).equals(section.sha256()))
            throw new ConflictException("DOCUMENT_TEMPLATE_CONTENT_CHANGED", "冻结分段校验失败");
        return section;
    }
    private DocumentTemplateFileRow requireFile(String runId, String fileId) {
        require(runId);
        return mapper.file(runId, fileId).orElseThrow(() -> new NotFoundException("该文档不属于当前需求任务"));
    }
    private DocumentTemplateRunRow require(String id) {
        return mapper.find(id).orElseThrow(() -> new NotFoundException("需求模板任务不存在"));
    }
    private List<String> strings(String value) { return List.of(json.readValue(value, String[].class)); }
    public record FileView(String id, String filename, String format, long sizeBytes, String sha256,
                           String representationSha256, String parserVersion, int sectionCount, List<String> limitations) { }
    public record SectionPage(List<DocumentTemplateMapper.SectionSummary> items, Integer nextOffset) { }
    public record Overview(String id, String projectId, String templateId, String templateVersion, String title,
            String state, String waitingReasonCode, String waitingMessage, String designerId, String taskId,
            int requirementRevision, long version, String createdAt, String updatedAt, boolean canCancel,
            boolean canResume, boolean archived, List<FileView> files, DocumentProgressMapper.Progress progress,
            boolean uploadReady, String snapshotSha, String taskState) { }
    public record RequirementPage(List<DocumentRequirementMapper.Summary> items, Integer nextOffset, int revision) { }
    public record RequirementDetail(io.opencode.loopper.template.DocumentRequirements.Requirement requirement,
                                   io.opencode.loopper.template.RequirementCodeAssessment.Item assessment) { }
}
