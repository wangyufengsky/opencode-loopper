package io.opencode.loopper.api;

import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.StoryAccountingActivityService;
import io.opencode.loopper.service.StoryAccountingActivityService.CallView;
import io.opencode.loopper.service.StoryAccountingCoordinator;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/story-accounting")
public class StoryAccountingController {
    private final StoryAccountingActivityService activity;
    private final StoryAccountingCoordinator accounting;
    public StoryAccountingController(StoryAccountingActivityService activity, StoryAccountingCoordinator accounting) {
        this.activity = activity;
        this.accounting = accounting;
    }
    @GetMapping public List<CallView> list() { return activity.list(); }
    @GetMapping("/{id}") public CallView get(@PathVariable String id) { return activity.get(id); }
    @PostMapping("/{id}/cancel") public CallView cancel(@PathVariable String id,
            @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi) {
        requireLocalUi(localUi);
        accounting.cancel(id);
        return activity.get(id);
    }
    @PostMapping("/{id}/dismiss") public void dismiss(@PathVariable String id,
            @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi) {
        requireLocalUi(localUi);
        activity.dismiss(id);
    }
    @PostMapping("/{id}/retry") public CallView retry(@PathVariable String id,
            @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi) {
        requireLocalUi(localUi);
        return activity.snapshot(accounting.retry(id));
    }
    private void requireLocalUi(String value) {
        if (!"1".equals(value)) throw new BadRequestException("LOCAL_UI_HEADER_REQUIRED", "仅允许本地界面操作统计调用");
    }
}
