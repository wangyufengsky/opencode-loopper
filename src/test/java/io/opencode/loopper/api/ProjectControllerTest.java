package io.opencode.loopper.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opencode.loopper.service.DirectoryPickerService;
import io.opencode.loopper.service.ProjectConventionService;
import io.opencode.loopper.service.ProjectConventionActivityService;
import io.opencode.loopper.service.ModelTokenUsageProjectionService;
import io.opencode.loopper.service.ProjectService;
import io.opencode.loopper.service.ProjectStackProfileService;
import io.opencode.loopper.service.ProjectStackSnapshot;
import io.opencode.loopper.persistence.ProjectConventionDraftRow;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.runtime.GitWorktreeManager.RepositoryInspection;
import io.opencode.loopper.service.ProjectConventionService.CurrentConvention;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProjectControllerTest {
    private final ProjectService projects = mock(ProjectService.class);
    private final DirectoryPickerService directoryPicker = mock(DirectoryPickerService.class);
    private final ProjectConventionService conventions = mock(ProjectConventionService.class);
    private final ProjectStackProfileService stackProfiles = mock(ProjectStackProfileService.class);
    private final ProjectConventionActivityService conventionActivity = mock(ProjectConventionActivityService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProjectController(
                    projects, directoryPicker, conventions, stackProfiles, conventionActivity))
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void returnsPersistedDescriptionAndActualRepositoryMode() throws Exception {
        ProjectRow project = new ProjectRow("project-1", "Example", "/tmp/example", "Useful context",
                "2026-08-05T00:00:00Z", "2026-08-05T00:00:01Z", 1, 0);
        when(projects.list()).thenReturn(List.of(project));
        when(projects.inspect(project)).thenReturn(new RepositoryInspection(true, true, "main"));
        when(projects.taskCount("project-1")).thenReturn(2);
        when(projects.openDesignerSessionCount("project-1")).thenReturn(1);
        when(stackProfiles.current("project-1")).thenReturn(new ProjectStackSnapshot(null, "project-1",
                io.opencode.loopper.domain.ProjectStackProfileState.READY, "sha",
                List.of("java"), List.of("java"), List.of(), 4, null, null, "now",
                List.of(new ProjectStackSnapshot.Component("root", ".", List.of("java"), List.of("java"),
                        List.of("maven"), List.of("junit"), List.of("pom.xml"), List.of()))));

        mvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].description").value("Useful context"))
                .andExpect(jsonPath("$[0].status").value("READY"))
                .andExpect(jsonPath("$[0].executionMode").value("WORKTREE"))
                .andExpect(jsonPath("$[0].branch").value("main"))
                .andExpect(jsonPath("$[0].taskCount").value(2))
                .andExpect(jsonPath("$[0].openDesignerSessionCount").value(1))
                .andExpect(jsonPath("$[0].stackProfileState").value("READY"))
                .andExpect(jsonPath("$[0].stackTechnologyFamilies[0]").value("java"))
                .andExpect(jsonPath("$[0].stackComponentCount").value(1));
    }

    @Test
    void returnsThePersistedModuleLevelStackProfile() throws Exception {
        when(stackProfiles.current("project-1")).thenReturn(new ProjectStackSnapshot("profile-1", "project-1",
                io.opencode.loopper.domain.ProjectStackProfileState.READY, "sha", List.of("node"), List.of("node"),
                List.of(), 7, null, null, "now", List.of(new ProjectStackSnapshot.Component(
                "frontend", "frontend", List.of("node"), List.of("node"), List.of("npm"), List.of("vitest"),
                List.of("frontend/package.json"), List.of()))));

        mvc.perform(get("/api/projects/project-1/stack-profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("profile-1"))
                .andExpect(jsonPath("$.manifestFingerprint").value("sha"))
                .andExpect(jsonPath("$.components[0].relativeRoot").value("frontend"))
                .andExpect(jsonPath("$.components[0].testFrameworks[0]").value("vitest"));
    }

    @Test
    void returnsTheSelectedAbsoluteDirectoryToTheLocalUi() throws Exception {
        when(directoryPicker.pickDirectory()).thenReturn(Optional.of("/tmp/example-project"));

        mvc.perform(post("/api/projects/pick-directory").header("X-Loopper-Local-UI", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected").value(true))
                .andExpect(jsonPath("$.path").value("/tmp/example-project"))
                .andExpect(jsonPath("$.name").value("example-project"));
    }

    @Test
    void representsCancellationWithoutAnErrorOrStalePath() throws Exception {
        when(directoryPicker.pickDirectory()).thenReturn(Optional.empty());

        mvc.perform(post("/api/projects/pick-directory").header("X-Loopper-Local-UI", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected").value(false))
                .andExpect(jsonPath("$.path").doesNotExist());
    }

    @Test
    void rejectsCrossOriginStyleRequestsWithoutTheLocalUiHeader() throws Exception {
        mvc.perform(post("/api/projects/pick-directory"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void startsReadOnlyConventionGenerationOnlyFromTheLocalUi() throws Exception {
        when(conventions.generate("project-1")).thenReturn(convention("draft-1", "project-1", "RUNNING", 0, null));

        mvc.perform(post("/api/projects/project-1/agents-md").header("X-Loopper-Local-UI", "1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("draft-1"))
                .andExpect(jsonPath("$.operation").value("CREATE"))
                .andExpect(jsonPath("$.readOnlyGeneration").value(true));
        mvc.perform(post("/api/projects/project-1/agents-md"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readsTheCurrentConventionWithoutStartingAi() throws Exception {
        when(conventions.current("project-1"))
                .thenReturn(new CurrentConvention("project-1", true, true, "# Current rules\n"));

        mvc.perform(get("/api/projects/project-1/agents-md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.loopperManaged").value(true))
                .andExpect(jsonPath("$.content").value("# Current rules\n"));
    }

    @Test
    void exposesLiveConventionActivityAndCancelsOnlyFromTheLocalUi() throws Exception {
        when(conventionActivity.activity("project-1", "draft-1")).thenReturn(
                new ProjectConventionActivityService.View("PROJECT_CONVENTION", "RUNNING", true,
                        "2026-08-24T00:00:00Z",
                        List.of(new ProjectConventionActivityService.Part("part-1", "THINKING",
                                "思考", "检查模块", "RUNNING", "2026-08-24T00:00:00Z")),
                        null, new ModelTokenUsageProjectionService.UsageView(
                                21L, 0, "2026-08-24T00:00:00Z")));
        when(conventions.cancel("project-1", "draft-1"))
                .thenReturn(convention("draft-1", "project-1", "CANCELLED", 0, null));

        mvc.perform(get("/api/projects/project-1/agents-md/draft-1/activity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parts[0].type").value("THINKING"))
                .andExpect(jsonPath("$.usage.totalTokens").value(21));
        mvc.perform(delete("/api/projects/project-1/agents-md/draft-1")
                        .header("X-Loopper-Local-UI", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"));
        mvc.perform(delete("/api/projects/project-1/agents-md/draft-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancelsManagementOnlyFromTheLocalUi() throws Exception {
        mvc.perform(delete("/api/projects/project-1").header("X-Loopper-Local-UI", "1"))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/projects/project-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void appliesOnlyAnExplicitConventionDraftFromTheLocalUi() throws Exception {
        when(conventions.apply("project-1", "draft-1"))
                .thenReturn(convention("draft-1", "project-1", "APPLIED", 1, "# preview"));

        mvc.perform(put("/api/projects/project-1/agents-md/draft-1").header("X-Loopper-Local-UI", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("APPLIED"))
                .andExpect(jsonPath("$.operation").value("UPDATE"));
    }

    private static ProjectConventionDraftRow convention(String id, String projectId, String state,
                                                          int sourceExists, String content) {
        return new ProjectConventionDraftRow(id, projectId, state, "remote", "COMPLETED", sourceExists,
                "hash", "", content, null, null,
                "2026-08-05T00:00:00Z", "2026-08-05T00:00:01Z", 1);
    }
}
