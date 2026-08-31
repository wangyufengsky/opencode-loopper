package io.opencode.loopper.api;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.opencode.loopper.runtime.InternalMcpContractCatalog;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.service.MachineCandidateSubmission;
import java.util.Map;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Owns a second, private MCP server without contributing another ToolCallbackProvider bean. */
@Configuration(proxyBeanMethods = false)
public class InternalMcpServerConfiguration {
    @Bean(destroyMethod = "close")
    InternalMcpServerRuntime internalMcpServerRuntime(
            MachineCandidateSubmission submissions, ObjectMapper json,
            @Value("${spring.ai.mcp.server.version:unknown}") String version) {
        WebMvcStreamableServerTransportProvider transport = WebMvcStreamableServerTransportProvider.builder()
                .mcpEndpoint(InternalMcpContractCatalog.ENDPOINT_PATH)
                .disallowDelete(true)
                .build();
        McpSchema.Tool tool = McpSchema.Tool.builder(
                        InternalMcpContractCatalog.TOOL_NAME, InternalMcpContractCatalog.inputSchema())
                .title("Submit bounded machine candidate")
                .description("Submit one candidate for deterministic validation; use the returned problems to retry")
                .annotations(McpSchema.ToolAnnotations.builder()
                        .readOnlyHint(false).destructiveHint(false).idempotentHint(true).openWorldHint(false).build())
                .build();
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("opencode-loopper-internal", version)
                .instructions("Expose only the server-owned bounded candidate submission contract")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .validateToolInputs(true)
                .immediateExecution(true)
                .toolCall(tool, (exchange, request) -> submit(submissions, json, request.arguments()))
                .build();
        return new InternalMcpServerRuntime(transport, server);
    }

    @Bean("internalMcpStreamableRouterFunction")
    RouterFunction<ServerResponse> internalMcpStreamableRouterFunction(InternalMcpServerRuntime runtime) {
        return runtime.routerFunction();
    }

    private static McpSchema.CallToolResult submit(MachineCandidateSubmission submissions, ObjectMapper json,
                                                    Map<String, Object> arguments) {
        try {
            String runId = requiredString(arguments, "runId");
            String idempotencyKey = requiredString(arguments, "idempotencyKey");
            Object candidate = arguments.get("candidate");
            if (!(candidate instanceof Map<?, ?>)) throw new IllegalArgumentException("candidate must be a JSON object");
            Object revisionValue = arguments.get("expectedSubmissionRevision");
            if (!(revisionValue instanceof Number revision)) {
                throw new IllegalArgumentException("expectedSubmissionRevision must be an integer");
            }
            MachineCandidateSubmission.SubmissionResult result = submissions.submit(
                    new MachineCandidateSubmission.SubmitCommand(runId, idempotencyKey,
                            json.writeValueAsString(candidate), revision.longValue(),
                            MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP));
            Map<String, Object> structured = json.readValue(result.responseJson(), new TypeReference<>() { });
            return McpSchema.CallToolResult.builder()
                    .addTextContent(result.responseJson())
                    .structuredContent(structured)
                    .isError(false)
                    .build();
        } catch (BadRequestException failure) {
            return safeError(json, failure.code(), failure.getMessage());
        } catch (ConflictException failure) {
            return safeError(json, failure.code(), failure.getMessage());
        } catch (IllegalArgumentException failure) {
            return safeError(json, "CANDIDATE_REQUEST_INVALID", failure.getMessage());
        } catch (RuntimeException failure) {
            return safeError(json, "INTERNAL_CANDIDATE_SUBMISSION_FAILED",
                    "Internal candidate submission failed");
        }
    }

    private static McpSchema.CallToolResult safeError(ObjectMapper json, String code, String detail) {
        String safeCode = code != null && code.matches("[A-Z0-9_]{1,64}")
                ? code : "INTERNAL_CANDIDATE_SUBMISSION_FAILED";
        String response = json.writeValueAsString(Map.of(
                "errorCode", safeCode,
                "detail", bounded(detail)));
        return McpSchema.CallToolResult.builder()
                .addTextContent(response)
                .structuredContent(Map.of("errorCode", safeCode, "detail", bounded(detail)))
                .isError(true)
                .build();
    }

    private static String requiredString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return text;
    }

    private static String bounded(String detail) {
        if (detail == null || detail.isBlank()) return "Internal candidate submission failed";
        return detail.length() <= 1_024 ? detail : detail.substring(0, 1_024);
    }

    static final class InternalMcpServerRuntime implements AutoCloseable {
        private final WebMvcStreamableServerTransportProvider transport;
        private final McpSyncServer server;

        InternalMcpServerRuntime(WebMvcStreamableServerTransportProvider transport, McpSyncServer server) {
            this.transport = transport;
            this.server = server;
        }

        RouterFunction<ServerResponse> routerFunction() {
            return transport.getRouterFunction();
        }

        @Override
        public void close() {
            server.closeGracefully();
        }
    }
}
