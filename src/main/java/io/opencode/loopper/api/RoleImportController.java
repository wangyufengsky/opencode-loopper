package io.opencode.loopper.api;

import io.opencode.loopper.service.roles.RoleArchive;
import io.opencode.loopper.service.roles.RolePublishingService;
import java.io.IOException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/role-imports")
public class RoleImportController {
    private final RoleArchive archive;
    private final RolePublishingService publishing;

    public RoleImportController(RoleArchive archive, RolePublishingService publishing) {
        this.archive = archive;
        this.publishing = publishing;
    }

    @PostMapping(value = "/validate", consumes = "multipart/form-data")
    public RolePublishingService.Validation validate(@RequestPart("file") MultipartFile file,
            @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi) throws IOException {
        KnowledgeController.requireUi(localUi);
        return publishing.validate(archive.parse(file.getBytes()));
    }

    @PostMapping(value = "/publish", consumes = "multipart/form-data")
    public RolePublishingService.Publication publish(@RequestPart("file") MultipartFile file,
            @RequestPart("request") RolePublishingService.PublishRequest request,
            @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi) throws IOException {
        KnowledgeController.requireUi(localUi);
        return publishing.publish(archive.parse(file.getBytes()), request);
    }
}
