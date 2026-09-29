package io.opencode.loopper.api;

import io.opencode.loopper.service.workflow.WorkflowNodePresets;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflows/node-presets")
public final class WorkflowPresetController {
    private final WorkflowNodePresets presets;
    public WorkflowPresetController(WorkflowNodePresets presets){this.presets=presets;}
    @GetMapping public CursorPage<WorkflowNodePresets.Summary> list(@RequestParam(required=false) String query,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit){return presets.list(query,cursor,limit);}
    @GetMapping("/{id}/versions/{version}") public WorkflowNodePresets.Detail get(@PathVariable String id,@PathVariable int version){return presets.get(id,version);}
}
