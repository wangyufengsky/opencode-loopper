package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.persistence.KnowledgeV2Mapper;
import io.opencode.loopper.service.PageCursor;
import io.opencode.loopper.service.assist.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Immutable-commit content search and patch search share the guarded Git transport and path policy. */
final class KnowledgeGitContent {
    private final KnowledgeGit git;
    private final KnowledgeV2Mapper mapper;
    private final ObjectMapper json;
    KnowledgeGitContent(KnowledgeGit git, KnowledgeV2Mapper mapper, ObjectMapper json) { this.git = git; this.mapper = mapper; this.json = json; }
    Map<String,Object> call(String owner, KnowledgeSources.Bound source, KnowledgeGit.Repository repository, String tool, Map<String,Object> args) {
        String commit = git.revision(repository, text(args, "commit"));
        String path = git.path(repository, text(args, "path"), true);
        boolean patches = tool.equals("search_knowledge_git_patches");
        String base = tool.equals("search_knowledge_git_content") ? "" : git.revision(repository, text(args, "baseCommit"));
        if (tool.equals("compare_knowledge_git_versions")) {
            var result = KnowledgeGit.segment(diff(repository, base, commit, path), integer(args, "startLine", 1), integer(args, "endLine", 0));
            result.put("baseCommit", base); result.put("commit", commit); result.put("path", text(args, "path"));
            result.put("location", "两个固定版本的差异文本"); return result;
        }
        var query = new KnowledgeSearchQuery(text(args, "query"), "EXACT", List.of());
        String identity = AssistFiles.sha(json.writeValueAsBytes(List.of(source, tool, commit, base, path, query.query())));
        String cursor = text(args, "cursor"), baseId, pageId; int position = 0, offset = 0;
        Map<String,Object> initial;
        if (cursor.isBlank()) {
            initial = candidates(repository, commit, base, path, patches); baseId = UUID.randomUUID().toString(); save(baseId, owner, identity, initial);
            pageId = UUID.nameUUIDFromBytes((baseId + ":0:0").getBytes(StandardCharsets.UTF_8)).toString();
        } else {
            var decoded = PageCursor.decode(cursor); baseId = decoded.id();
            var saved = mapper.snapshot(baseId, owner, identity); if (saved == null) throw KnowledgeSources.bad("Git 内容游标不属于当前拥有者或查询");
            initial = json.readValue(saved.bodyJson(), new TypeReference<>() { });
            String[] parts = decoded.value().split(":");
            try { if (parts.length != 2) throw new IllegalArgumentException(); position = Integer.parseInt(parts[0]); offset = Integer.parseInt(parts[1]); }
            catch (IllegalArgumentException failure) { throw KnowledgeSources.bad("Git 内容游标无效"); }
            pageId = UUID.nameUUIDFromBytes((baseId + ":" + position + ":" + offset).getBytes(StandardCharsets.UTF_8)).toString();
        }
        var previous = mapper.snapshot(pageId, owner, identity);
        if (previous != null) return json.readValue(previous.bodyJson(), new TypeReference<>() { });
        @SuppressWarnings("unchecked") var candidates = (List<String>)initial.get("candidates");
        if (position < 0 || position > candidates.size() || offset < 0 || offset > 4_194_304) throw KnowledgeSources.bad("Git 内容游标位置无效");
        var matches = new ArrayList<Map<String,Object>>(); var limitations = new ArrayList<String>();
        if (Boolean.TRUE.equals(initial.get("limited"))) limitations.add("候选超过 1000 项，本次只扫描前 1000 项，请缩小路径或版本范围");
        int scanned = 0; long deadline = System.nanoTime() + 6_000_000_000L;
        while (position < candidates.size() && matches.size() < 30 && scanned < 20 && System.nanoTime() < deadline) {
            String candidate = candidates.get(position); scanned++;
            try {
                String content = patches ? patch(repository, candidate, path) : git.read(repository.root(), "show", "--no-ext-diff", "--no-textconv", commit + ":" + candidate);
                if (content.indexOf('\0') >= 0) throw KnowledgeSources.bad("二进制内容未检索");
                var hits = query.occurrences(content, offset, 31 - matches.size()); boolean remaining = false;
                for (var hit : hits) {
                    if (matches.size() == 30) { offset = hit.index(); remaining = true; break; }
                    var row = KnowledgeOccurrences.hit(content, hit, 0); row.put("commit", patches ? candidate : commit); row.put("sourceId", source.id()); row.put("sourceName", source.name());
                    row.put("path", patches ? text(args, "path") : relative(repository, candidate)); row.put("sha256", AssistFiles.sha(content.getBytes(StandardCharsets.UTF_8)));
                    row.put("lineBasis", patches ? "提交差异文本行号（含头部及上下文）" : "版本内原文件行号");
                    row.put("read", Map.of("tool", patches ? "read_knowledge_git_commit" : "read_knowledge_git_file", "arguments", patches
                            ? Map.of("sourceId", source.id(), "commit", candidate, "startLine", Math.max(1, ((Number)row.get("startLine")).intValue() - 2))
                            : Map.of("sourceId", source.id(), "commit", commit, "path", relative(repository, candidate), "startLine", Math.max(1, ((Number)row.get("startLine")).intValue() - 2))));
                    matches.add(row);
                }
                if (remaining) break;
            } catch (AssistFailure failure) { limitations.add((patches ? candidate.substring(0, 12) : relative(repository, candidate)) + "：" + failure.getMessage()); }
            position++; offset = 0;
        }
        var result = new LinkedHashMap<String,Object>(); result.put("matches", matches); result.put("commit", commit); result.put("baseCommit", base);
        result.put("nextCursor", position < candidates.size() ? new PageCursor(position + ":" + offset, baseId).encode() : null);
        result.put("incomplete", position < candidates.size() || !limitations.isEmpty()); result.put("limitations", limitations);
        result.put("examined", scanned); result.put("collectedAt", Instant.now().toString());
        result.put("detail", patches ? "检索 baseCommit..commit 可达差集的提交差异文本，包含头部与上下文；不是提交标题搜索，也不证明变更引入原因。" : "固定提交的普通文本文件内容；无命中不证明不存在。只读取本地对象。");
        save(pageId, owner, identity, result); return result;
    }
    private Map<String,Object> candidates(KnowledgeGit.Repository repository, String commit, String base, String path, boolean patches) {
        List<String> values;
        if (patches) values = Arrays.stream(git.read(repository.root(), "log", "--format=%H", "--max-count=1001", base + ".." + commit, "--", KnowledgeGit.literal(path)).split("\n")).filter(s -> !s.isBlank()).toList();
        else values = Arrays.stream(git.read(repository.root(), "ls-tree", "-r", "-z", commit, "--", KnowledgeGit.literal(path)).split("\0"))
                .filter(s -> s.matches("(?s)100(644|755) blob [a-f0-9]+\\t.+")).map(s -> s.substring(s.indexOf('\t') + 1))
                .filter(p -> git.allowedRepositoryPath(repository, p)).sorted().toList();
        return Map.of("candidates", values.stream().limit(1000).toList(), "limited", values.size() > 1000);
    }
    private String patch(KnowledgeGit.Repository repository, String commit, String path) {
        // Identical text and coordinates to read_knowledge_git_commit. Apply path selection to commits, not a different patch layout.
        String raw = git.read(repository.root(), "diff-tree", "--root", "-m", "--no-commit-id", "--name-only", "--no-renames", "-r", "-z", commit, "--", KnowledgeGit.literal(repository.prefix()));
        var visible = git.readableChanges(repository, raw, git.read(repository.root(), "rev-list", "--parents", "-n", "1", commit).strip().split(" "));
        if (visible.size() > 50) throw KnowledgeSources.bad("提交超过 50 个可读文件，请通过版本比较按具体路径读取");
        if (visible.isEmpty()) return "";
        var command = new ArrayList<>(List.of("show", "-m", "--format=fuller", "--no-ext-diff", "--no-textconv", "--no-renames", commit, "--"));
        visible.stream().map(KnowledgeGit::literal).forEach(command::add); return git.read(repository.root(), command.toArray(String[]::new));
    }
    private String diff(KnowledgeGit.Repository repository, String base, String commit, String path) {
        String raw = git.read(repository.root(), "diff", "--no-ext-diff", "--no-textconv", "--no-renames", "--name-only", "-z", base, commit, "--", KnowledgeGit.literal(path));
        var visible = git.readableChanges(repository, raw, base, commit);
        if (visible.size() > 50) throw KnowledgeSources.bad("比较超过 50 个可读文件，请缩小 path");
        if (visible.isEmpty()) return "没有当前范围内的可读差异";
        var command = new ArrayList<>(List.of("diff", "--no-ext-diff", "--no-textconv", "--no-renames", base, commit, "--"));
        visible.stream().map(KnowledgeGit::literal).forEach(command::add); return git.read(repository.root(), command.toArray(String[]::new));
    }
    private void save(String id, String owner, String query, Map<String,Object> body) {
        String encoded = AssistRedaction.text(json.writeValueAsString(body));
        if (encoded.length() > 750_000) throw KnowledgeSources.bad("Git 查询快照过大，请缩小路径或版本范围");
        mapper.insertSnapshot(new KnowledgeV2Mapper.Snapshot(id, owner, query, encoded, Instant.now().toString()));
    }
    private static String relative(KnowledgeGit.Repository repository, String path) { return repository.prefix().isBlank() ? path : path.substring(repository.prefix().length() + 1); }
    private static String text(Map<String,Object> args, String key) { return Objects.toString(args.get(key), ""); }
    private static int integer(Map<String,Object> args, String key, int fallback) {
        Object value = args.get(key); if (value == null) return fallback;
        if (!(value instanceof Number n) || n.doubleValue() != n.intValue()) throw KnowledgeSources.bad(key + " 必须是整数"); return n.intValue();
    }
}
