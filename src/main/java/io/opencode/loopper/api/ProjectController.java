package io.opencode.loopper.api;

import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.DirectoryPickerService;
import io.opencode.loopper.service.ProjectService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private final ProjectService service;
    private final DirectoryPickerService directoryPicker;
    public ProjectController(ProjectService service, DirectoryPickerService directoryPicker) { this.service = service; this.directoryPicker = directoryPicker; }
    @GetMapping public List<ProjectDto> list() { return service.list().stream().map(this::dto).toList(); }
    @GetMapping("/{id}") public ProjectDto get(@PathVariable String id) { return dto(service.get(id)); }
    @PostMapping public ResponseEntity<ProjectDto> create(@Valid @RequestBody ProjectRequest request) {
        ProjectRow row = service.create(request.name(), request.rootPath());
        return ResponseEntity.created(URI.create("/api/projects/" + row.id())).body(dto(row));
    }
    @PostMapping("/pick-directory")
    public DirectorySelectionDto pickDirectory(@RequestHeader("X-Loopper-Local-UI") String localUi) {
        if (!"1".equals(localUi)) {
            throw new BadRequestException("LOCAL_UI_HEADER_REQUIRED", "Folder selection is available only to the local Loopper UI");
        }
        return directoryPicker.pickDirectory()
                .map(path -> new DirectorySelectionDto(true, path, directoryName(path)))
                .orElseGet(() -> new DirectorySelectionDto(false, null, null));
    }
    @PutMapping("/{id}") public ProjectDto rename(@PathVariable String id, @Valid @RequestBody RenameProjectRequest request) { return dto(service.rename(id, request.name())); }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable String id) { service.delete(id); return ResponseEntity.noContent().build(); }
    public record ProjectRequest(@NotBlank String name, @NotBlank String rootPath) { }
    public record RenameProjectRequest(@NotBlank String name) { }
    public record DirectorySelectionDto(boolean selected, String path, String name) { }
    private String directoryName(String path) { Path fileName = Path.of(path).getFileName(); return fileName == null ? path : fileName.toString(); }
    private ProjectDto dto(ProjectRow row) { return new ProjectDto(row.id(), row.name(), row.rootPath(), "READY", null, null, row.updatedAt(), service.taskCount(row.id())); }
    public record ProjectDto(String id, String name, String rootPath, String status, String description, String branch, String updatedAt, int taskCount) { }
}
