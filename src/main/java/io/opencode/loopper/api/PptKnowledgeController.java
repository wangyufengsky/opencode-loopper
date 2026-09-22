package io.opencode.loopper.api;

import io.opencode.loopper.service.ppt.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ppt")
public class PptKnowledgeController {
    private final PptKnowledge knowledge;
    private final PptKnowledgeEvidence evidence;
    public PptKnowledgeController(PptKnowledge knowledge,PptKnowledgeEvidence evidence) { this.knowledge=knowledge;this.evidence=evidence; }
    @GetMapping("/projects") public Object projects(@RequestParam(defaultValue="")String query,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit) {
        return knowledge.projects(query,cursor,limit);
    }
    @GetMapping("/documents/{id}/knowledge") public Object sources(@PathVariable String id) { return knowledge.view(id); }
    @GetMapping("/documents/{id}/knowledge/evidence/{evidenceId}") public Object evidence(@PathVariable String id,@PathVariable String evidenceId) { return evidence.read(id,evidenceId); }
}
