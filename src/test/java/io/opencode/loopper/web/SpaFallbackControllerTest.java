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
        for (String route : new String[]{
                "/tasks/task-019fc6ad", "/tasks/task-019fc6ad/recovery", "/tasks/task-019fc6ad/design",
                "/inbox", "/insights", "/automations"
        }) {
            mvc.perform(get(route))
                    .andExpect(status().isOk())
                    .andExpect(forwardedUrl("/index.html"));
        }
    }

    @Test
    void doesNotTurnMissingApiResourcesIntoHtml() throws Exception {
        mvc.perform(get("/api/tasks/missing"))
                .andExpect(status().isNotFound());
    }
}
