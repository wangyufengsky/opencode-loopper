package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.SessionFailure;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import static io.opencode.loopper.runtime.OpenCodeClient.*;
import static io.opencode.loopper.runtime.OpenCodeHttpTransport.directoryUri;

/** Discovers and compiles policy before creating a non-attested legacy transport Session. */
final class OpenCodeSessionCreator {
    private final Supplier<OpenCodeConnectionDetails> connectionSupplier;
    private final OpenCodeHttpTransport http;
    private final OpenCodeMcpDiscovery mcpDiscovery;
    private final OpenCodeSessionConnectionGuard sessionConnections;
    private final AssistRuntimeSupport assist;
    private final ConfiguredRoleRuntime roles;
    OpenCodeSessionCreator(Supplier<OpenCodeConnectionDetails> connections, OpenCodeHttpTransport http,
            OpenCodeMcpDiscovery discovery, OpenCodeSessionConnectionGuard bindings,
            AssistRuntimeSupport assist, ConfiguredRoleRuntime roles) {
        this.connectionSupplier = connections; this.http = http; this.mcpDiscovery = discovery;
        this.sessionConnections = bindings; this.assist = assist; this.roles = roles;
    }
    record Created(OpenCodeSession session, SessionProfile profile, boolean managed) { }
    Created create(Path worktree, String title, OpenCodeModel model, SessionProfile profile, RoleContext context) {
        try {
            Path canonical = worktree.toRealPath();
            SessionProfile effectiveProfile = profile == null ? SessionProfile.IMPLEMENTATION : profile;
            OpenCodeConnectionDetails connection = connectionSupplier.get();
            RestClient sessionClient = http.client(connection);
            Map<String, Object> request = new LinkedHashMap<>();
            if (title != null && !title.isBlank()) request.put("title", title);
            if (model != null && model.providerId() != null && !model.providerId().isBlank() && model.modelId() != null && !model.modelId().isBlank()) {
                request.put("model", Map.of("id", model.modelId(), "providerID", model.providerId()));
            }
            OpenCodeMcpDiscovery.Access mcp = effectiveProfile == SessionProfile.ROUTER_NO_TOOLS
                    ? OpenCodeMcpDiscovery.Access.empty()
                    : mcpDiscovery.discover(sessionClient, canonical, connection.internalMcpServer());
            if (OpenCodeHttpClientSemantics.candidateProfile(effectiveProfile) || effectiveProfile == SessionProfile.PPT_AGENT) {
                mcp.requireCandidateReady(connection.managed(), connection.generation(), connection.internalMcpServer());
            }
            List<Map<String, String>> permissions = OpenCodePermissionPolicy.rules(effectiveProfile,
                    mcp.connectedServers(), connection.internalMcpServer());
            if (assist != null) permissions=assist.permissions(canonical,effectiveProfile,mcp.connectedServers(),connection.internalMcpServer(),false);
            if (roles != null) permissions = roles.permissions(context, effectiveProfile, permissions.stream()
                    .map(r -> new SessionPermissionRule(r.get("permission"), r.get("pattern"), r.get("action"))).toList(), connection.internalMcpServer())
                    .stream().map(r -> Map.of("permission", r.permission(), "pattern", r.pattern(), "action", r.action())).toList();
            request.put("permission", permissions);
            JsonNode body = sessionClient.post().uri(uri -> directoryUri(uri, "/session", canonical))
                    .contentType(MediaType.APPLICATION_JSON).body(request)
                    .retrieve().body(JsonNode.class);
            String id = body == null ? null : body.path("id").asText(null);
            if (id == null && body != null) id = body.path("session").path("id").asText(null);
            if (id == null || id.isBlank()) throw new SessionFailure("OPENCODE_INVALID_RESPONSE", "OpenCode did not return a session id");
            String reportedDirectory = body.path("directory").asText(null);
            if ((reportedDirectory == null || reportedDirectory.isBlank()) && body.has("session")) {
                reportedDirectory = body.path("session").path("directory").asText(null);
            }
            if (reportedDirectory == null || reportedDirectory.isBlank()) {
                throw new SessionFailure("OPENCODE_DIRECTORY_MISSING",
                        "OpenCode did not confirm the execution directory for the new session");
            }
            Path reported = Path.of(reportedDirectory).toRealPath();
            if (!reported.equals(canonical)) {
                throw new SessionFailure("OPENCODE_DIRECTORY_MISMATCH",
                        "OpenCode created the session outside the requested execution workspace");
            }
            OpenCodeSession session = sessionConnections.created(id, canonical, connection);
            if (assist != null) assist.remember(id,connection.generation(),canonical,effectiveProfile,permissions,connection.internalMcpServer());
            if (roles != null) roles.remember(id, context, effectiveProfile, permissions.stream()
                    .map(r -> new SessionPermissionRule(r.get("permission"), r.get("pattern"), r.get("action"))).toList());
            return new Created(session, effectiveProfile, connection.managed());
        } catch (SessionFailure e) { throw e; }
        catch (Exception e) { throw new SessionFailure("OPENCODE_SESSION_CREATE_FAILED", e.getMessage()); }
    }
}
