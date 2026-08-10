package io.opencode.loopper.api;

import io.opencode.loopper.service.StateTransitionQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/state-transitions")
public final class StateTransitionController {
    private final StateTransitionQueryService transitions;

    public StateTransitionController(StateTransitionQueryService transitions) { this.transitions = transitions; }

    @GetMapping
    public StateTransitionQueryService.TransitionPage query(
            @RequestParam(required = false) String machineType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String scopeType,
            @RequestParam(required = false) String scopeId,
            @RequestParam(required = false) Long afterSequence,
            @RequestParam(required = false) Integer limit) {
        return transitions.query(machineType, entityId, scopeType, scopeId, afterSequence, limit);
    }
}
