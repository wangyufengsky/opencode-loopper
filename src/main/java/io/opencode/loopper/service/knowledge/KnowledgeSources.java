package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.persistence.KnowledgeRows.Source;
import io.opencode.loopper.service.*;
import io.opencode.loopper.service.assist.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class KnowledgeSources {
    private final KnowledgeMapper mapper;
    private final ProjectService projects;
    private final DatabaseConnectionService databases;
    private final KnowledgeDocumentCache documents;
    private final ObjectMapper json;
    private final Path storage;
    private final KnowledgeGit git;
    public KnowledgeSources(KnowledgeMapper mapper, ProjectService projects, DatabaseConnectionService databases,
            KnowledgeDocumentCache documents, ObjectMapper json, LoopperProperties properties, KnowledgeGit git) {
        this.git = git; this.mapper = mapper; this.projects = projects; this.databases = databases; this.documents = documents; this.json = json;
        this.storage = canonicalDataRoot(properties.getDataDir()).resolve("knowledge/files");
    }
    private static Path canonicalDataRoot(Path configured) {
        Path absolute = configured.toAbsolutePath().normalize(), existing = absolute;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        try { return existing == null ? absolute : existing.toRealPath().resolve(existing.relativize(absolute)); }
        catch (java.io.IOException failure) { throw bad("受管资料目录无法读取，请检查数据目录"); }
    }
    public record Bound(String id, String kind, String name, String path, String sha256, String state, String detail, long version) { }
    public record View(String id, String kind, String name, String state, String detail, long version) { }
    public record Selection(List<Bound> sources, List<DatabaseConnectionService.Bound> connections) { }
    public record Incoming(String name, byte[] bytes) { }
    public static View view(Bound source) { return new View(source.id(), source.kind(), source.name(), source.state(), source.detail(), source.version()); }
    private static Bound bound(Source row) { return new Bound(row.id(), row.kind(), row.name(), row.path(), row.sha256(), row.state(), row.detail(), row.version()); }
    public CursorPage<View> list(String project, String cursor, Integer requested) {
        var root = projects.get(project); var page = PageCursor.decode(cursor); int limit = PageCursor.limit(requested);
        var rows = mapper.sources(project, page == null ? "" : page.value(), page == null ? "" : page.id(), limit + 1);
        List<View> items = new ArrayList<>();
        if (page == null) {
            items.add(view(new Bound("code", "CODE", "项目代码", root.rootPath(), null, "READY", "包含当前本地改动", 0)));
            items.add(view(new Bound("documents", "DOCUMENTS", "项目文档", root.rootPath(), null, "READY", "项目目录内的文档", 0)));
            if (root.documentPath() != null && !root.documentPath().isBlank()) items.add(view(projectDocuments(root.documentPath(), root.version())));
            var repository = git.source(root.rootPath()); if (repository != null) items.add(view(repository));
            databases.forProject(project).forEach(c -> items.add(new View("database:" + c.id(), "DATABASE", c.name(), "READY", String.join("、", c.config().schemas()), c.version())));
        }
        var visible = rows.stream().limit(limit).toList(); visible.forEach(s -> items.add(view(bound(s))));
        String next = rows.size() > limit ? new PageCursor(visible.getLast().createdAt(), visible.getLast().id()).encode() : null;
        return new CursorPage<>(items, next);
    }
    private Bound projectDocuments(String path, long version) {
        try { return new Bound("project-documents", "DOCUMENTS", "项目文档目录", KnowledgeFiles.directory(path).toString(), null, "READY", "来自项目 documentPath", version); }
        catch (RuntimeException unavailable) { return new Bound("project-documents", "DOCUMENTS", "项目文档目录", Objects.toString(path, ""), null, "FAILED", "项目文档目录不存在或不可读，请检查项目管理中的文档路径", version); }
    }
    public Selection freeze(String project, List<String> ids) {
        var root = projects.get(project); if (root.managed() != 1) throw bad("项目已取消管理，请重新选择项目");
        if (ids == null || ids.isEmpty() || ids.size() > 100 || new HashSet<>(ids).size() != ids.size()) throw bad("请选择 1–100 项不重复的资料来源");
        List<Bound> sources = new ArrayList<>(); List<DatabaseConnectionService.Bound> connections = new ArrayList<>();
        var available = databases.forProject(project);
        for (String id : ids) {
            if ("code".equals(id) || "documents".equals(id)) sources.add(new Bound(id, "code".equals(id) ? "CODE" : "DOCUMENTS",
                    "code".equals(id) ? "项目代码" : "项目文档", KnowledgeFiles.directory(root.rootPath()).toString(), null, "READY", "", 0));
            else if ("project-documents".equals(id)) {
                var source = projectDocuments(root.documentPath(), root.version());
                if (!source.state().equals("READY")) throw bad(source.detail()); sources.add(source);
            } else if ("git".equals(id)) {
                var source = git.source(root.rootPath()); if (source == null) throw bad("项目 Git 仓库不可用"); sources.add(source);
            } else if (id != null && id.startsWith("database:")) connections.add(available.stream().filter(c -> id.equals("database:" + c.id())).findFirst().orElseThrow(() -> bad("数据库连接未授权给当前项目")));
            else { var row = require(project, id); if (!"READY".equals(row.state())) throw bad("所选资料尚不可用，请刷新资料来源"); sources.add(bound(row)); }
        }
        return new Selection(List.copyOf(sources), List.copyOf(connections));
    }
    public List<Bound> frozen(KnowledgeRows.Conversation conversation) { return json.readValue(conversation.sourcesJson(), new TypeReference<>() { }); }
    public List<DatabaseConnectionService.Bound> connections(KnowledgeRows.Conversation conversation) { return json.readValue(conversation.connectionsJson(), new TypeReference<>() { }); }
    public Selection selection(String project, String conversationId, List<String> ids) {
        if (conversationId == null || conversationId.isBlank()) return freeze(project, ids);
        var conversation = mapper.conversation(conversationId).filter(c -> c.projectId().equals(project)).orElseThrow(() -> bad("会话不属于当前项目"));
        return KnowledgeSearchService.subset(new Selection(frozen(conversation), connections(conversation)), ids);
    }
    public List<View> frozenViews(KnowledgeRows.Conversation conversation) {
        var result = new ArrayList<>(frozen(conversation).stream().map(KnowledgeSources::view).toList());
        connections(conversation).forEach(c -> result.add(new View("database:" + c.id(), "DATABASE", c.name(), "READY", String.join("、", c.config().schemas()), c.version())));
        return result;
    }
    public DatabaseConnectionService.Bound connection(String project, String conversationId, String sourceId) {
        if (conversationId == null || conversationId.isBlank()) return freeze(project, List.of(sourceId)).connections().stream().findFirst().orElseThrow(() -> bad("数据库未授权"));
        var conversation = mapper.conversation(conversationId).filter(c -> c.projectId().equals(project)).orElseThrow(() -> bad("会话不属于当前项目"));
        return connections(conversation).stream().filter(c -> sourceId.equals("database:" + c.id())).findFirst().orElseThrow(() -> bad("数据库未授权给当前会话"));
    }
    public Bound selected(String project, String conversationId, String sourceId) {
        if (conversationId == null || conversationId.isBlank()) return freeze(project, List.of(sourceId)).sources().stream().findFirst().orElseThrow(() -> bad("请使用数据库结构入口"));
        var conversation = mapper.conversation(conversationId).filter(c -> c.projectId().equals(project)).orElseThrow(() -> bad("会话不属于当前项目"));
        return frozen(conversation).stream().filter(s -> s.id().equals(sourceId)).findFirst().orElseThrow(() -> bad("资料不属于当前会话"));
    }
    public View addDirectory(String project, String path) {
        projects.get(project); Path root = KnowledgeFiles.directory(path); String now = Instant.now().toString();
        var old = mapper.duplicateDirectory(project, root.toString()); if (old.isPresent()) return view(bound(old.get()));
        Source row = new Source(UUID.randomUUID().toString(), project, "DIRECTORY", root.getFileName().toString(), root.toString(), null, "READY", "", now, now, 0);
        mapper.insertSource(row); return view(bound(row));
    }
    public List<View> upload(String project, List<Incoming> files) {
        projects.get(project);
        if (files == null || files.isEmpty() || files.size() > 10 || files.stream().anyMatch(f -> f.bytes() == null || f.bytes().length == 0 || f.bytes().length > 20 * 1024 * 1024)
                || files.stream().mapToLong(f -> f.bytes().length).sum() > 50L * 1024 * 1024) throw bad("每次最多 10 份文档，单文件 20 MiB，总计 50 MiB");
        List<View> result = new ArrayList<>();
        for (Incoming file : files) {
            try { result.add(uploadOne(project, file)); }
            catch (AssistFailure invalid) { result.add(new View(UUID.randomUUID().toString(), "UPLOAD", Objects.toString(file.name(), "文档"), "FAILED", invalid.getMessage(), 0)); }
        }
        return result;
    }
    private View uploadOne(String project, Incoming file) {
        String name = file.name() == null ? "document" : file.name();
        if (name.length() > 240 || name.contains("/") || name.contains("\\") || name.chars().anyMatch(Character::isISOControl)
                || !KnowledgeFiles.DOCUMENTS.contains(KnowledgeFiles.extension(name))) throw bad("请上传 Markdown、DOCX、XLSX、PPTX 或文本 PDF");
        var existing = mapper.duplicateUpload(project, AssistFiles.sha(file.bytes()), name);
        if (existing.isPresent() && !existing.get().state().equals("FAILED")) return view(bound(existing.get()));
        String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        Path target = storage.resolve(id + "." + KnowledgeFiles.extension(name));
        var row = new Source(id, project, "UPLOAD", name, target.toString(), AssistFiles.sha(file.bytes()), "PREPARED", "正在保存与解析", now, now, 0);
        mapper.insertSource(row);
        try {
            requireStorage(); Files.createDirectories(storage); requireStorage();
            Path pending = target.resolveSibling(target.getFileName() + ".part");
            Files.write(pending, file.bytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try (var channel = java.nio.channels.FileChannel.open(pending, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
            documents.parse(name, file.bytes());
            mapper.sourceState(id, 0, "READY", "", Instant.now().toString());
        } catch (Exception e) { mapper.sourceState(id, 0, "FAILED", e instanceof AssistFailure ? e.getMessage() : "文件保存未完成，请刷新重试", Instant.now().toString()); }
        return view(bound(require(project, id)));
    }
    public View refresh(String project, String id, long version) {
        Source row = require(project, id); if (row.version() != version) throw new ConflictException("KNOWLEDGE_SOURCE_CHANGED", "资料配置已变化，请刷新");
        String state = "READY", detail = "";
        try { if (row.kind().equals("UPLOAD")) { recoverFile(row); var loaded = documents.load(uploadPath(bound(row)), row.name()); if (!loaded.sha256().equals(row.sha256())) throw bad("上传原件已变化，不能替换原资料版本"); }
            else KnowledgeFiles.directory(row.path());
        } catch (RuntimeException e) { state = "FAILED"; detail = e instanceof AssistFailure ? e.getMessage() : "资料读取失败，请检查文件"; }
        if (mapper.sourceState(id, version, state, detail, Instant.now().toString()) != 1) throw new ConflictException("KNOWLEDGE_SOURCE_CHANGED", "资料配置已变化，请刷新");
        return view(bound(require(project, id)));
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${loopper.knowledge-upload-recovery-delay:30000}")
    public void recoverUploads() {
        for (Source row : mapper.preparedSources()) {
            if (Instant.parse(row.createdAt()).plusSeconds(60).isAfter(Instant.now())) continue;
            try { refresh(row.projectId(), row.id(), row.version()); } catch (RuntimeException changed) { /* Another refresh owns this version. */ }
        }
    }
    private void recoverFile(Source row) {
        Path target = uploadPath(bound(row)), pending = target.resolveSibling(target.getFileName() + ".part");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            if (!AssistFiles.sha(AssistFiles.read(pending, 20 * 1024 * 1024)).equals(row.sha256())) throw bad("上传未完成，请重新上传此文件");
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.io.IOException failure) { throw bad("上传未完成，请重新上传此文件"); }
    }
    public void remove(String project, String id, long version) {
        require(project, id);
        if (mapper.sourceState(id, version, "REMOVED", "", Instant.now().toString()) != 1) throw new ConflictException("KNOWLEDGE_SOURCE_CHANGED", "资料配置已变化，请刷新");
    }
    public byte[] read(Bound source, String relative, int limit) {
        Path selected = path(source, relative);
        Path root = source.kind().equals("UPLOAD") ? storage : KnowledgeFiles.directory(source.path());
        return KnowledgeFiles.read(root, root.relativize(selected).toString(), limit);
    }
    public Path path(Bound source, String relative) { return source.kind().equals("UPLOAD") ? uploadPath(source) : KnowledgeFiles.resolve(source.path(), relative); }
    private Path uploadPath(Bound source) {
        requireStorage(); Path path = Path.of(source.path()).toAbsolutePath().normalize();
        if (!path.getParent().equals(storage) || Files.isSymbolicLink(path)) throw KnowledgeFiles.denied(); return path;
    }
    private void requireStorage() {
        for (Path p = storage; p != null; p = p.getParent()) if (Files.isSymbolicLink(p)) throw KnowledgeFiles.denied();
    }
    private Source require(String project, String id) { return mapper.source(project, id).filter(s -> !s.state().equals("REMOVED")).orElseThrow(() -> bad("资料不存在或已移除")); }
    public static AssistFailure bad(String message) { return new AssistFailure("KNOWLEDGE_SOURCE_INVALID", message); }
}
