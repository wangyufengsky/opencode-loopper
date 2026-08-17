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
import io.opencode.loopper.service.ProjectService;
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
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProjectController(projects, directoryPicker, conventions))
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void returnsPersistedDescriptionAndActualRepositoryMode() throws Exception {
        ProjectRow project = new ProjectRow("project-1", "Example", "/tmp/example", "Useful context",
                "2026-08-05T00:00:00Z", "2026-08-05T00:00:01Z", 1, 0);
        when(projects.list()).thenReturn(List.of(project));
        when(projects.inspect(project)).thenReturn(new RepositoryInspection(true, true, "main"));
        when(projects.taskCount("project-1")).thenReturn(2);
        when(projects.openDesignerSessionCount("project-1")).thenReturn(1);

        mvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].description").value("Useful context"))
                .andExpect(jsonPath("$[0].status").value("READY"))
                .andExpect(jsonPath("$[0].executionMode").value("WORKTREE"))
                .andExpect(jsonPath("$[0].branch").value("main"))
                .andExpect(jsonPath("$[0].taskCount").value(2))
                .andExpect(jsonPath("$[0].openDesignerSessionCount").value(1));
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
