package io.opencode.loopper.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Forwards extensionless Vue history routes to the packaged SPA entrypoint.
 * API, actuator and static-asset URLs are rejected from the catch-all, so their
 * normal HTTP errors remain observable to operators and clients.
 */
@Controller
public class SpaFallbackController {
    @GetMapping({
            "/", "/projects", "/designer", "/tasks", "/tasks/{taskId}",
            "/tasks/{taskId}/recovery", "/tasks/{taskId}/design",
            "/inbox", "/insights", "/automations", "/runtime", "/settings"
    })
    public String index() {
        return "forward:/index.html";
    }

    @GetMapping({
            "/{first:(?!api$|actuator$|assets$)[^.]+}",
            "/{first:(?!api$|actuator$|assets$)[^.]+}/{*path}"
    })
    public String unknownHistoryRoute(@PathVariable(required = false) String path) {
        String normalized = path == null ? "" : path.replaceFirst("^/+", "");
        String lastSegment = normalized.contains("/")
                ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
        if (lastSegment.contains(".")) {
            throw new ResponseStatusException(NOT_FOUND);
        }
        return "forward:/index.html";
    }
}
