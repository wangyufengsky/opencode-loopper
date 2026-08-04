package io.opencode.loopper.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opencode.loopper.service.OpenCodeModelCatalogService;
import io.opencode.loopper.service.SettingsService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SettingsControllerTest {
    private final SettingsService settings = mock(SettingsService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SettingsController(settings))
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void exposesDynamicModelOptions() throws Exception {
        when(settings.models("opencode")).thenReturn(List.of(
                new OpenCodeModelCatalogService.AvailableModel("opencode/model-a", "opencode", "model-a", "opencode / model-a")));

        mvc.perform(get("/api/settings/models").queryParam("cliPath", "opencode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("opencode/model-a"))
                .andExpect(jsonPath("$[0].model").value("model-a"));
    }
}
