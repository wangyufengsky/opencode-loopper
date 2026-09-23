package io.opencode.loopper.service;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Metadata is cheap; source and result bodies are loaded only after a scoped request. */
@Service
public final class SourceTemplateReadService {
    private final SourceTemplateAdmission admission;
    private final SourceTemplateMapper mapper;
    private final SourceSnapshotStorage storage;
    private final LoopperMapper domain;
    private final ObjectMapper json;
    private final SourceTemplateModelMapper models;
    private final SourceTemplateControl controls;
    public SourceTemplateReadService(SourceTemplateAdmission admission, SourceTemplateMapper mapper,
            SourceSnapshotStorage storage, LoopperMapper domain, ObjectMapper json, SourceTemplateModelMapper models, SourceTemplateControl controls) {
        this.admission = admission; this.mapper = mapper; this.storage = storage; this.domain = domain; this.json = json;
        this.models = models; this.controls = controls;
    }
    public Overview overview(String id) {
        var row = admission.require(id);
        var parameters = json.readValue(row.parametersJson(), SourceTemplateParameters.class);
        var snapshot = row.snapshotJson() == null ? null : json.readValue(row.snapshotJson(), SourceSnapshot.class);
        String taskState = row.taskId() == null ? null : domain.findTask(row.taskId()).map(TaskRow::state).orElse(null);
        return new Overview(row.id(), row.projectId(), row.templateId(), row.templateVersion(), row.title(), row.state(),
                row.version(), row.createdAt(), row.updatedAt(), row.archived() != 0, parameters.sourcePath(),
                parameters.testOutputPath(), parameters.documentPath(), parameters.requirements(),
                snapshot, row.designerId(), row.taskId(), taskState, row.waitingReasonCode(), row.waitingMessage(),
                mapper.coverageCounts(id), models.counts(id), controls.resumable(row), controls.archivable(row),
                domain.sourceTestProfile(id).map(p -> compact(json.readValue(p.profileJson(), SourceTestProfile.class))).orElse(null));
    }
    private SourceTestProfile compact(SourceTestProfile profile) {
        return new SourceTestProfile(profile.manifestSha256(), profile.modules().stream().map(m -> new SourceTestProfile.Module(
                m.root(), m.framework(), List.of(), m.testRoots(), m.fixtureRoots(), m.command())).toList());
    }
    public CursorPage<SourceTemplateModelMapper.Metadata> batches(String id, String cursor, int limit) {
        admission.require(id); var position = PageCursor.decode(cursor); int bounded = PageCursor.limit(limit);
        var rows = models.metadata(id, position == null ? "" : position.value(), position == null ? "" : position.id(), bounded + 1);
        boolean more = rows.size() > bounded; var page = more ? rows.subList(0, bounded) : rows;
        return new CursorPage<>(page, more ? new PageCursor(page.getLast().createdAt(), page.getLast().id()).encode() : null);
    }
    public CursorPage<SourceTemplateMapper.Coverage> coverage(String id, String cursor, int limit) {
        admission.require(id);
        int after;
        try { after = cursor == null || cursor.isBlank() ? -1 : Integer.parseInt(cursor); }
        catch (NumberFormatException invalid) { throw new BadRequestException("SOURCE_CURSOR_INVALID", "列表位置无效，请重新加载"); }
        int bounded = Math.max(1, Math.min(100, limit));
        var rows = mapper.coverage(id, after, bounded + 1);
        boolean more = rows.size() > bounded;
        var page = more ? rows.subList(0, bounded) : rows;
        return new CursorPage<>(page, more ? Integer.toString(page.getLast().ordinal()) : null);
    }
    public SourceText source(String id, String path, int startLine, int limit) {
        var row = admission.require(id);
        if (row.snapshotJson() == null || !json.readValue(row.snapshotJson(), SourceSnapshot.class).ready())
            throw new ConflictException("SOURCE_SNAPSHOT_NOT_READY", "源码尚未冻结完成，请稍后重试");
        var file = mapper.file(id, path).orElseThrow(() -> new NotFoundException("该文件不属于本次冻结源码"));
        if (file.sha256() == null) throw new BadRequestException("SOURCE_FILE_UNAVAILABLE", "该文件不可读取，请查看排除原因");
        int start = Math.max(1, startLine), count = Math.max(1, Math.min(200, limit));
        var lines = storage.read(id, file.sha256()).lines().toList();
        if (start > lines.size() + 1) throw new BadRequestException("SOURCE_LINE_INVALID", "源码行号超出范围");
        int end = Math.min(lines.size(), start - 1 + count);
        String body = String.join("\n", lines.subList(start - 1, end));
        if (body.length() > 48000) throw new BadRequestException("SOURCE_READ_LIMIT", "所选源码段过长，请减少读取行数");
        return new SourceText(path, file.sha256(), start, end, lines.size(), body);
    }
    public SourceTemplateMapper.Coverage coverageItem(String id, String path) {
        admission.require(id);
        return mapper.coverageItem(id, path).orElseThrow(() -> new NotFoundException("该文件不属于本次处理范围"));
    }
    public record Overview(String id, String projectId, String templateId, String templateVersion, String title,
            String state, long version, String createdAt, String updatedAt, boolean archived, String sourcePath,
            String testOutputPath, String documentPath, String requirements, SourceSnapshot snapshot,
            String designerId, String taskId, String taskState, String waitingReasonCode, String waitingMessage,
            List<SourceTemplateMapper.Count> coverage, List<SourceTemplateModelMapper.Count> progress,
            boolean canResume, boolean canArchive, SourceTestProfile testProfile) { }
    public record SourceText(String path, String sha256, int startLine, int endLine, int totalLines, String content) { }
}
