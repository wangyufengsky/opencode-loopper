package io.opencode.loopper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import io.modelcontextprotocol.spec.McpSchema;
import io.opencode.loopper.service.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SubmissionContractMcpToolTest {
    @Test void returnsRegisteredSchemaAndSpecificNestedFieldWithoutSubmitting() {
        var reads = mock(SubmissionContractReadService.class);
        var submits = mock(TemplateCandidateSubmissionService.class);
        var json = new ObjectMapper();
        when(reads.describe("batch")).thenReturn(new SubmissionContractReadService.Contract(
                "submit_template_analysis", "REVIEW", 7, Map.of()));
        var target = TemplateAnalysisMcpTool.specification(submits, json);
        var tool = SubmissionContractMcpTool.specification(reads, List.of(target), json);
        var full = tool.callHandler().apply(null, new McpSchema.CallToolRequest("describe_submission_contract", Map.of("runId", "batch", "pointer", "")));
        assertThat(full.isError()).isFalse();
        var actual = json.valueToTree(full.structuredContent());
        assertThat(actual.path("inputSchema")).isEqualTo(json.valueToTree(target.tool().inputSchema()));
        assertThat(actual.path("contract").path("expectedSubmissionRevision").asLong()).isEqualTo(7);
        var nested = tool.callHandler().apply(null, new McpSchema.CallToolRequest("describe_submission_contract",
                Map.of("runId", "batch", "pointer", "/properties/candidate/anyOf/0/properties/reviews/items/properties/unitId")));
        assertThat(nested.isError()).isFalse();
        assertThat(json.valueToTree(nested.structuredContent()).path("inputSchema").path("type").asText()).isEqualTo("string");
        verifyNoInteractions(submits);
    }
    @Test void closedScopeAndUnknownFieldNeverReturnAContract() {
        var reads = mock(SubmissionContractReadService.class);
        when(reads.describe("old")).thenThrow(new ConflictException("CANDIDATE_CONTRACT_CLOSED", "候选已关闭"));
        var tool = SubmissionContractMcpTool.specification(reads, List.of(), new ObjectMapper());
        var result = tool.callHandler().apply(null, new McpSchema.CallToolRequest("describe_submission_contract", Map.of("runId", "old", "pointer", "")));
        assertThat(result.isError()).isTrue();
        assertThat(new ObjectMapper().valueToTree(result.structuredContent()).has("inputSchema")).isFalse();
    }
}
