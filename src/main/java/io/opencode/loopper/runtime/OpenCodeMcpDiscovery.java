package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.SessionFailure;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Discovers bounded OpenCode MCP status and enforces candidate-session readiness. */
final class OpenCodeMcpDiscovery {
    private static final int MAX_SERVERS = 64;
    private static final int MAX_SERVER_NAME_LENGTH = 128;
    private final OpenCodeResponseParser responses;

    OpenCodeMcpDiscovery(OpenCodeResponseParser responses) {
        this.responses = responses;
    }

    Access discover(RestClient client, Path worktree, String internalMcpServer) {
        try {
            JsonNode body = client.get().uri(uri -> mcpUri(uri, worktree))
                    .retrieve().body(JsonNode.class);
            if (body == null || !body.isObject()) {
                throw new SessionFailure("OPENCODE_MCP_DISCOVERY_FAILED",
                        "OpenCode 未返回有效的 MCP Server 列表");
            }
            Map<String, String> servers = new LinkedHashMap<>();
            body.propertyStream().limit(MAX_SERVERS).forEach(entry -> {
                String name = entry.getKey();
                String status = entry.getValue().isTextual()
                        ? entry.getValue().asText(null) : entry.getValue().path("status").asText(null);
                if (name != null && !name.isBlank() && name.length() <= MAX_SERVER_NAME_LENGTH
                        && status != null && !status.isBlank()) {
                    servers.put(name, status);
                }
            });
            // User-configured discovery remains bounded, but the private server
            // is a generation identity and must never disappear behind that window.
            if (internalMcpServer != null && !internalMcpServer.isBlank()) {
                JsonNode internal = body.get(internalMcpServer);
                String status = internal == null ? null : internal.isTextual()
                        ? internal.asText(null) : internal.path("status").asText(null);
                if (status != null && !status.isBlank()) servers.put(internalMcpServer, status);
            }
            return new Access(servers);
        } catch (SessionFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new SessionFailure("OPENCODE_MCP_DISCOVERY_FAILED",
                    "无法读取当前项目的 MCP Server：" + responses.bounded(failure.getMessage()));
        }
    }

    private static URI mcpUri(org.springframework.web.util.UriBuilder uri, Path worktree) {
        return uri.path("/mcp").queryParam("directory", "{directory}")
                .build(Map.of("directory", worktree.toString()));
    }

    record Access(Map<String, String> statuses) {
        Access {
            statuses = statuses == null ? Map.of() : Map.copyOf(statuses);
        }

        static Access empty() {
            return new Access(Map.of());
        }

        List<String> connectedServers() {
            return statuses.entrySet().stream()
                    .filter(entry -> "connected".equalsIgnoreCase(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();
        }

        void requireCandidateReady(boolean managed, String generation, String internalMcpServer) {
            if (!managed || generation == null || generation.isBlank()
                    || internalMcpServer == null || internalMcpServer.isBlank()) {
                throw new SessionFailure("OPENCODE_INTERNAL_MCP_NOT_READY",
                        "Candidate sessions require the current managed OpenCode generation");
            }
            if (!"connected".equalsIgnoreCase(statuses.get(internalMcpServer))) {
                throw new SessionFailure("OPENCODE_INTERNAL_MCP_NOT_READY",
                        "The exact internal MCP server is not connected for this OpenCode generation");
            }
        }
    }
}
