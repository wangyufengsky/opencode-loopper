package io.opencode.loopper.service;

import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.config.LoopperProperties;
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
    public ProjectService(LoopperMapper mapper, LoopperProperties properties) { this.mapper = mapper; this.properties = properties; }
    @Transactional
    public ProjectRow create(String name, String rootPath) {
        if (name == null || name.isBlank()) throw new BadRequestException("PROJECT_NAME_REQUIRED", "Project name is required");
        String root = canonicalDirectory(rootPath);
        requireAllowedRoot(root);
        String now = Instant.now().toString();
        ProjectRow project = new ProjectRow(UUID.randomUUID().toString(), name.trim(), root, now, now, 0);
        mapper.insertProject(project);
        return project;
    }
    public List<ProjectRow> list() { return mapper.listProjects(); }
    public int taskCount(String projectId) { return mapper.countTasksForProject(projectId); }
    public ProjectRow get(String id) { return mapper.findProject(id).orElseThrow(() -> new NotFoundException("Project not found: " + id)); }
    @Transactional
    public ProjectRow rename(String id, String name) {
        ProjectRow old = get(id);
        if (name == null || name.isBlank()) throw new BadRequestException("PROJECT_NAME_REQUIRED", "Project name is required");
        ProjectRow changed = new ProjectRow(old.id(), name.trim(), old.rootPath(), old.createdAt(), Instant.now().toString(), old.version());
        if (mapper.updateProject(changed) != 1) throw new ConflictException("PROJECT_VERSION_CONFLICT", "Project was updated concurrently");
        return get(id);
    }
    @Transactional
    public void delete(String id) {
        if (mapper.deleteProject(id) != 1) throw new NotFoundException("Project not found: " + id);
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
}
