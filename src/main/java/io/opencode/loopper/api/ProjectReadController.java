package io.opencode.loopper.api;

import io.opencode.loopper.service.ProjectReadService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectReadController {
    private final ProjectReadService reads;

    public ProjectReadController(ProjectReadService reads) {
        this.reads = reads;
    }

    @GetMapping("/summaries")
    public List<ProjectReadService.ProjectSummary> summaries(
            @RequestParam(defaultValue = "false") boolean refresh) {
        return reads.summaries(refresh);
    }
}
