package io.opencode.loopper.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards the finite set of Vue history routes to the packaged SPA entrypoint.
 * API, actuator and static-asset URLs deliberately have no catch-all mapping, so
 * their normal HTTP errors remain observable to operators and clients.
 */
@Controller
public class SpaFallbackController {
    @GetMapping({"/", "/projects", "/designer", "/tasks", "/tasks/{taskId}", "/runtime", "/settings"})
    public String index() {
        return "forward:/index.html";
    }
}
