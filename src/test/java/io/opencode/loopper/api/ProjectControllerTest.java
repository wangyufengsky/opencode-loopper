package io.opencode.loopper.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opencode.loopper.service.DirectoryPickerService;
import io.opencode.loopper.service.ProjectService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProjectControllerTest {
    private final ProjectService projects = mock(ProjectService.class);
    private final DirectoryPickerService directoryPicker = mock(DirectoryPickerService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProjectController(projects, directoryPicker))
            .setControllerAdvice(new ApiExceptionHandler()).build();

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
}
