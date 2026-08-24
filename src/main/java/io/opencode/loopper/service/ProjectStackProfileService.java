package io.opencode.loopper.service;

import io.opencode.loopper.domain.ProjectStackProfileState;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.ProjectStackComponentRow;
import io.opencode.loopper.persistence.ProjectStackProfileRow;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Persists immutable stack-analysis snapshots after bounded filesystem I/O completes. */
@Service
public final class ProjectStackProfileService {
    private final LoopperMapper mapper;
    private final ProjectStackAnalyzer analyzer;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public ProjectStackProfileService(LoopperMapper mapper, ProjectStackAnalyzer analyzer, ObjectMapper json,
                                      PlatformTransactionManager transactionManager) {
        this.mapper = mapper; this.analyzer = analyzer; this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void analyzeRegisteredProject(ProjectRegisteredEvent event) {
        try { forceRefresh(event.projectId()); }
        catch (RuntimeException ignoredAfterRegistration) { }
    }

    public ProjectStackSnapshot forceRefresh(String projectId) {
        return analyze(project(projectId), true);
    }

    public ProjectStackSnapshot ensureCurrent(String projectId) {
        return analyze(project(projectId), false);
    }

    public ProjectStackSnapshot current(String projectId) {
        project(projectId);
        return mapper.findCurrentProjectStackProfile(projectId).map(this::snapshot)
                .orElseGet(() -> unanalyzed(projectId));
    }

    public ProjectStackSnapshot get(String projectId, String profileId) {
        ProjectStackProfileRow row = mapper.findProjectStackProfile(profileId)
                .filter(profile -> projectId.equals(profile.projectId()))
                .orElseThrow(() -> new NotFoundException("Project stack profile not found: " + profileId));
        return snapshot(row);
    }

    public ProjectStackSnapshot get(String profileId) {
        return mapper.findProjectStackProfile(profileId).map(this::snapshot)
                .orElseThrow(() -> new NotFoundException("Project stack profile not found: " + profileId));
    }

    public String inspectFingerprint(String projectId) {
        ProjectRow project = project(projectId);
        return analyzer.analyze(Path.of(project.rootPath())).manifestFingerprint();
    }

    public List<String> technologies(String profileId, List<String> componentKeys) {
        ProjectStackSnapshot profile = get(profileId);
        validateComponentKeys(profile, componentKeys);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        profile.components().stream().filter(component -> componentKeys.contains(component.key()))
                .forEach(component -> result.addAll(component.technologies()));
        return List.copyOf(result);
    }

    public void validateComponentKeys(ProjectStackSnapshot profile, List<String> componentKeys) {
        List<String> requested = componentKeys == null ? List.of() : componentKeys.stream().distinct().toList();
        java.util.Set<String> available = profile.components().stream()
                .map(ProjectStackSnapshot.Component::key).collect(java.util.stream.Collectors.toSet());
        if (!available.containsAll(requested)) {
            throw new BadRequestException("PROJECT_COMPONENT_INVALID", "选择的组件不属于当前项目技术栈画像");
        }
    }

    private ProjectStackSnapshot analyze(ProjectRow project, boolean force) {
        ProjectStackAnalyzer.Analysis analysis = analyzer.analyze(Path.of(project.rootPath()));
        ProjectStackProfileRow current = mapper.findCurrentProjectStackProfile(project.id()).orElse(null);
        if (analysis.state() != ProjectStackProfileState.FAILED && current != null
                && current.manifestFingerprint().equals(analysis.manifestFingerprint())
                && current.analysisState().equals(analysis.state().name())) {
            return snapshot(current);
        }
        if (!force && analysis.state() == ProjectStackProfileState.FAILED && current != null
                && ProjectStackProfileState.FAILED.name().equals(current.analysisState())) {
            return snapshot(current);
        }
        return persist(project, analysis);
    }

    private ProjectStackSnapshot persist(ProjectRow project, ProjectStackAnalyzer.Analysis analysis) {
        return transactions.execute(status -> {
            String id = UUID.randomUUID().toString();
            String now = Instant.now().toString();
            ProjectStackProfileRow row = new ProjectStackProfileRow(id, project.id(), analysis.state().name(),
                    analysis.manifestFingerprint(), write(analysis.technologyFamilies()), write(analysis.technologies()),
                    write(analysis.evidence()), analysis.filesScanned(), analysis.components().size(),
                    analysis.errorCode(), analysis.errorDetail(), now, now);
            if (mapper.insertProjectStackProfile(row) != 1) {
                throw new ConflictException("PROJECT_STACK_PROFILE_CREATE_CONFLICT", "项目技术栈画像未能持久化");
            }
            for (ProjectStackAnalyzer.ComponentResult component : analysis.components()) {
                ProjectStackComponentRow componentRow = new ProjectStackComponentRow(id, component.key(),
                        component.relativeRoot(), write(component.technologyFamilies()), write(component.technologies()),
                        write(component.buildTools()), write(component.testFrameworks()),
                        write(component.manifestSources()), write(component.evidence()));
                if (mapper.insertProjectStackComponent(componentRow) != 1) {
                    throw new ConflictException("PROJECT_STACK_COMPONENT_CREATE_CONFLICT", "项目组件画像未能持久化");
                }
            }
            return snapshot(row);
        });
    }

    private ProjectStackSnapshot snapshot(ProjectStackProfileRow row) {
        List<ProjectStackSnapshot.Component> components = mapper.listProjectStackComponents(row.id()).stream()
                .map(component -> new ProjectStackSnapshot.Component(component.componentKey(), component.relativeRoot(),
                        read(component.technologyFamiliesJson()), read(component.technologiesJson()),
                        read(component.buildToolsJson()), read(component.testFrameworksJson()),
                        read(component.manifestSourcesJson()), read(component.evidenceJson()))).toList();
        return new ProjectStackSnapshot(row.id(), row.projectId(), ProjectStackProfileState.valueOf(row.analysisState()),
                row.manifestFingerprint(), read(row.technologyFamiliesJson()), read(row.technologiesJson()),
                read(row.evidenceJson()), row.filesScanned(), row.errorCode(), row.errorDetail(), row.analyzedAt(), components);
    }

    private ProjectRow project(String id) {
        return mapper.findProject(id).orElseThrow(() -> new NotFoundException("Project not found: " + id));
    }

    private ProjectStackSnapshot unanalyzed(String projectId) {
        return new ProjectStackSnapshot(null, projectId, ProjectStackProfileState.UNANALYZED, null,
                List.of(), List.of(), List.of(), 0, null, null, null, List.of());
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Unable to persist project stack profile", failure); }
    }

    private List<String> read(String value) {
        if (value == null) return List.of();
        try { return json.readValue(value, new TypeReference<>() { }); }
        catch (Exception ignored) { return List.of(); }
    }
}
