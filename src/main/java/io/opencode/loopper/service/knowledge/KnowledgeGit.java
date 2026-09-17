package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.persistence.KnowledgeV2Mapper;
import io.opencode.loopper.runtime.GitEvidenceProcess;
import io.opencode.loopper.runtime.GitBlameLines;
import io.opencode.loopper.service.PageCursor;
import io.opencode.loopper.service.assist.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Local Git reads only: no shell, checkout, fetch, hooks, textconv, external diff or lazy fetch. */
@Service
public final class KnowledgeGit {
    private final GitEvidenceProcess process;
    private final KnowledgeV2Mapper mapper;
    private final ObjectMapper json;
    public KnowledgeGit(GitEvidenceProcess process, KnowledgeV2Mapper mapper, ObjectMapper json) {
        this.process = process; this.mapper = mapper; this.json = json;
    }
    record Repository(Path project, Path root, String prefix, String identity) { }
    public KnowledgeSources.Bound source(String projectPath) {
        try {
            var repository = repository(projectPath);
            return new KnowledgeSources.Bound("git", "GIT", "项目仓库", repository.project().toString(), repository.identity(), "READY", "本地 Git 记录 · 不自动同步远端", 0);
        } catch (RuntimeException absent) { return null; }
    }
    private Repository repository(String path) {
        try {
            Path project = KnowledgeFiles.directory(path);
            Path root = Path.of(read(project, "rev-parse", "--show-toplevel").strip()).toRealPath();
            if (!project.startsWith(root)) throw KnowledgeFiles.denied();
            Path git = Path.of(read(project, "rev-parse", "--absolute-git-dir").strip()).toRealPath();
            var identity = Files.readAttributes(git, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            String prefix = root.relativize(project).toString().replace('\\', '/');
            return new Repository(project, root, prefix, AssistFiles.sha((git + ":" + identity.fileKey() + ":" + identity.creationTime()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.io.IOException invalid) { throw KnowledgeSources.bad("Git 仓库不可读，请检查项目目录"); }
    }
    private Repository authorized(KnowledgeSources.Bound source) {
        if (!"GIT".equals(source.kind())) throw KnowledgeSources.bad("请选择 Git 来源");
        var repository = repository(source.path());
        if (!repository.identity().equals(source.sha256())) throw KnowledgeSources.bad("项目仓库身份已变化，请新建对话");
        return repository;
    }
    public Map<String,Object> call(String owner, KnowledgeSources.Bound source, String tool, Map<String,Object> args) {
        var repository = authorized(source);
        Map<String,Object> result = switch (tool) {
            case "inspect_knowledge_git" -> inspect(repository);
            case "search_knowledge_git_commits", "list_knowledge_git_authors" -> history(owner, source, repository, tool, args);
            case "read_knowledge_git_commit" -> commit(repository, args);
            case "read_knowledge_git_file" -> file(repository, args, false);
            case "blame_knowledge_git_lines" -> file(repository, args, true);
            default -> throw KnowledgeSources.bad("未知 Git 查询，请选择已授权工具");
        };
        var body = new LinkedHashMap<>(result);
        body.put("kind", "GIT"); body.put("sourceId", source.id()); body.putIfAbsent("name", "项目 Git 记录");
        body.putIfAbsent("location", "当前项目范围 · 本地已知记录");
        body.put("sha256", AssistFiles.sha(json.writeValueAsBytes(result)));
        return body;
    }
    private Map<String,Object> inspect(Repository repository) {
        String branch = optional(repository.project(), "symbolic-ref", "--quiet", "--short", "HEAD");
        var refs = refs(repository);
        return Map.of("branch", branch.isBlank() ? "分离 HEAD" : branch.strip(), "refs", refs, "projectPath", repository.prefix(),
                "notice", "查询本地分支及远程跟踪引用；可能尚未包含远端最新提交。", "collectedAt", Instant.now().toString());
    }
    private List<Map<String,String>> refs(Repository repository) {
        String raw = read(repository.project(), "for-each-ref", "--count=129", "--format=%(refname)%00%(objectname)", "refs/heads", "refs/remotes", "refs/tags");
        var refs = new ArrayList<Map<String,String>>();
        for (String line : raw.split("\n")) {
            if (line.isBlank()) continue;
            String[] fields = line.split("\0", -1);
            if (fields.length == 2) refs.add(Map.of("name", fields[0], "sha", fields[1]));
        }
        if (refs.size() > 128) throw KnowledgeSources.bad("仓库引用超过 128 个，请先缩小本地仓库范围");
        String head = optional(repository.project(), "rev-parse", "--verify", "HEAD").strip();
        if (!head.isBlank()) refs.add(Map.of("name", "HEAD", "sha", head));
        return List.copyOf(refs);
    }
    private Map<String,Object> history(String owner, KnowledgeSources.Bound source, Repository repository, String tool, Map<String,Object> args) {
        var filters = new TreeMap<>(args); filters.keySet().removeAll(Set.of("scope", "cursor"));
        String query = AssistFiles.sha(json.writeValueAsBytes(List.of(source.path(), source.sha256(), tool, filters)));
        String cursor = string(args, "cursor"), snapshotId; int offset = 0; Map<String,Object> snapshot;
        if (!cursor.isBlank()) {
            var page = PageCursor.decode(cursor); snapshotId = page.id();
            try { offset = Integer.parseInt(page.value()); } catch (NumberFormatException invalid) { throw KnowledgeSources.bad("Git 分页位置无效，请重新查询"); }
            var saved = mapper.snapshot(snapshotId, owner, query);
            if (saved == null || offset < 0 || offset > 1000) throw KnowledgeSources.bad("Git 分页不属于本次查询，请重新查询");
            snapshot = json.readValue(saved.bodyJson(), new TypeReference<>() { });
        } else {
            snapshot = collect(repository, tool, args); snapshotId = UUID.randomUUID().toString();
            mapper.insertSnapshot(new KnowledgeV2Mapper.Snapshot(snapshotId, owner, query, AssistRedaction.text(json.writeValueAsString(snapshot)), Instant.now().toString()));
        }
        @SuppressWarnings("unchecked") var items = (List<Map<String,Object>>) snapshot.get("items");
        var result = new LinkedHashMap<>(snapshot);
        result.put("items", items.stream().skip(offset).limit(50).toList());
        result.put("nextCursor", offset + 50 < items.size() ? new PageCursor(Integer.toString(offset + 50), snapshotId).encode() : "");
        return result;
    }
    private Map<String,Object> collect(Repository repository, String tool, Map<String,Object> args) {
        String ref = string(args, "ref"), path = path(repository, string(args, "path"), true);
        var selected = refs(repository).stream().filter(r -> ref.isBlank() || r.get("name").equals(ref) || r.get("name").equals("refs/heads/" + ref)).toList();
        if (!ref.isBlank() && selected.isEmpty()) throw KnowledgeSources.bad("分支不属于当前仓库，请先查看 Git 来源");
        var command = new ArrayList<>(List.of("log", "-z", "--max-count=1001", "--format=%H%x00%an%x00%ae%x00%aI%x00%cn%x00%ce%x00%cI%x00%s"));
        command.addAll(selected.stream().map(r -> r.get("sha")).distinct().toList()); command.add("--"); command.add(literal(path));
        String raw = selected.isEmpty() ? "" : read(repository.root(), command.toArray(String[]::new));
        String[] fields = raw.split("\0", -1); var commits = new ArrayList<Map<String,Object>>();
        Instant since = time(args, "since"), until = time(args, "until");
        if (since != null && until != null && !since.isBefore(until)) throw KnowledgeSources.bad("开始时间必须早于结束时间");
        String author = string(args, "author").toLowerCase(Locale.ROOT), query = string(args, "query").toLowerCase(Locale.ROOT);
        String timeField = string(args, "timeField");
        if (!Set.of("", "author", "committer").contains(timeField)) throw KnowledgeSources.bad("时间类型应为 author 或 committer");
        int scanned = 0;
        for (int i = 0; i + 7 < fields.length; i += 8) {
            scanned++; if (scanned > 1000) break;
            Instant at = Instant.parse(fields[i + (timeField.equals("committer") ? 6 : 3)]);
            if (since != null && at.isBefore(since) || until != null && !at.isBefore(until)) continue;
            if (!author.isBlank() && !(fields[i+1] + " " + fields[i+2]).toLowerCase(Locale.ROOT).contains(author)) continue;
            if (!fields[i+7].toLowerCase(Locale.ROOT).contains(query)) continue;
            commits.add(Map.of("sha", fields[i], "author", fields[i+1], "email", fields[i+2], "authoredAt", fields[i+3],
                    "committer", fields[i+4], "committerEmail", fields[i+5], "committedAt", fields[i+6], "subject", fields[i+7]));
        }
        List<?> items = commits;
        if (tool.equals("list_knowledge_git_authors")) items = commits.stream().map(c -> Map.of("name", c.get("author"), "email", c.get("email"))).distinct().toList();
        return Map.of("items", items, "refs", selected, "incomplete", scanned > 1000, "scannedCommits", Math.min(scanned, 1000),
                "timeField", timeField.equals("committer") ? "committer" : "author", "collectedAt", Instant.now().toString(),
                "notice", scanned > 1000 ? "仅扫描前 1000 条提交，请缩小分支或路径；无结果不表示没有工作。" : "结果只代表本地已知 Git 记录。", "location", repository.prefix().isBlank() ? "项目仓库" : repository.prefix());
    }
    private Map<String,Object> commit(Repository repository, Map<String,Object> args) {
        String sha = revision(repository, string(args, "commit"));
        String paths = read(repository.root(), "diff-tree", "--root", "-m", "--no-commit-id", "--name-only", "--no-renames", "-r", "-z", sha, "--", literal(repository.prefix()));
        List<String> visible = Arrays.stream(paths.split("\0")).filter(p -> !p.isBlank() && allowedRepositoryPath(repository, p)).distinct().toList();
        if (visible.isEmpty()) throw KnowledgeSources.bad("此提交没有当前项目范围内的可读变更");
        var command = new ArrayList<>(List.of("show", "-m", "--format=fuller", "--no-ext-diff", "--no-textconv", "--no-renames", sha, "--"));
        visible.stream().limit(50).map(KnowledgeGit::literal).forEach(command::add);
        String text = read(repository.root(), command.toArray(String[]::new));
        var result = segment(text, integer(args,"startLine",1), integer(args,"endLine",0));
        result.put("commit", sha); result.put("files", visible.stream().limit(50).toList()); result.put("incomplete", visible.size() > 50);
        result.put("name", "提交 " + sha.substring(0,12)); result.put("location", "提交差异文本"); return result;
    }
    private Map<String,Object> file(Repository repository, Map<String,Object> args, boolean blame) {
        String sha = revision(repository, string(args, "commit")), path = path(repository, string(args, "path"), false);
        String entry = read(repository.root(), "ls-tree", "-z", sha, "--", literal(path));
        if (!entry.matches("(?s)100(644|755) blob [a-f0-9]+\\t.*\0")) throw KnowledgeSources.bad("此版本的文件不存在或不是普通文件");
        String text = read(repository.root(), "show", "--no-ext-diff", "--no-textconv", sha + ":" + path);
        var result = segment(text, integer(args,"startLine",1), integer(args,"endLine",0));
        result.put("commit", sha); result.put("path", string(args,"path")); result.put("name", string(args,"path"));
        result.put("location", sha.substring(0,12) + " · " + string(args,"path")); result.put("format", "code");
        if (blame) {
            int start = (int) result.get("startLine"), end = (int) result.get("endLine");
            String output = read(repository.root(), "blame", "--line-porcelain", "-L", start + "," + end, sha, "--", path);
            result.put("authors", GitBlameLines.parse(output, start, end));
        }
        return result;
    }
    private String revision(Repository repository, String input) {
        if (!input.matches("[a-f0-9]{40}|[a-f0-9]{64}")) throw KnowledgeSources.bad("请使用提交查询返回的完整 SHA");
        var references = refs(repository);
        if (!references.isEmpty()) {
            var args = new ArrayList<>(List.of("rev-list", "--max-count=1", input, "--not"));
            references.stream().map(ref -> ref.get("sha")).distinct().forEach(args::add);
            if (read(repository.root(), args.toArray(String[]::new)).isBlank()) return input;
        }
        throw KnowledgeSources.bad("提交不属于当前仓库可查询的引用范围");
    }
    private String path(Repository repository, String relative, boolean empty) {
        if (relative.isBlank()) { if (empty) return repository.prefix(); throw KnowledgeFiles.denied(); }
        Path path;
        try { path = Path.of(relative); } catch (InvalidPathException invalid) { throw KnowledgeFiles.denied(); }
        if (relative.contains("\\") || relative.indexOf('\0') >= 0 || path.isAbsolute() || !path.normalize().equals(path) || !KnowledgeFiles.allowed(path)
                || path.startsWith("..") || relative.equals(".")) throw KnowledgeFiles.denied();
        return repository.prefix().isBlank() ? relative : repository.prefix() + "/" + relative;
    }
    private boolean allowedRepositoryPath(Repository repository, String path) {
        String prefix = repository.prefix();
        if (!prefix.isBlank() && !path.startsWith(prefix + "/")) return false;
        String relative = prefix.isBlank() ? path : path.substring(prefix.length() + 1);
        return KnowledgeFiles.allowed(Path.of(relative));
    }
    static Map<String,Object> segment(String text, int start, int requestedEnd) {
        if (text.indexOf('\0') >= 0) throw KnowledgeSources.bad("此 Git 文件不是可读取文本");
        String[] lines = text.split("\n", -1);
        if (start < 1 || start > lines.length || requestedEnd != 0 && requestedEnd < start) throw KnowledgeSources.bad("引用行范围无效");
        int end = Math.min(lines.length, Math.min(start + 199, requestedEnd > 0 ? requestedEnd : Integer.MAX_VALUE));
        var body = new StringBuilder(); int actual = start - 1;
        for (int i = start - 1; i < end; i++) { if (body.length() + lines[i].length() + 1 > 12000) break; body.append(lines[i]).append('\n'); actual = i + 1; }
        if (actual < start) throw KnowledgeSources.bad("单行文本过长，请缩小读取内容");
        var result = new LinkedHashMap<String,Object>(); result.put("text", body.toString()); result.put("startLine", start); result.put("endLine", actual);
        result.put("totalLines", lines.length); result.put("nextLine", actual < lines.length ? actual + 1 : -1); return result;
    }
    private String read(Path directory, String... args) {
        String output = optional(directory, args); return output;
    }
    private String optional(Path directory, String... args) {
        var command = new ArrayList<>(List.of("--no-pager", "-c", "core.fsmonitor=false")); command.addAll(List.of(args));
        var result = process.run(directory, Duration.ofSeconds(5), command, Map.of("GIT_NO_LAZY_FETCH", "1"));
        if (result.exitCode() != 0) {
            if (args[0].equals("symbolic-ref") || args[0].equals("rev-parse") && List.of(args).contains("--verify")) return "";
            throw KnowledgeSources.bad("Git 读取失败，请检查仓库或缩小查询范围");
        }
        return result.output();
    }
    private static String literal(String path) { return ":(literal)" + (path.isBlank() ? "." : path); }
    private static String string(Map<String,Object> args, String key) { String value = Objects.toString(args.get(key), ""); if (value.length() > 4096) throw KnowledgeSources.bad("Git 查询参数过长"); return value; }
    private static int integer(Map<String,Object> args, String key, int fallback) { return args.get(key) instanceof Number n ? n.intValue() : fallback; }
    private static Instant time(Map<String,Object> args, String key) { String value = string(args,key); try { return value.isBlank() ? null : OffsetDateTime.parse(value).toInstant(); } catch (java.time.format.DateTimeParseException invalid) { throw KnowledgeSources.bad("日期请包含时区，例如 2026-09-16T00:00:00+08:00"); } }
}
