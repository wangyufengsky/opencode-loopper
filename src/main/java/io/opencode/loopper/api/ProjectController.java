package io.opencode.loopper.api;

import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.ProjectConventionDraftRow;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.DirectoryPickerService;
import io.opencode.loopper.service.ProjectConventionService;
import io.opencode.loopper.service.ProjectService;
import io.opencode.loopper.service.ProjectStackProfileService;
import io.opencode.loopper.service.ProjectStackSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
    private final ProjectConventionService conventions;
    private final ProjectStackProfileService stackProfiles;
    public ProjectController(ProjectService service, DirectoryPickerService directoryPicker,
                             ProjectConventionService conventions, ProjectStackProfileService stackProfiles) {
        this.service = service;
        this.directoryPicker = directoryPicker;
        this.conventions = conventions;
        this.stackProfiles = stackProfiles;
    }
    @GetMapping public List<ProjectDto> list() { return service.list().stream().map(this::dto).toList(); }
    @GetMapping("/{id}") public ProjectDto get(@PathVariable String id) { return dto(service.get(id)); }
    @GetMapping("/{id}/stack-profile")
    public ProjectStackProfileDto stackProfile(@PathVariable String id) {
        return stackProfileDto(stackProfiles.current(id));
    }
    @PostMapping public ResponseEntity<ProjectDto> create(@Valid @RequestBody ProjectRequest request) {
        ProjectRow row = service.create(request.name(), request.rootPath(), request.description());
        return ResponseEntity.created(URI.create("/api/projects/" + row.id())).body(dto(row));
    }
    @PostMapping("/pick-directory")
    public DirectorySelectionDto pickDirectory(@RequestHeader("X-Loopper-Local-UI") String localUi) {
        requireLocalUi(localUi);
        return directoryPicker.pickDirectory()
                .map(path -> new DirectorySelectionDto(true, path, directoryName(path)))
                .orElseGet(() -> new DirectorySelectionDto(false, null, null));
    }
    @PutMapping("/{id}") public ProjectDto rename(@PathVariable String id, @Valid @RequestBody RenameProjectRequest request) { return dto(service.rename(id, request.name())); }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelManagement(@PathVariable String id,
                                                 @RequestHeader("X-Loopper-Local-UI") String localUi) {
        requireLocalUi(localUi);
        service.cancelManagement(id);
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/{id}/agents-md")
    public CurrentProjectConventionDto currentConvention(@PathVariable String id) {
        ProjectConventionService.CurrentConvention current = conventions.current(id);
        return new CurrentProjectConventionDto(current.projectId(), current.exists(), current.loopperManaged(), current.content());
    }
    @PostMapping("/{id}/agents-md")
    public ResponseEntity<ProjectConventionDto> generateConvention(@PathVariable String id,
                                                                    @RequestHeader("X-Loopper-Local-UI") String localUi) {
        requireLocalUi(localUi);
        return ResponseEntity.accepted().body(conventionDto(conventions.generate(id)));
    }
    @GetMapping("/{id}/agents-md/{draftId}")
    public ProjectConventionDto convention(@PathVariable String id, @PathVariable String draftId) {
        return conventionDto(conventions.get(id, draftId));
    }
    @PutMapping("/{id}/agents-md/{draftId}")
    public ProjectConventionDto applyConvention(@PathVariable String id, @PathVariable String draftId,
                                                @RequestHeader("X-Loopper-Local-UI") String localUi) {
        requireLocalUi(localUi);
        return conventionDto(conventions.apply(id, draftId));
    }
    public record ProjectRequest(@NotBlank String name, @NotBlank String rootPath,
                                 @Size(max = 500) String description) { }
    public record RenameProjectRequest(@NotBlank String name) { }
    public record DirectorySelectionDto(boolean selected, String path, String name) { }
    public record ProjectConventionDto(String id, String projectId, String state, String operation,
                                       boolean readOnlyGeneration, String content, String normalizationNotice,
                                       String error, String updatedAt, String stackProfileId,
                                       String stackFingerprint) { }
    public record CurrentProjectConventionDto(String projectId, boolean exists, boolean loopperManaged, String content) { }
    private String directoryName(String path) { Path fileName = Path.of(path).getFileName(); return fileName == null ? path : fileName.toString(); }
    private ProjectDto dto(ProjectRow row) {
        var inspection = service.inspect(row);
        ProjectStackSnapshot stack = stackProfiles.current(row.id());
        String status = !inspection.pathAvailable() ? "INVALID" : inspection.isolatedWorktree() ? "READY" : "NEEDS_GIT";
        String executionMode = inspection.isolatedWorktree() ? "WORKTREE" : inspection.pathAvailable() ? "DIRECT" : "UNAVAILABLE";
        return new ProjectDto(row.id(), row.name(), row.rootPath(), status, row.description(), inspection.branch(),
                executionMode, row.updatedAt(), service.taskCount(row.id()), service.openDesignerSessionCount(row.id()),
                stack.state().name(), stack.technologyFamilies(), stack.components().size(), stack.analyzedAt());
    }
    private ProjectConventionDto conventionDto(ProjectConventionDraftRow row) {
        return new ProjectConventionDto(row.id(), row.projectId(), row.state(), row.sourceExists() == 1 ? "UPDATE" : "CREATE",
                true, row.proposedContent(), row.normalizationNotice(), row.errorMessage(), row.updatedAt(),
                row.projectStackProfileId(), row.stackFingerprint());
    }
    private void requireLocalUi(String localUi) {
        if (!"1".equals(localUi)) {
            throw new BadRequestException("LOCAL_UI_HEADER_REQUIRED", "This operation is available only to the local Loopper UI");
        }
    }
    public record ProjectDto(String id, String name, String rootPath, String status, String description, String branch,
                             String executionMode, String updatedAt, int taskCount,
                             int openDesignerSessionCount, String stackProfileState,
                             List<String> stackTechnologyFamilies, int stackComponentCount,
                             String stackAnalyzedAt) { }
    public record ProjectStackProfileDto(String id, String projectId, String state, String manifestFingerprint,
                                         List<String> technologyFamilies, List<String> technologies,
                                         int filesScanned, String errorCode, String errorDetail, String analyzedAt,
                                         List<ProjectStackComponentDto> components) { }
    public record ProjectStackComponentDto(String key, String relativeRoot, List<String> technologyFamilies,
                                           List<String> technologies, List<String> buildTools,
                                           List<String> testFrameworks, List<String> manifestSources) { }
    private ProjectStackProfileDto stackProfileDto(ProjectStackSnapshot profile) {
        return new ProjectStackProfileDto(profile.id(), profile.projectId(), profile.state().name(),
                profile.manifestFingerprint(), profile.technologyFamilies(), profile.technologies(),
                profile.filesScanned(), profile.errorCode(), profile.errorDetail(), profile.analyzedAt(),
                profile.components().stream().map(component -> new ProjectStackComponentDto(component.key(),
                        component.relativeRoot(), component.technologyFamilies(), component.technologies(),
                        component.buildTools(), component.testFrameworks(), component.manifestSources())).toList());
    }
}
