package io.opencode.loopper.service;

import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.runtime.GitWorktreeManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {
    private final LoopperMapper mapper;
    private final LoopperProperties properties;
    private final GitWorktreeManager worktrees;
    public ProjectService(LoopperMapper mapper, LoopperProperties properties, GitWorktreeManager worktrees) {
        this.mapper = mapper;
        this.properties = properties;
        this.worktrees = worktrees;
    }
    @Transactional
    public ProjectRow create(String name, String rootPath) {
        return create(name, rootPath, "");
    }
    @Transactional
    public ProjectRow create(String name, String rootPath, String description) {
        if (name == null || name.isBlank()) throw new BadRequestException("PROJECT_NAME_REQUIRED", "Project name is required");
        String root = canonicalDirectory(rootPath);
        requireAllowedRoot(root);
        String normalizedDescription = normalizeDescription(description);
        String now = Instant.now().toString();
        var existing = mapper.findProjectByRoot(root);
        if (existing.isPresent()) {
            ProjectRow old = existing.get();
            if (old.managed() == 1) {
                throw new ConflictException("PROJECT_ALREADY_MANAGED", "Project root is already managed");
            }
            ProjectRow restored = new ProjectRow(old.id(), name.trim(), old.rootPath(), normalizedDescription,
                    old.createdAt(), now, 1, old.version());
            if (mapper.updateProject(restored) != 1) {
                throw new ConflictException("PROJECT_VERSION_CONFLICT", "Project was updated concurrently");
            }
            return get(old.id());
        }
        ProjectRow project = new ProjectRow(UUID.randomUUID().toString(), name.trim(), root, normalizedDescription,
                now, now, 1, 0);
        mapper.insertProject(project);
        return project;
    }
    public List<ProjectRow> list() { return mapper.listProjects(); }
    public int taskCount(String projectId) { return mapper.countTasksForProject(projectId); }
    public ProjectRow get(String id) { return mapper.findProject(id).orElseThrow(() -> new NotFoundException("Project not found: " + id)); }
    public GitWorktreeManager.RepositoryInspection inspect(ProjectRow project) {
        return worktrees.inspect(Path.of(project.rootPath()));
    }
    @Transactional
    public ProjectRow rename(String id, String name) {
        ProjectRow old = get(id);
        if (name == null || name.isBlank()) throw new BadRequestException("PROJECT_NAME_REQUIRED", "Project name is required");
        ProjectRow changed = new ProjectRow(old.id(), name.trim(), old.rootPath(), old.description(), old.createdAt(),
                Instant.now().toString(), old.managed(), old.version());
        if (mapper.updateProject(changed) != 1) throw new ConflictException("PROJECT_VERSION_CONFLICT", "Project was updated concurrently");
        return get(id);
    }
    @Transactional
    public void cancelManagement(String id) {
        ProjectRow project = get(id);
        if (project.managed() != 1 || mapper.unmanageProject(id, Instant.now().toString()) != 1) {
            throw new NotFoundException("Managed project not found: " + id);
        }
    }
    public String canonicalDirectory(String input) {
        if (input == null || input.isBlank()) throw new BadRequestException("PROJECT_PATH_REQUIRED", "Project root path is required");
        try {
            Path path = Path.of(input).toRealPath();
            if (!Files.isDirectory(path)) throw new BadRequestException("PROJECT_PATH_NOT_DIRECTORY", "Project root must be a directory");
            return path.toString();
        } catch (BadRequestException e) { throw e; }
        catch (Exception e) { throw new BadRequestException("PROJECT_PATH_INVALID", "Project root cannot be resolved safely: " + e.getMessage()); }
    }
    private void requireAllowedRoot(String root) {
        String configured = properties.getAllowedRoot();
        if (configured == null || configured.isBlank()) return;
        try {
            Path allowed = Path.of(configured).toRealPath();
            if (!Path.of(root).startsWith(allowed)) {
                throw new BadRequestException("PROJECT_OUTSIDE_ALLOWED_ROOT", "Project root must be inside the configured allowed project root");
            }
        } catch (BadRequestException e) { throw e; }
        catch (Exception e) { throw new BadRequestException("ALLOWED_ROOT_INVALID", "Configured allowed project root cannot be resolved safely"); }
    }
    private String normalizeDescription(String description) {
        String normalized = description == null ? "" : description.trim();
        if (normalized.length() > 500) {
            throw new BadRequestException("PROJECT_DESCRIPTION_TOO_LONG", "Project description must not exceed 500 characters");
        }
        return normalized;
    }
}
