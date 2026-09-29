package io.opencode.loopper.service.workflow;

import io.opencode.loopper.workflow.CanvasLayout;
import io.opencode.loopper.workflow.WorkflowGraph;

public final class WorkflowRequests {
    private WorkflowRequests() { }
    public record CreateTemplate(String requestKey, String title, String description, WorkflowGraph graph, CanvasLayout layout) { }
    public record ReviseTemplate(String requestKey, long expectedVersion, int expectedRevision, String title, String description, WorkflowGraph graph) { }
    public record CopyTemplate(String requestKey, int sourceRevision, String title) { }
    public record VersionCommand(String requestKey, long expectedVersion) { }
    public record Layout(String requestKey, int expectedRevision, long expectedLayoutVersion, CanvasLayout layout) { }
    public record CreateRequirement(String requestKey, String projectId, String title, String objective, String templateId, int templateRevision) { }
    public record RevisePlan(String requestKey, long expectedVersion, int expectedRevision, WorkflowGraph graph) { }
}
