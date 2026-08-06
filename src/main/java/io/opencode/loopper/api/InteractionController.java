package io.opencode.loopper.api;

import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.InteractionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/interactions")
public class InteractionController {
    private final InteractionService interactions;

    public InteractionController(InteractionService interactions) { this.interactions = interactions; }

    @GetMapping
    public List<FeatureContracts.InteractionDto> list() { return interactions.listOpen(); }

    @PostMapping("/{id}/resolve")
    public FeatureContracts.InteractionDto resolve(
            @PathVariable String id,
            @RequestHeader("X-Loopper-Local-UI") String localUi,
            @Valid @RequestBody FeatureContracts.ResolveInteractionRequest request) {
        if (!"1".equals(localUi)) {
            throw new BadRequestException("LOCAL_UI_HEADER_REQUIRED", "Interaction actions require the local Loopper UI");
        }
        return interactions.resolve(id, request);
    }
}
