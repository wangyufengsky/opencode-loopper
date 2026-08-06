package io.opencode.loopper.api;

import io.opencode.loopper.domain.RecoveryMode;
import io.opencode.loopper.service.RecoveryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Recovery is always a child task; this controller intentionally exposes no in-place revert endpoint. */
@RestController
@RequestMapping("/api/tasks")
public class RecoveryController {
    private final RecoveryService recovery;

    public RecoveryController(RecoveryService recovery) { this.recovery = recovery; }

    @PostMapping("/{id}/recoveries")
    public FeatureContracts.RecoveryDto create(@PathVariable String id,
                                               @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi,
                                               @RequestBody(required = false) FeatureContracts.RecoveryRequest request) {
        requireLocalUi(localUi);
        RecoveryMode mode = request == null ? RecoveryMode.FROM_FAILED_STAGE : request.mode();
        return recovery.create(id, mode);
    }

    @GetMapping("/{id}/recoveries")
    public List<FeatureContracts.RecoveryDto> list(@PathVariable String id) {
        return recovery.list(id);
    }

    private void requireLocalUi(String localUi) {
        if (!"1".equals(localUi)) {
            throw new io.opencode.loopper.service.BadRequestException("LOCAL_UI_HEADER_REQUIRED",
                    "This operation is available only to the local Loopper UI");
        }
    }
}
