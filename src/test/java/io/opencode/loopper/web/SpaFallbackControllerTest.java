package io.opencode.loopper.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SpaFallbackControllerTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SpaFallbackController()).build();

    @Test
    void forwardsVueHistoryRoutesToThePackagedEntryPoint() throws Exception {
        mvc.perform(get("/tasks/task-019fc6ad"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void doesNotTurnMissingApiResourcesIntoHtml() throws Exception {
        mvc.perform(get("/api/tasks/missing"))
                .andExpect(status().isNotFound());
    }
}
