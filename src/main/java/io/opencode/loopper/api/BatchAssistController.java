package io.opencode.loopper.api;

import io.opencode.loopper.service.TaskService;
import io.opencode.loopper.service.assist.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class BatchAssistController {
    private final BatchAssistConfigService configs;
    private final BatchEvidenceReadService evidence;
    private final TaskService tasks;
    public BatchAssistController(BatchAssistConfigService configs,BatchEvidenceReadService evidence,TaskService tasks) {
        this.configs=configs;this.evidence=evidence;this.tasks=tasks;
    }
    @GetMapping("/api/projects/{project}/assist-config")
    public BatchAssistConfigService.View get(@PathVariable String project) { return configs.get(project); }
    @PutMapping("/api/projects/{project}/assist-config")
    public BatchAssistConfigService.View save(@RequestHeader("X-Loopper-Local-UI")String ui,@PathVariable String project,@RequestBody BatchAssistConfigService.Request request) {
        DatabaseConnectionController.local(ui);return configs.save(project,request);
    }
    public record Version(long version) { }
    @PostMapping("/api/projects/{project}/assist-config/check")
    public BatchAssistConfigService.View check(@RequestHeader("X-Loopper-Local-UI")String ui,@PathVariable String project,@RequestBody Version request) {
        DatabaseConnectionController.local(ui);return configs.check(project,request.version());
    }
    @GetMapping("/api/projects/{project}/assist-config/discover")
    public Map<String,String> discover(@PathVariable String project) { return configs.discover(project); }
    @GetMapping("/api/tasks/{task}/execution-evidence")
    public Map<String,Object> directory(@PathVariable String task,@RequestParam(required=false)String attemptId,@RequestParam(required=false)String cursor) {
        tasks.get(task);return evidence.directory("TASK:"+task,"9999",attemptId,cursor);
    }
    @GetMapping("/api/tasks/{task}/execution-evidence/body")
    public Map<String,Object> read(@PathVariable String task,@RequestParam String reference,@RequestParam(defaultValue="0")int offset) {
        tasks.get(task);return evidence.read("TASK:"+task,"9999",reference,offset);
    }
    @GetMapping("/api/tasks/{task}/execution-evidence/failures")
    public Map<String,Object> failures(@PathVariable String task,@RequestParam(required=false)String attemptId,@RequestParam(required=false)String cursor) {
        tasks.get(task);return evidence.failures("TASK:"+task,"9999",attemptId,cursor);
    }
    @GetMapping("/api/tasks/{task}/execution-evidence/failure")
    public Map<String,Object> failure(@PathVariable String task,@RequestParam String id) {
        tasks.get(task);return evidence.failure("TASK:"+task,"9999",id);
    }
    @GetMapping("/api/tasks/{task}/execution-evidence/search")
    public Map<String,Object> search(@PathVariable String task,@RequestParam String query,@RequestParam(required=false)String attemptId,@RequestParam(required=false)String cursor) {
        tasks.get(task);return evidence.search("TASK:"+task,"9999",attemptId,query,cursor);
    }
}
