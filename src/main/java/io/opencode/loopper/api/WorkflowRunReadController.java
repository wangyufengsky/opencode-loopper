package io.opencode.loopper.api;
import io.opencode.loopper.persistence.WorkflowRunReadMapper;
import io.opencode.loopper.service.workflow.*;
import io.opencode.loopper.workflow.WorkflowGraph;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/workflows/requirements/{id}")
public final class WorkflowRunReadController {
    private final WorkflowRunReads reads;
    private final WorkflowRunActivity activity;
    public WorkflowRunReadController(WorkflowRunReads reads,WorkflowRunActivity activity){this.reads=reads;this.activity=activity;}
    @GetMapping("/execution") public WorkflowRunReads.Snapshot snapshot(@PathVariable String id){return reads.snapshot(id);}
    @GetMapping("/nodes/{key}/attempts") public CursorPage<WorkflowRunReadMapper.Attempt> list(@PathVariable String id,@PathVariable String key,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit){return reads.page(id,key,cursor,limit);}
    @GetMapping("/nodes/{key}/attempts/{attempt}") public WorkflowRunReadMapper.Attempt get(@PathVariable String id,@PathVariable String key,@PathVariable String attempt){return reads.get(id,key,attempt);}
    @GetMapping("/nodes/{key}/attempts/{attempt}/definition") public WorkflowGraph.Node definition(@PathVariable String id,@PathVariable String key,@PathVariable String attempt){return reads.definition(id,key,attempt);}
    @GetMapping("/nodes/{key}/attempts/{attempt}/activity") public WorkflowRunActivity.Activity activity(@PathVariable String id,@PathVariable String key,@PathVariable String attempt){return activity.get(id,key,attempt);}
}
