package io.opencode.loopper.workflow;

import java.util.List;

/** Definition-only contract shared by the local UI boundary and template export use case. */
public final class WorkflowTemplateExport {
    private WorkflowTemplateExport() { }
    public enum Mode { CURRENT, INITIAL }
    public record PreviewRequest(int expectedRevision, Mode mode, WorkflowGraph graph, CanvasLayout layout) { }
    public record Preview(Mode mode, int sourceRevision, boolean initialAvailable, WorkflowGraph graph, CanvasLayout layout,
                          List<String> fixedPlanningNodes, String sha256, List<WorkflowGraphValidator.Diagnostic> diagnostics) { }
    public record Save(String requestKey, String title, String description, PreviewRequest selection, String previewSha256) { }
}
