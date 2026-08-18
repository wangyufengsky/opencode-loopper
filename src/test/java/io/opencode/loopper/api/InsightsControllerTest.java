package io.opencode.loopper.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opencode.loopper.service.UsageInsightsService;
import io.opencode.loopper.service.InsightReadService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InsightsControllerTest {
    @Test
    void exposesServerAuthoritativeUsageWithoutConvertingUnknownToZero() throws Exception {
        UsageInsightsService service = mock(UsageInsightsService.class);
        when(service.insights()).thenReturn(Map.of("tasks", java.util.List.of(), "usage", Map.of("unknownUsageCount", 2), "generatedAt", "2026-08-05T00:00:00Z"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new InsightsController(service, mock(InsightReadService.class))).build();
        mvc.perform(get("/api/insights"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.unknownUsageCount").value(2))
                .andExpect(jsonPath("$.usage.totalTokens").doesNotExist());
    }
}
