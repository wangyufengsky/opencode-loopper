package io.opencode.loopper.api;

import io.modelcontextprotocol.spec.McpSchema;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.service.SnapshotReviewReads;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SnapshotRoleToolTest {
    @Test void directMcpRequestCannotReadOrRecordEvidenceAfterFrozenRoleDeniedTheTool() {
        var reads = mock(SnapshotReviewReads.class);
        doThrow(new ConflictException("ROLE_MCP_TOOL_DENIED", "当前冻结角色未获此工具权限"))
                .when(reads).requireTool("batch", "read_snapshot_review_code");
        var tool = SnapshotReviewMcpTools.specifications(reads, new ObjectMapper()).stream()
                .filter(value -> value.tool().name().equals("read_snapshot_review_code")).findFirst().orElseThrow();
        var response = tool.callHandler().apply(null, new McpSchema.CallToolRequest("read_snapshot_review_code",
                Map.of("runId", "batch", "version", "sha", "path", "src/App.java", "blob", "blob", "startLine", 1, "limit", 20)));

        assertThat(response.isError()).isTrue();
        assertThat(new ObjectMapper().valueToTree(response.structuredContent()).path("errorCode").asText())
                .isEqualTo("ROLE_MCP_TOOL_DENIED");
        verify(reads).requireTool("batch", "read_snapshot_review_code");
        verifyNoMoreInteractions(reads);
    }
}
