package io.opencode.loopper.api;

import io.opencode.loopper.service.DesignerStopService;
import io.opencode.loopper.service.DesignerActivityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local-UI-only destructive controls are separated from the regular Designer conversation API. */
@RestController
@RequestMapping("/api/designer-sessions")
public final class DesignerControlController {
    private final DesignerStopService stops;
    private final DesignerActivityService activities;

    public DesignerControlController(DesignerStopService stops, DesignerActivityService activities) {
        this.stops = stops;
        this.activities = activities;
    }

    @GetMapping("/{id}/activity")
    public DesignerActivityService.View activity(@PathVariable String id) {
        return activities.activity(id);
    }

    @PostMapping("/{id}/stop")
    public DesignerStopService.Result stop(@PathVariable String id,
                                           @RequestHeader("X-Loopper-Local-UI") String localUi) {
        if (!"1".equals(localUi)) {
            throw new io.opencode.loopper.service.BadRequestException(
                    "LOCAL_UI_HEADER_REQUIRED", "此操作仅允许本机 Loopper 界面调用");
        }
        return stops.stop(id);
    }
}
