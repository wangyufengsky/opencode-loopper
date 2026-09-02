package io.opencode.loopper.api;

import io.opencode.loopper.service.StoryBindingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class StoryBindingController {
    private final StoryBindingService bindings;
    public StoryBindingController(StoryBindingService bindings) { this.bindings = bindings; }

    @GetMapping("/{projectId}/story-binding-capability")
    public StoryBindingService.Capability capability(@PathVariable String projectId) {
        return bindings.capability(projectId);
    }
}
