package io.opencode.loopper.api;

import io.opencode.loopper.service.knowledge.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@RestController
@RequestMapping("/api/projects/{project}/knowledge-sources")
public class KnowledgeSourceController {
    private final KnowledgeSources sources;
    private final KnowledgeReader reader;
    private final KnowledgeGit git;
    private final io.opencode.loopper.service.assist.DatabaseQueryService databases;
    public KnowledgeSourceController(KnowledgeSources sources, KnowledgeReader reader, io.opencode.loopper.service.assist.DatabaseQueryService databases, KnowledgeGit git) { this.git = git; this.sources = sources; this.reader = reader; this.databases = databases; }
    @GetMapping public CursorPage<KnowledgeSources.View> list(@PathVariable String project,
            @RequestParam(required=false) String cursor, @RequestParam(required=false) Integer limit) { return sources.list(project, cursor, limit); }
    public record Directory(String path) { }
    public record Revision(long version) { }
    @PostMapping("/directories") public KnowledgeSources.View directory(@PathVariable String project, @RequestBody Directory input,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String localUi) { KnowledgeController.requireUi(localUi); return sources.addDirectory(project, input.path()); }
    @PostMapping("/uploads") public List<KnowledgeSources.View> upload(@PathVariable String project, @RequestPart("files") List<MultipartFile> files,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String localUi) {
        KnowledgeController.requireUi(localUi);
        if (files.isEmpty() || files.size()>10 || files.stream().anyMatch(f -> f.getSize()<=0 || f.getSize()>20L*1024*1024)
                || files.stream().mapToLong(MultipartFile::getSize).sum()>50L*1024*1024) throw KnowledgeSources.bad("每次最多 10 份文档，单文件 20 MiB，总计 50 MiB");
        List<KnowledgeSources.Incoming> incoming = new ArrayList<>();
        try { for (var file : files) incoming.add(new KnowledgeSources.Incoming(file.getOriginalFilename(), file.getBytes())); }
        catch (java.io.IOException failure) { throw KnowledgeSources.bad("上传尚未完成，请重新选择文件"); }
        return sources.upload(project, incoming);
    }
    @PostMapping("/{source}/refresh") public KnowledgeSources.View refresh(@PathVariable String project, @PathVariable String source, @RequestBody Revision input,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String localUi) { KnowledgeController.requireUi(localUi); return sources.refresh(project, source, input.version()); }
    @DeleteMapping("/{source}") public void remove(@PathVariable String project, @PathVariable String source, @RequestParam long version,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String localUi) { KnowledgeController.requireUi(localUi); sources.remove(project, source, version); }
    @GetMapping("/{source}/git") public Map<String,Object> git(@PathVariable String project, @PathVariable String source,
            @RequestParam(required=false) String conversationId, @RequestParam(defaultValue="inspect_knowledge_git") String tool,
            @RequestParam Map<String,String> parameters) {
        var bound = sources.selected(project, conversationId, source); Map<String,Object> args = new LinkedHashMap<>();
        for (String key : List.of("ref", "path", "query", "author", "since", "until", "timeField", "cursor", "commit")) if (parameters.containsKey(key)) args.put(key, parameters.get(key));
        for (String key : List.of("startLine", "endLine")) if (parameters.containsKey(key)) {
            try { args.put(key, Integer.parseInt(parameters.get(key))); } catch (NumberFormatException invalid) { throw KnowledgeSources.bad("Git 行号无效"); }
        }
        return git.call(conversationId == null || conversationId.isBlank() ? "project:" + project : conversationId, bound, tool, args);
    }
    @GetMapping("/{source}/database") public Map<String,Object> database(@PathVariable String project, @PathVariable String source,
            @RequestParam(required=false) String conversationId, @RequestParam(required=false) String schema,
            @RequestParam(required=false) String table, @RequestParam(defaultValue="tables") String kind, @RequestParam(defaultValue="0") int offset) {
        var connection = sources.connection(project, conversationId, source);
        if (schema == null || schema.isBlank()) return Map.of("schemas", connection.config().schemas(), "name", connection.name(), "kind", "DATABASE");
        var result = new LinkedHashMap<>(databases.inspect(connection, schema, table, kind, offset));
        result.put("kind", "DATABASE"); result.put("name", connection.name()); result.put("collectedAt", java.time.Instant.now().toString()); return result;
    }
    @GetMapping("/{source}/directory") public KnowledgeFiles.Listing browse(@PathVariable String project, @PathVariable String source,
            @RequestParam(required=false) String conversationId, @RequestParam(required=false) String path,
            @RequestParam(required=false) String query, @RequestParam(required=false) String cursor) {
        return reader.browse(sources.selected(project, conversationId, source), path, query, cursor);
    }
    @GetMapping("/{source}/content") public Map<String,Object> read(@PathVariable String project, @PathVariable String source,
            @RequestParam(required=false) String conversationId, @RequestParam(required=false) String path,
            @RequestParam(defaultValue="0") int offset, @RequestParam(defaultValue="-1") int section, @RequestParam(defaultValue="1") int startLine, @RequestParam(defaultValue="0") int endLine, @RequestParam(required=false) String expectedSha) {
        return reader.readRange(sources.selected(project, conversationId, source), path, section, startLine, endLine, expectedSha, offset);
    }
    @GetMapping("/{source}/search") public Map<String,Object> search(@PathVariable String project, @PathVariable String source,
            @RequestParam(required=false) String conversationId, @RequestParam(required=false) String path,
            @RequestParam String query, @RequestParam(required=false) String cursor) {
        return reader.search(sources.selected(project, conversationId, source), path, query, cursor);
    }
}
