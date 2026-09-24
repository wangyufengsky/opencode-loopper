package io.opencode.loopper.service;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.GitEvidenceProcess;
import io.opencode.loopper.template.DocumentModelInput;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Bounded frozen source reads. File text is never loaded by summary/overview endpoints. */
@Service
public class DocumentFrozenReadService {
    private final DocumentModelAccess access;
    private final DocumentTemplateCandidatePolicy inputs;
    private final DocumentTemplateMapper documents;
    private final DocumentCodeMapper code;
    private final DocumentCodeSnapshotService snapshots;
    private final GitEvidenceProcess git;
    private final ObjectMapper json;
    private final DocumentCodeContentCache contentCache;
    public DocumentFrozenReadService(DocumentModelAccess access, DocumentTemplateCandidatePolicy inputs,
            DocumentTemplateMapper documents, DocumentCodeMapper code, DocumentCodeSnapshotService snapshots,
            GitEvidenceProcess git, ObjectMapper json, DocumentCodeContentCache contentCache) {
        this.access = access; this.inputs = inputs; this.documents = documents; this.code = code;
        this.snapshots = snapshots; this.git = git; this.json = json; this.contentCache = contentCache;
    }
    public String authorizedSession(String id) { return access.require(id, false).externalSessionId(); }
    public DocumentTemplateMapper.Section section(String id, String fileId, int ordinal, String sha) {
        var model = access.require(id, false); var input = inputs.input(model);
        requireSource(model, input, fileId);
        documents.file(model.runId(), fileId).orElseThrow(() -> invalid("文档不属于当前模板"));
        var section = documents.section(fileId, ordinal).orElseThrow(() -> invalid("冻结分段不存在"));
        if (!section.sha256().equals(sha) || !DocumentModelStore.hash(section.content()).equals(sha))
            throw invalid("冻结分段内容校验失败");
        access.require(id, false);
        if (input.sourceRevision() > 0) documents.recordSourceRead(model.externalSessionId(), model.runId(), input.sourceRevision(), fileId, ordinal, sha);
        return section;
    }
    public List<DocumentIndex> documents(String id) {
        var model = access.require(id, false);
        var input = inputs.input(model);
        return (input.sourceRevision() > 0 ? documents.sourceFiles(model.runId(), input.sourceRevision()) : documents.files(model.runId())).stream().map(file -> new DocumentIndex(file.id(), file.filename(),
                file.format(), file.sha256(), file.sectionCount(), json.readValue(file.limitationsJson(),
                new tools.jackson.core.type.TypeReference<List<String>>() { }))).toList();
    }
    public CursorPage<DocumentTemplateMapper.SectionSummary> sections(String id, String fileId, int after, int limit) {
        var model = access.require(id, false);
        requireSource(model, inputs.input(model), fileId);
        documents.file(model.runId(), fileId).orElseThrow(() -> invalid("文档不属于当前模板"));
        if (after < -1 || limit < 1 || limit > 100) throw invalid("分段目录分页参数无效");
        var page = documents.sections(fileId, after + 1, limit + 1);
        var items = page.stream().limit(limit).toList();
        return new CursorPage<>(items, page.size() > limit ? String.valueOf(items.getLast().ordinal()) : null);
    }
    public CursorPage<DocumentCodeMapper.File> files(String id, String query, String after, int limit) {
        var model = access.require(id, true); ready(model);
        if (limit < 1 || limit > 100 || query == null || query.length() > 200 || after == null || after.length() > 1024)
            throw invalid("代码目录分页参数无效");
        var rows = code.files(model.runId(), after, query, limit + 1);
        var items = rows.stream().limit(limit).toList();
        return new CursorPage<>(items, rows.size() > limit ? items.getLast().path() : null);
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Window read(String id, String path, String blobSha, int start, int limit) {
        if (start < 1 || limit < 1 || limit > 200) throw invalid("代码行读取范围须为 1–200 行");
        var model = access.require(id, true); ready(model);
        var source = file(model, path, blobSha);
        String text = content(model, source);
        String[] lines = text.split("\n", -1);
        if (start > lines.length) throw invalid("起始行超出冻结文件范围");
        int end = Math.min(lines.length, start + limit - 1);
        String body = String.join("\n", Arrays.copyOfRange(lines, start - 1, end));
        if (body.length() > 32000) throw invalid("源码行内容超过读取上限，请缩小行范围");
        access.require(id, true);
        if (code.readCount(model.id()) >= 2048 && code.evidence(model.id(), path, start, end).isEmpty())
            throw invalid("本批代码读取证据超过 2048 段，请保留未完成范围后拆分评审");
        code.insertRead(new DocumentCodeMapper.Read(model.id(), path, source.blobSha(), start, end, body,
                DocumentModelStore.hash(body), Instant.now().toString()));
        return new Window(path, source.blobSha(), start, end, lines.length, body, end < lines.length);
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Search search(String id, String path, String blobSha, String query, int afterLine) {
        if (query == null || query.isBlank() || query.length() > 200 || afterLine < 0) throw invalid("源码检索参数无效");
        var model = access.require(id, true); ready(model);
        var source = file(model, path, blobSha);
        String[] lines = content(model, source).split("\n", -1);
        var matches = new ArrayList<Match>(); int next = 0;
        for (int index = afterLine; index < lines.length; index++) {
            if (!lines[index].toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) continue;
            if (matches.size() == 20) { next = matches.getLast().line(); break; }
            var value = lines[index]; matches.add(new Match(index + 1, value.substring(0, Math.min(1000, value.length())), value.length() > 1000));
        }
        access.require(id, true);
        return new Search(path, source.blobSha(), matches, next == 0 ? null : next, next == 0,
                "仅在指定冻结文件中检索；没有命中不能证明需求未实现。引用结论前须读取对应完整代码行。");
    }
    private void requireSource(DocumentTemplateModelRow model, DocumentModelInput input, String file) {
        if (input.sourceRevision() > 0 && documents.sourceFiles(model.runId(), input.sourceRevision()).stream()
                .noneMatch(value -> value.id().equals(file))) throw invalid("文档不属于本角色冻结原文版本");
    }
    public Object resource(String id, String fileId, String section) {
        if (fileId.equals("index")) return documents(id);
        if (section.startsWith("index")) return sections(id, fileId, section.equals("index") ? -1 : Integer.parseInt(section.substring(5)), 100);
        var model = access.require(id, false); requireSource(model, inputs.input(model), fileId);
        var value = documents.section(fileId, Integer.parseInt(section)).orElseThrow(() -> invalid("原文分段不存在"));
        return section(id, fileId, value.ordinal(), value.sha256());
    }
    private DocumentCodeMapper.File file(DocumentTemplateModelRow model, String path, String sha) {
        var file = code.file(model.runId(), path).orElseThrow(() -> invalid("源码不属于冻结快照"));
        if (!file.blobSha().equals(sha) || file.limitation() != null || DocumentCodeSnapshotService.protectedPath(path))
            throw invalid("该源码内容身份不符或存在读取限制");
        return file;
    }
    private String content(DocumentTemplateModelRow model, DocumentCodeMapper.File file) {
        String sha = inputs.input(model).snapshotSha();
        return contentCache.read(model.runId(), sha, file.blobSha(), () -> {
            String body = git.read(snapshots.repository(model.runId()), "cat-file", "blob", file.blobSha());
            if (body.indexOf('\u0000') >= 0 || body.indexOf('\uFFFD') >= 0) throw invalid("二进制或非 UTF-8 源文件不能作为文本证据");
            return body;
        });
    }
    private void ready(DocumentTemplateModelRow model) {
        DocumentModelInput input = inputs.input(model);
        var run = documents.find(model.runId()).orElseThrow();
        var snapshot = json.readValue(run.snapshotJson(), DocumentCodeSnapshotStore.Snapshot.class);
        if (!snapshot.ready() || !snapshot.sha().equals(input.snapshotSha())) throw invalid("冻结代码树尚未就绪或身份变化");
    }
    private static BadRequestException invalid(String message) { return new BadRequestException("DOCUMENT_FROZEN_READ_INVALID", message); }
    public record Window(String path, String blobSha, int startLine, int endLine, int totalLines, String content, boolean hasMore) { }
    public record DocumentIndex(String fileId, String filename, String format, String sha256, int sections, List<String> limitations) { }
    public record Match(int line, String text, boolean truncated) { }
    public record Search(String path, String blobSha, List<Match> matches, Integer nextAfterLine, boolean searchedToEnd, String limitation) { }
}
