package io.opencode.loopper.api;

import io.modelcontextprotocol.spec.McpSchema;
import io.opencode.loopper.runtime.PptAgentProfile;
import io.opencode.loopper.service.assist.AssistFailure;
import io.opencode.loopper.service.ppt.agent.PptAgentTools;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PptKnowledgeMcpContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PptAgentTools service = mock(PptAgentTools.class);

    @Test void expiredRoundIdentityRequestsCorrectionButOtherScopeFailuresStillStop() {
        var spec=PptMcpTools.specifications(service,json).stream().filter(t->t.tool().name().equals("ppt_get_context")).findFirst().orElseThrow();
        var args=Map.<String,Object>of("scope","expired","documentId","document","runId","run","args",Map.of());
        when(service.call("ppt_get_context",args)).thenThrow(new io.opencode.loopper.domain.SessionFailure("PPT_SCOPE_EXPIRED","请读取本轮最新身份"));
        var result=json.valueToTree(spec.callHandler().apply(null,new McpSchema.CallToolRequest("ppt_get_context",args)).structuredContent());
        assertThat(result.path("action").asText()).isEqualTo("READ_CURRENT_IDENTITY_AND_RETRY");
        assertThat(result.path("repairHint").asText()).contains("rejected without executing","current user message");
        doThrow(new io.opencode.loopper.domain.SessionFailure("PPT_SCOPE_DENIED","授权不属于当前作品")).when(service).call("ppt_get_context",args);
        result=json.valueToTree(spec.callHandler().apply(null,new McpSchema.CallToolRequest("ppt_get_context",args)).structuredContent());
        assertThat(result.path("action").asText()).isEqualTo("STOP_AND_WAIT_FOR_RECOVERY");
    }

    @Test void databaseTimeoutPreservesStopAndInspectInsteadOfInvitingAnotherQuery() {
        var specification = PptMcpTools.specifications(service, json).stream()
                .filter(tool -> tool.tool().name().equals("ppt_query_knowledge_database")).findFirst().orElseThrow();
        var arguments = Map.<String,Object>of("scope", "scope", "documentId", "document", "runId", "run",
                "args", Map.of("connectionId", "database", "sql", "SELECT total FROM app.totals"));
        when(service.call("ppt_query_knowledge_database", arguments)).thenThrow(new AssistFailure(
                "DATABASE_QUERY_TIMEOUT", "查询超时，已请求取消；结果未知，请勿自动重复执行", "STOP_AND_INSPECT"));
        var response = specification.callHandler().apply(null, new McpSchema.CallToolRequest("ppt_query_knowledge_database", arguments));
        JsonNode body = json.valueToTree(response.structuredContent());
        assertThat(response.isError()).isTrue();
        assertThat(body.path("errorCode").asText()).isEqualTo("DATABASE_QUERY_TIMEOUT");
        assertThat(body.path("detail").asText()).contains("结果未知", "请勿自动重复执行");
        assertThat(body.path("action").asText()).isEqualTo("STOP_AND_INSPECT");
        assertThat(body.path("repairHint").asText()).contains("do not repeat").doesNotContain("correct the same tool call");
        verify(service, times(1)).call("ppt_query_knowledge_database", arguments);
    }

    @Test void knowledgeToolsRequireTheSamePptOwnerEnvelopeAndDoNotAcceptCallerRunIdentityInArguments() {
        var specifications = PptMcpTools.specifications(service, json);
        for (String name : PptAgentProfile.KNOWLEDGE_TOOLS) {
            var tool = specifications.stream().filter(value -> value.tool().name().equals(name)).findFirst().orElseThrow().tool();
            JsonNode schema = json.valueToTree(tool.inputSchema());
            assertThat(schema.path("required").valueStream().map(JsonNode::asText).toList())
                    .containsExactlyInAnyOrder("scope", "runId", "documentId", "args");
            assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
            JsonNode properties = schema.path("properties").path("args").path("properties");
            assertThat(properties.has("agentRunId") || properties.has("agentScope") || properties.has("scope")).isFalse();
            assertThat(tool.annotations().readOnlyHint()).isTrue();
        }
    }

    @Test void projectReadSchemasDescribeQueryColumnsGitOperationsAndSearchFollowups() {
        var specifications = PptMcpTools.specifications(service, json);
        var query = specifications.stream().filter(value -> value.tool().name().equals("ppt_query_knowledge_database")).findFirst().orElseThrow().tool();
        JsonNode args = json.valueToTree(query.inputSchema()).path("properties").path("args");
        assertThat(args.path("required").valueStream().map(JsonNode::asText).toList()).contains("connectionId", "sql");
        assertThat(args.path("properties").path("connectionId").path("type").asText()).isEqualTo("string");
        assertThat(query.description()).contains("without the database: prefix");
        var inspect = specifications.stream().filter(value -> value.tool().name().equals("ppt_inspect_knowledge_database")).findFirst().orElseThrow().tool();
        JsonNode inspectArgs = json.valueToTree(inspect.inputSchema()).path("properties").path("args");
        assertThat(inspectArgs.path("required").valueStream().map(JsonNode::asText).toList()).contains("connectionId", "schema");
        assertThat(inspectArgs.path("properties").path("kind").path("enum").valueStream().map(JsonNode::asText).toList())
                .containsExactlyElementsOf(List.of("tables", "columns", "indexes", "keys"));
        var git = specifications.stream().filter(value -> value.tool().name().equals("ppt_read_knowledge_git")).findFirst().orElseThrow().tool();
        JsonNode gitArgs = json.valueToTree(git.inputSchema()).path("properties").path("args");
        assertThat(gitArgs.path("properties").path("operation").path("enum").valueStream().map(JsonNode::asText).toList())
                .containsExactlyElementsOf(List.of("inspect", "commits", "authors", "commit", "file", "blame"));
        assertThat(gitArgs.path("properties").has("commit")).isTrue();
        var read = specifications.stream().filter(value -> value.tool().name().equals("ppt_read_knowledge_source")).findFirst().orElseThrow().tool();
        assertThat(read.description()).contains("section", "evidenceId OR sourceId");
        JsonNode readArgs = json.valueToTree(read.inputSchema()).path("properties").path("args").path("properties");
        assertThat(readArgs.path("evidenceId").path("type").asText()).isEqualTo("string");
        assertThat(readArgs.path("expectedSha").path("type").asText()).isEqualTo("string");
        assertThat(readArgs.path("section").path("minimum").asInt()).isEqualTo(-1);
    }
}
