package io.opencode.loopper.api;

import io.opencode.loopper.service.UsageInsightsService;
import io.opencode.loopper.service.InsightReadService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only, server-authoritative quality, duration, retry and provider-usage view. */
@RestController
@RequestMapping("/api/insights")
public class InsightsController {
    private final UsageInsightsService insights;
    private final InsightReadService reads;
    public InsightsController(UsageInsightsService insights, InsightReadService reads) {
        this.insights = insights; this.reads = reads;
    }
    @GetMapping public Map<String, Object> get() { return insights.insights(); }
    @GetMapping("/page") public InsightReadService.InsightPage page(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String cursor,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer limit) {
        return reads.page(cursor, limit);
    }
}
