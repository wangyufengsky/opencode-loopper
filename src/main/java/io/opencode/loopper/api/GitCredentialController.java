package io.opencode.loopper.api;

import io.opencode.loopper.service.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/git-credentials")
public class GitCredentialController {
    private final GitCredentialService credentials;
    private final GitCredentialProbe probe;
    public GitCredentialController(GitCredentialService credentials, GitCredentialProbe probe) {
        this.credentials = credentials; this.probe = probe;
    }
    @GetMapping public GitCredentialService.View get(@RequestParam(required = false) String projectId) { return credentials.get(projectId); }
    @PutMapping public GitCredentialService.View save(@RequestParam(required = false) String projectId,
            @RequestHeader(value = "X-Loopper-Local-UI", required = false) String ui, @RequestBody GitCredentialService.Request request) {
        local(ui); return credentials.save(projectId, request);
    }
    @PostMapping("/test") public GitCredentialProbe.Result test(@RequestParam(required = false) String projectId,
            @RequestHeader(value = "X-Loopper-Local-UI", required = false) String ui, @RequestBody GitCredentialService.Request request) {
        local(ui); return probe.test(projectId, request);
    }
    private static void local(String ui) {
        if (!"1".equals(ui)) throw new BadRequestException("LOCAL_UI_HEADER_REQUIRED", "请从 Loopper 本地页面管理 Git 账号");
    }
}
