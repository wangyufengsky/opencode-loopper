package io.opencode.loopper.workflow;

import tools.jackson.databind.JsonNode;

/** User-visible saved evidence, separate from model access to active sources. */
public final class WorkflowKnowledgeEvidence {
    private WorkflowKnowledgeEvidence() { }
    public record Entry(String id,String toolName,String createdAt) { }
    public record Body(String id,String toolName,String createdAt,JsonNode content) { }
}
