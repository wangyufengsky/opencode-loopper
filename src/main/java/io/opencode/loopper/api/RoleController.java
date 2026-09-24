package io.opencode.loopper.api;

import io.opencode.loopper.service.roles.RolePublishingService;
import io.opencode.loopper.service.roles.RoleReadService;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RoleController {
    private final RoleReadService roles;
    private final RolePublishingService publishing;

    public RoleController(RoleReadService roles, RolePublishingService publishing) {
        this.roles = roles;
        this.publishing = publishing;
    }

    @GetMapping("/roles")
    public RoleReadService.CatalogPage list(@RequestParam(required = false) String query,
                                            @RequestParam(required = false) String cursor,
                                            @RequestParam(required = false) Integer limit) {
        return roles.list(query, cursor, limit);
    }

    @GetMapping("/roles/{roleId}")
    public RoleReadService.RoleDetail detail(@PathVariable String roleId) { return roles.detail(roleId); }

    @GetMapping("/roles/{roleId}/revisions")
    public RoleReadService.RevisionPage history(@PathVariable String roleId,
                                                @RequestParam(required = false) String cursor,
                                                @RequestParam(required = false) Integer limit) {
        return roles.history(roleId, cursor, limit);
    }

    @GetMapping("/roles/{roleId}/revisions/{revisionId}")
    public RoleReadService.Revision revision(@PathVariable String roleId, @PathVariable String revisionId) {
        return roles.revision(roleId, revisionId);
    }

    @GetMapping("/roles/{roleId}/compare")
    public RoleReadService.Comparison compare(@PathVariable String roleId, @RequestParam String from,
                                              @RequestParam String to) {
        return roles.compare(roleId, from, to);
    }

    @GetMapping("/roles/{roleId}/preview")
    public RoleReadService.Preview preview(@PathVariable String roleId, @RequestParam String slot,
                                           @RequestParam(required = false) String projectId) {
        return roles.preview(roleId, slot, projectId);
    }

    public record PreviewRequest(String slot, String projectId) { }

    @PostMapping("/roles/{roleId}/preview")
    public RoleReadService.Preview previewPost(@PathVariable String roleId, @RequestBody PreviewRequest request) {
        return roles.preview(roleId, request.slot(), request.projectId());
    }

    @GetMapping("/roles/{roleId}/export")
    public ResponseEntity<byte[]> export(@PathVariable String roleId,
                                         @RequestParam(required = false) String revision) {
        byte[] body = roles.export(roleId, revision);
        String safeName = roleId.replaceAll("[^a-zA-Z0-9._-]", "_") + ".zip";
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + safeName + "\"")
                .contentType(MediaType.parseMediaType("application/zip")).body(body);
    }

    @GetMapping({"/role-bindings", "/role-slots"})
    public List<RolePublishingService.BindingView> bindings() { return publishing.bindings(); }
}
