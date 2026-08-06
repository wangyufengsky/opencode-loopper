package io.opencode.loopper.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opencode.loopper.service.AutomationService;
import io.opencode.loopper.service.LoopSpecTemplateService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AutomationControllerTest {
    private final LoopSpecTemplateService templates = mock(LoopSpecTemplateService.class);
    private final AutomationService automation = mock(AutomationService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AutomationController(templates, automation))
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void localMutationsRequireTheLocalUiHeader() throws Exception {
        when(templates.create("T", "D")).thenReturn(new LoopSpecTemplateService.TemplateView(
                "template", "T", "D", "ACTIVE", "created", "updated", 0, List.of()));

        mvc.perform(post("/api/automations/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"T\",\"description\":\"D\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/automations/templates")
                        .header("X-Loopper-Local-UI", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"T\",\"description\":\"D\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("template"));
    }

    @Test
    void loopbackWebhookEndpointDoesNotRequireTheBrowserMutationHeader() throws Exception {
        var run = new AutomationService.RunView("run", "rule", "WEBHOOK", "REVIEW_REQUIRED", "draft", null,
                Map.of("deliveryId", "delivery"), "detected", null, null);
        when(automation.webhook(eq("rule"), eq("token"), any(), eq("{}"), eq("delivery"))).thenReturn(run);

        mvc.perform(post("/api/automations/webhooks/rule/token")
                        .with(request -> { request.setRemoteAddr("127.0.0.1"); return request; })
                        .header("X-Loopper-Delivery-Id", "delivery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REVIEW_REQUIRED"));

        verify(automation).webhook("rule", "token", "127.0.0.1", "{}", "delivery");
    }
}
