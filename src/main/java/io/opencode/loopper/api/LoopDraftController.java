package io.opencode.loopper.api;

import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.service.LoopDraftService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/loop-drafts")
public class LoopDraftController {
    private final LoopDraftService service;
    public LoopDraftController(LoopDraftService service) { this.service = service; }
    @PostMapping public ResponseEntity<LoopDraftDto> create(@Valid @RequestBody DraftSpecRequest request) {
        LoopDraftRow row = service.create(request.spec()); return ResponseEntity.created(URI.create("/api/loop-drafts/" + row.id())).body(dto(row));
    }
    @GetMapping("/{id}") public LoopDraftDto get(@PathVariable String id) { return dto(service.get(id)); }
    @PutMapping("/{id}") public LoopDraftDto update(@PathVariable String id, @Valid @RequestBody DraftSpecRequest request) { return dto(service.update(id, request.spec())); }
    @PostMapping("/{id}/confirm") public TaskReference confirm(@PathVariable String id, @RequestBody(required = false) ConfirmDraftRequest request) {
        return new TaskReference(service.confirm(id, request == null ? null : request.title()).id());
    }
    public record ConfirmDraftRequest(String title) { }
    public record DraftSpecRequest(@NotNull @Valid LoopSpec spec) { }
    public record LoopDraftDto(String id, String status, String updatedAt, LoopSpec spec) { }
    public record TaskReference(String taskId) { }
    private LoopDraftDto dto(LoopDraftRow row) { return new LoopDraftDto(row.id(), row.status(), row.updatedAt(), service.spec(row)); }
}
