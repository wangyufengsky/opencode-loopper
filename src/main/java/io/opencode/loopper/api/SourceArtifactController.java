package io.opencode.loopper.api;

import io.opencode.loopper.persistence.SourceArtifactMapper;
import io.opencode.loopper.service.SourceArtifactFiles;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/template-tasks/source-runs/{id}/artifacts")
public final class SourceArtifactController {
    private final SourceArtifactFiles files;
    public SourceArtifactController(SourceArtifactFiles files) { this.files = files; }
    @GetMapping
    public CursorPage<SourceArtifactMapper.Metadata> list(@PathVariable String id,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
        return files.list(id, cursor, limit);
    }
    @GetMapping("/{artifactId}")
    public SourceArtifactMapper.Artifact read(@PathVariable String id, @PathVariable String artifactId) {
        return files.read(id, artifactId);
    }
    @GetMapping("/content")
    public SourceArtifactMapper.Artifact named(@PathVariable String id, @RequestParam String name) { return files.named(id, name); }
    @GetMapping("/{artifactId}/download")
    public ResponseEntity<byte[]> download(@PathVariable String id, @PathVariable String artifactId) {
        var row = files.read(id, artifactId);
        return response("text/markdown", row.name(), row.content().getBytes(StandardCharsets.UTF_8));
    }
    @GetMapping("/download")
    public ResponseEntity<byte[]> bundle(@PathVariable String id) {
        return response("application/zip", "detailed-design-" + id + ".zip", files.bundle(id));
    }
    private static ResponseEntity<byte[]> response(String type, String name, byte[] bytes) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff").body(bytes);
    }
}
