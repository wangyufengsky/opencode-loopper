package io.opencode.loopper.api;

import io.opencode.loopper.runtime.OpenCodeToolInventory;
import io.opencode.loopper.runtime.McpToolCatalogReader;
import io.opencode.loopper.service.ProjectService;
import java.nio.file.Path;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/runtime/tools")
public class OpenCodeToolsController {
    private final OpenCodeToolInventory inventory;
    private final ProjectService projects;
    public OpenCodeToolsController(OpenCodeToolInventory inventory, ProjectService projects) {
        this.inventory = inventory; this.projects = projects;
    }
    @GetMapping public OpenCodeToolInventory.Inventory get(@RequestParam String projectId) {
        return inventory.inventory(directory(projectId));
    }
    @GetMapping("/catalog") public McpToolCatalogReader.Catalog tools(@RequestParam String projectId,
                                                                     @RequestParam String serverId) {
        return inventory.tools(directory(projectId), serverId);
    }
    private Path directory(String projectId) {
        return projectId == null || projectId.isBlank() ? Path.of(System.getProperty("user.dir")).toAbsolutePath()
                : Path.of(projects.get(projectId).rootPath());
    }
}
