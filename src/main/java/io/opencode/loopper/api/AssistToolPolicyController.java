package io.opencode.loopper.api;

import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.ProjectService;
import io.opencode.loopper.service.assist.*;
import java.nio.file.Path;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/runtime/tool-policies")
public class AssistToolPolicyController {
    private final AssistToolPolicyService policy;private final OpenCodeToolInventory inventory;private final ProjectService projects;
    public AssistToolPolicyController(AssistToolPolicyService policy,OpenCodeToolInventory inventory,ProjectService projects){this.policy=policy;this.inventory=inventory;this.projects=projects;}
    public record Catalog(List<AssistToolPolicyService.View> tools,boolean complete,String detail) { }
    @GetMapping public Catalog get(@RequestParam(defaultValue="")String projectId,@RequestParam String serverId) {
        Path directory=directory(projectId);
        if(AssistToolCatalog.SERVER.equals(serverId))return new Catalog(policy.catalog(projectId,serverId,AssistToolCatalog.tools().stream().map(AssistToolCatalog.Tool::name).toList(),true),true,"仅对新建会话生效；受管运行环境启动后可调用");
        var catalog=inventory.tools(directory,serverId);
        return new Catalog(policy.catalog(projectId,serverId,catalog.tools().stream().map(McpToolCatalogReader.Tool::name).toList(),catalog.complete()),catalog.complete(),catalog.detail());
    }
    public record Update(String projectId,String serverId,String toolName,int enabled,long version) { }
    @PutMapping public void update(@RequestHeader("X-Loopper-Local-UI")String ui,@RequestBody Update body) {
        DatabaseConnectionController.local(ui);String scope=body.projectId()==null?"":body.projectId();directory(scope);
        policy.update(scope,body.serverId(),body.toolName(),body.enabled(),body.version());
    }
    private Path directory(String project){return project.isBlank()?Path.of(System.getProperty("user.dir")).toAbsolutePath():Path.of(projects.get(project).rootPath());}
}
