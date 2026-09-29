package io.opencode.loopper.workflow;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

/** Accepted output and frozen input are separate records; neither is a model session state. */
public record WorkflowDelivery(String summary, String outcome, Map<String, Value> outputs) {
    public WorkflowDelivery { outputs = outputs == null ? Map.of() : Map.copyOf(outputs); }
    public record Value(WorkflowGraph.DataKind kind, JsonNode content) { }
    public record Input(String name, WorkflowGraph.DataKind kind, String source, String sourceId,
                        String outputName, String attemptId, String sha256, JsonNode content,
                        @JsonInclude(JsonInclude.Include.NON_NULL) OutputReference reference) {
        public Input(String name, WorkflowGraph.DataKind kind, String source, String sourceId,
                     String outputName, String attemptId, String sha256, JsonNode content) {
            this(name, kind, source, sourceId, outputName, attemptId, sha256, content, null);
        }
    }
    /** The surrounding input pins the producer attempt, output name and whole-delivery hash. */
    public record OutputReference(int version, String contentSha256, int sizeBytes) { }
    public record Inputs(int version, String requirementId, int planRevision, String nodeId,
                         String objective, List<Input> values) {
        public Inputs { values = List.copyOf(values); }
    }
}
