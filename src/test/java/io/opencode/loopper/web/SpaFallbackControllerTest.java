package io.opencode.loopper.web;

import io.opencode.loopper.LoopperApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.scheduling.enabled=false",
        "loopper.startup-recovery.enabled=false",
        "spring.datasource.url=jdbc:sqlite:file:spa-fallback-test?mode=memory&cache=shared"
})
@AutoConfigureMockMvc
class SpaFallbackControllerTest {
    @Autowired private MockMvc mvc;

    @Test
    void servesPackagedEntryPointForVueHistoryRoutesWithoutShadowingAssets() throws Exception {
        MvcResult entry = mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn();
        String entryHtml = entry.getResponse().getContentAsString();
        assertThat(entryHtml).contains("<div id=\"app\"></div>");

        for (String route : new String[]{
                "/", "/tasks/task-019fc6ad", "/tasks/task-019fc6ad/recovery", "/tasks/task-019fc6ad/design",
                "/inbox", "/insights", "/automations", "/unknown/deep/path"
        }) {
            mvc.perform(get(route))
                    .andExpect(status().isOk())
                    .andExpect(forwardedUrl("/index.html"));
        }

        String assetPath = entryHtml.replaceFirst("(?s).*src=\"(/assets/[^\"]+)\".*", "$1");
        assertThat(assetPath).startsWith("/assets/");
        mvc.perform(get(assetPath))
                .andExpect(status().isOk());
    }

    @Test
    void doesNotTurnMissingApiResourcesIntoHtml() throws Exception {
        for (String path : new String[]{
                "/api/tasks/missing", "/actuator/missing", "/assets/missing.js", "/missing/app.js"
        }) {
            mvc.perform(get(path))
                    .andExpect(status().isNotFound());
        }
    }
}
