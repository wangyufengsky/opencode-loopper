package io.opencode.loopper.api;

import io.opencode.loopper.service.UsageInsightsService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only, server-authoritative quality, duration, retry and provider-usage view. */
@RestController
@RequestMapping("/api/insights")
public class InsightsController {
    private final UsageInsightsService insights;
    public InsightsController(UsageInsightsService insights) { this.insights = insights; }
    @GetMapping public Map<String, Object> get() { return insights.insights(); }
}
