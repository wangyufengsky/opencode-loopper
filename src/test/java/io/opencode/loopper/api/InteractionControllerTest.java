package io.opencode.loopper.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.service.InteractionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InteractionControllerTest {
    private final InteractionService interactions = mock(InteractionService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new InteractionController(interactions))
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void rejectsResolutionWithoutExplicitOptimisticLockVersion() throws Exception {
        mvc.perform(post("/api/interactions/i-1/resolve")
                        .header("X-Loopper-Local-UI", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"ONCE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("FIELD_VALIDATION"));

        verify(interactions, never()).resolve(eq("i-1"), any());
    }

    @Test
    void mapsStaleVersionToConflict() throws Exception {
        when(interactions.resolve(eq("i-1"), any())).thenThrow(new ConflictException(
                "INTERACTION_VERSION_CONFLICT", "refresh"));

        mvc.perform(post("/api/interactions/i-1/resolve")
                        .header("X-Loopper-Local-UI", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"ONCE\",\"version\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INTERACTION_VERSION_CONFLICT"));
    }
}
