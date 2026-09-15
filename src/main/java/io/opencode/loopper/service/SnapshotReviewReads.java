package io.opencode.loopper.service;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.GitEvidenceProcess;
import io.opencode.loopper.template.SnapshotReview;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Bounded code reads and independent per-batch receipts; indexes never imply semantic coverage. */
@Service
public class SnapshotReviewReads {
    private final SnapshotReviewAccess access;
    private final SnapshotReviewMapper snapshots;
    private final TemplateTaskMapper batches;
    private final SnapshotReviewStore store;
    private final TemplateGitSnapshotService repositories;
    private final GitEvidenceProcess git;
    private final DocumentCodeContentCache cache;
    private final ObjectMapper json;
    public SnapshotReviewReads(SnapshotReviewAccess access, SnapshotReviewMapper snapshots, TemplateTaskMapper batches,
            SnapshotReviewStore store, TemplateGitSnapshotService repositories, GitEvidenceProcess git,
            DocumentCodeContentCache cache, ObjectMapper json) {
        this.access = access; this.snapshots = snapshots; this.batches = batches; this.store = store;
        this.repositories = repositories; this.git = git; this.cache = cache; this.json = json;
    }
    public Map<String, Object> guide(String id, int offset, int limit) {
        var row = access.require(id); page(offset, limit);
        var snapshot = store.snapshot(row.taskId()); var input = input(row);
        var units = input.units();
        return Map.of("phase", input.phase(), "objective", input.objective(), "targetSha", snapshot.targetSha(),
                "baselineSha", snapshot.baselineSha() == null ? "" : snapshot.baselineSha(),
                "units", units.stream().skip(offset).limit(limit).map(u -> Map.of("id", u.id(), "path", u.path(),
                        "change", u.change(), "limitation", u.limitation() == null ? "" : u.limitation())).toList(),
                "nextOffset", offset + limit < units.size() ? offset + limit : -1,
                "groups", input.groups(), "relations", input.relations(), "dependencies", input.dependencies().stream().skip(offset).limit(limit).toList(),
                "nextDependencyOffset", offset + limit < input.dependencies().size() ? offset + limit : -1);
    }
    public Map<String, Object> groups(String id, int offset, int limit) {
        var row = access.require(id); page(offset, limit);
        var groups = store.plan(row.taskId()).groups();
        return Map.of("groups", groups.stream().skip(offset).limit(limit).toList(), "nextOffset", offset + limit < groups.size() ? offset + limit : -1);
    }
    public CursorPage<SnapshotReview.File> files(String id, String version, String after, int limit) {
        var row = access.require(id); page(0, limit); version(row.taskId(), version);
        if (after == null || after.length() > 1024) throw invalid("目录游标无效");
        var rows = snapshots.files(row.taskId(), version, after, limit + 1);
        var items = rows.stream().limit(limit).toList();
        return new CursorPage<>(items, rows.size() > limit ? items.getLast().path() : null);
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Object> read(String id, String version, String path, String blob, int start, int limit) {
        var row = access.require(id);
        if (start < 1 || limit < 1 || limit > 200) throw invalid("每次读取须为 1–200 行");
        var file = file(row.taskId(), version, path, blob);
        String[] lines = content(row.taskId(), file).split("\n", -1);
        if (start > lines.length) throw invalid("起始行超出冻结文件");
        int end = Math.min(lines.length, start + limit - 1);
        String body = String.join("\n", Arrays.copyOfRange(lines, start - 1, end));
        if (body.length() > 32000) throw invalid("代码片段超过单次读取上限，请缩小行范围");
        access.require(id);
        var ref = new SnapshotReview.Reference(version, path, blob, start, end, body);
        if (snapshots.readCount(id) >= 2048 && snapshots.receiptContent(id, ref).isEmpty())
            throw invalid("本批读取证据已达 2048 段，请提交局限或申请补充分批");
        snapshots.receipt(id, row.taskId(), ref, body);
        return Map.of("reference", ref, "totalLines", lines.length, "hasMore", end < lines.length);
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Object> search(String id, String version, String path, String blob, String query, int afterLine) {
        var row = access.require(id);
        if (query == null || query.isBlank() || query.length() > 200 || afterLine < 0) throw invalid("检索参数无效");
        var file = file(row.taskId(), version, path, blob);
        String[] lines = content(row.taskId(), file).split("\n", -1);
        List<Map<String, Object>> matches = new ArrayList<>(); int next = -1;
        for (int i = afterLine; i < lines.length; i++) {
            if (!lines[i].contains(query)) continue;
            if (matches.size() == 20) { next = i; break; }
            matches.add(Map.of("line", i + 1, "text", lines[i].substring(0, Math.min(lines[i].length(), 1000)), "truncated", lines[i].length() > 1000));
        }
        access.require(id);
        return Map.of("matches", matches, "nextAfterLine", next, "limitation", "只检索指定冻结文件；无命中不是缺失证明，引用前必须读取完整片段");
    }
    public Map<String, Object> results(String id, int offset, int limit) {
        var row = access.require(id); page(offset, limit);
        var values = snapshots.accepted(row.taskId(), offset, limit + 1);
        return Map.of("batches", values.stream().limit(limit).toList(), "nextOffset", values.size() > limit ? offset + limit : -1);
    }
    public Map<String, Object> result(String id, String batchId) {
        var caller = access.require(id); var input = input(caller);
        var row = batches.findBatch(batchId).orElseThrow(() -> invalid("依赖批次不存在"));
        if (!row.taskId().equals(caller.taskId()) || !SnapshotReview.batch(row.purpose()) || !row.state().equals("VALIDATED") || row.outputJson() == null) throw invalid("依赖结果尚未验证");
        return Map.of("batchId", batchId, "purpose", row.purpose(), "candidate", json.readTree(row.outputJson()),
                "sha256", TemplateGitEvidenceCollector.hash(row.outputJson()));
    }
    public void evidence(String batchId, List<SnapshotReview.Reference> refs, boolean targetRequired) {
        var row = snapshotsForBatch(batchId); String target = store.snapshot(row.taskId()).targetSha(); boolean targetFound = false;
        if (refs == null || refs.size() > 64) throw invalid("证据数量无效");
        for (var ref : refs) {
            if (ref == null || ref.startLine() < 1 || ref.endLine() < ref.startLine() || ref.quote() == null || ref.quote().isBlank())
                throw invalid("引用缺少版本、位置或原文");
            var content = snapshots.receiptContent(batchId, ref).orElseThrow(() -> invalid("引用必须来自本角色实际读取的同版本同位置代码"));
            if (!content.contains(ref.quote())) throw invalid("引用原文与冻结读取内容不匹配");
            if (target.equals(ref.version())) targetFound = true;
        }
        if (targetRequired && !targetFound) throw invalid("当前缺陷必须引用目标版本代码证据");
    }
    private TemplateTaskBatchRow snapshotsForBatch(String id) { return batches.findBatch(id).orElseThrow(() -> invalid("批次不存在")); }
    private SnapshotReview.Input input(TemplateTaskBatchRow row) { return json.readValue(row.inputJson(), TemplateBatchExecution.Input.class).snapshot(); }
    private SnapshotReview.File file(String task, String version, String path, String blob) {
        version(task, version);
        var file = snapshots.fileAt(task, version, path).orElseThrow(() -> invalid("文件不属于冻结版本"));
        if (!file.blob().equals(blob) || file.limitation() != null || DocumentCodeSnapshotService.protectedPath(path))
            throw invalid("文件身份不符或不允许读取");
        return file;
    }
    private void version(String task, String version) {
        var snapshot = store.snapshot(task);
        if (!snapshot.targetSha().equals(version) && !Objects.equals(snapshot.baselineSha(), version)) throw invalid("代码版本不属于本轮审查");
    }
    private String content(String task, SnapshotReview.File file) {
        return cache.read(task, file.version(), file.blob(), () -> {
            String body = git.read(repositories.workspace(task).resolve("repository.git"), "cat-file", "blob", file.blob());
            if (body.indexOf('\u0000') >= 0 || body.indexOf('\uFFFD') >= 0) throw invalid("二进制或非 UTF-8 文件不能作为文本证据");
            return body;
        });
    }
    private static void page(int offset, int limit) { if (offset < 0 || limit < 1 || limit > 100) throw invalid("分页范围无效"); }
    static BadRequestException invalid(String message) { return new BadRequestException("SNAPSHOT_CANDIDATE_INVALID", message); }
}
