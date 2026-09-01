package io.opencode.loopper.runtime;

import static io.opencode.loopper.runtime.OpenCodeHttpTransport.directoryUri;

import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.runtime.OpenCodeClient.FilePart;
import io.opencode.loopper.runtime.OpenCodeClient.MessageLookup;
import io.opencode.loopper.runtime.OpenCodeClient.OpenCodeModel;
import io.opencode.loopper.runtime.OpenCodeClient.OpenCodeSession;
import io.opencode.loopper.runtime.OpenCodeClient.PromptRequest;
import io.opencode.loopper.runtime.OpenCodeClient.SessionAttestation;
import io.opencode.loopper.runtime.OpenCodeClient.SessionAttestationKind;
import io.opencode.loopper.runtime.OpenCodeClient.SessionCreationPlan;
import io.opencode.loopper.runtime.OpenCodeClient.SessionLookup;
import io.opencode.loopper.runtime.OpenCodeClient.SessionPermissionRule;
import io.opencode.loopper.runtime.OpenCodeClient.SessionProfile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/** Owns the fail-closed HTTP protocol for exact Session and prompt recovery. */
final class OpenCodeExactRecoveryTransport {
    private final Supplier<OpenCodeConnectionDetails> connectionSupplier;
    private final Supplier<OpenCodeRuntimeManager.RuntimeIdentity> localIdentitySupplier;
    private final OpenCodeHttpTransport http;
    private final OpenCodeMcpDiscovery mcpDiscovery;
    private final OpenCodeSessionConnectionGuard sessionConnections;
    private final OpenCodeSessionRuntimeBindings runtimeBindings;
    private final boolean requirePersistentBinding;

    OpenCodeExactRecoveryTransport(Supplier<OpenCodeConnectionDetails> connectionSupplier,
                                   Supplier<OpenCodeRuntimeManager.RuntimeIdentity> localIdentitySupplier,
                                   OpenCodeHttpTransport http,
                                   OpenCodeMcpDiscovery mcpDiscovery,
                                   OpenCodeSessionConnectionGuard sessionConnections,
                                   OpenCodeSessionRuntimeBindings runtimeBindings,
                                   boolean requirePersistentBinding) {
        this.connectionSupplier = connectionSupplier;
        this.localIdentitySupplier = localIdentitySupplier;
        this.http = http;
        this.mcpDiscovery = mcpDiscovery;
        this.sessionConnections = sessionConnections;
        this.runtimeBindings = runtimeBindings;
        this.requirePersistentBinding = requirePersistentBinding;
    }

    SessionCreationPlan prepareCandidateLocally(Path worktree, String baseTitle, OpenCodeModel model,
                                                SessionProfile profile, String creationCredential) {
        if (!requirePersistentBinding) {
            throw new SessionFailure("OPENCODE_SESSION_RECOVERY_UNSUPPORTED",
                    "Attested session recovery requires durable runtime bindings");
        }
        if (!candidateProfile(profile)) {
            throw new SessionFailure("OPENCODE_CANDIDATE_PROFILE_INVALID",
                    "Local candidate planning requires a candidate Session profile");
        }
        try {
            Path canonical = worktree.toRealPath();
            OpenCodeRuntimeManager.RuntimeIdentity identity = localIdentitySupplier.get();
            if (identity == null || !identity.managed()) {
                throw new SessionFailure("CANDIDATE_MANAGED_RUNTIME_REQUIRED",
                        "Internal MCP candidates require a managed OpenCode runtime");
            }
            if (blank(identity.generation()) || blank(identity.internalMcpServer())
                    || identity.endpoint() == null) {
                throw new SessionFailure("OPENCODE_CANDIDATE_RUNTIME_IDENTITY_INCOMPLETE",
                        "Managed OpenCode did not expose a complete local runtime identity");
            }
            String fingerprint = OpenCodeSessionConnectionGuard.endpointFingerprint(identity.endpoint());
            List<SessionPermissionRule> permissions = permissionRules(
                    profile, List.of(), identity.internalMcpServer());
            String permissionDigest = OpenCodeClient.permissionPolicyDigest(permissions);
            String exactTitle = OpenCodeClient.recoveryTitle(baseTitle, creationCredential);
            String requestDigest = OpenCodeClient.sessionCreationRequestSha256(canonical, exactTitle,
                    identity.generation(), true, identity.internalMcpServer(), fingerprint,
                    model, profile, permissionDigest, creationCredential);
            return new SessionCreationPlan(canonical, exactTitle, identity.generation(), true,
                    identity.internalMcpServer(), fingerprint, model, profile, permissions,
                    permissionDigest, creationCredential, requestDigest);
        } catch (SessionFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SessionFailure("OPENCODE_CANDIDATE_RUNTIME_IDENTITY_UNAVAILABLE",
                    failure.getMessage());
        }
    }

    void requireCandidateReady(SessionCreationPlan persistedPlan) {
        if (persistedPlan == null || !candidateProfile(persistedPlan.profile())) {
            throw new SessionFailure("OPENCODE_CANDIDATE_PROFILE_INVALID",
                    "Candidate readiness requires a persisted candidate Session plan");
        }
        if (!persistedPlan.managed()) {
            throw new SessionFailure("CANDIDATE_MANAGED_RUNTIME_REQUIRED",
                    "Internal MCP candidates require a managed OpenCode runtime");
        }
        requireCandidatePolicy(persistedPlan);
        OpenCodeConnectionDetails connection = requireCurrentPlan(persistedPlan);
        OpenCodeMcpDiscovery.Access mcp = mcpDiscovery.discover(http.client(connection),
                persistedPlan.canonicalDirectory(), persistedPlan.internalMcpServer());
        mcp.requireCandidateReady(connection.managed(), connection.generation(),
                connection.internalMcpServer());
    }

    SessionCreationPlan prepare(Path worktree, String baseTitle, OpenCodeModel model,
                                SessionProfile profile, String creationCredential) {
        if (!requirePersistentBinding) {
            throw new SessionFailure("OPENCODE_SESSION_RECOVERY_UNSUPPORTED",
                    "Attested session recovery requires durable runtime bindings");
        }
        try {
            Path canonical = worktree.toRealPath();
            SessionProfile effectiveProfile = profile == null ? SessionProfile.IMPLEMENTATION : profile;
            OpenCodeConnectionDetails connection = connectionSupplier.get();
            OpenCodeMcpDiscovery.Access mcp = effectiveProfile == SessionProfile.ROUTER_NO_TOOLS
                    ? OpenCodeMcpDiscovery.Access.empty()
                    : mcpDiscovery.discover(http.client(connection), canonical,
                    connection.internalMcpServer());
            if (candidateProfile(effectiveProfile)) {
                mcp.requireCandidateReady(connection.managed(), connection.generation(),
                        connection.internalMcpServer());
            }
            List<SessionPermissionRule> permissions = permissionRules(effectiveProfile,
                    mcp.connectedServers(), connection.internalMcpServer());
            String permissionDigest = OpenCodeClient.permissionPolicyDigest(permissions);
            String fingerprint = OpenCodeSessionConnectionGuard.endpointFingerprint(connection.baseUrl());
            String generation = runtimeGeneration(connection, fingerprint);
            String exactTitle = OpenCodeClient.recoveryTitle(baseTitle, creationCredential);
            String requestDigest = OpenCodeClient.sessionCreationRequestSha256(canonical, exactTitle,
                    generation, connection.managed(), connection.internalMcpServer(), fingerprint,
                    model, effectiveProfile, permissionDigest, creationCredential);
            return new SessionCreationPlan(canonical, exactTitle, generation, connection.managed(),
                    connection.managed() ? connection.internalMcpServer() : null, fingerprint,
                    model, effectiveProfile, permissions, permissionDigest, creationCredential, requestDigest);
        } catch (SessionFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SessionFailure("OPENCODE_SESSION_RECOVERY_PLAN_FAILED", failure.getMessage());
        }
    }

    SessionAttestation create(SessionCreationPlan plan) {
        try {
            OpenCodeConnectionDetails connection = requireCurrentPlan(plan);
            JsonNode body = http.client(connection).post()
                    .uri(uri -> directoryUri(uri, "/session", plan.canonicalDirectory()))
                    .contentType(MediaType.APPLICATION_JSON).body(createRequest(plan))
                    .retrieve().body(JsonNode.class);
            String id = requiredText(first(body, "id", "session", "id"),
                    "OpenCode create response session id", "OPENCODE_INVALID_RESPONSE");
            String directory = requiredText(first(body, "directory", "session", "directory"),
                    "OpenCode create response directory", "OPENCODE_DIRECTORY_MISSING");
            Path reported = canonicalRemotePath(directory, "OPENCODE_DIRECTORY_MISMATCH");
            if (!reported.equals(plan.canonicalDirectory())) {
                throw new SessionFailure("OPENCODE_DIRECTORY_MISMATCH",
                        "OpenCode created the session outside the requested execution workspace");
            }
            sessionConnections.created(id, plan.canonicalDirectory(), connection);
            return attestation(id, plan);
        } catch (SessionFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new SessionFailure("OPENCODE_SESSION_CREATE_FAILED", failure.getMessage());
        }
    }

    SessionLookup findSessions(SessionCreationPlan plan) {
        if (!requirePersistentBinding) return new SessionLookup(false, List.of());
        try {
            OpenCodeConnectionDetails connection = requireCurrentPlan(plan);
            JsonNode body = http.client(connection).get()
                    .uri(uri -> directoryUri(uri, "/session", plan.canonicalDirectory()))
                    .retrieve().body(JsonNode.class);
            JsonNode sessions = exactSessionArray(body);
            List<SessionAttestation> matches = new ArrayList<>();
            for (JsonNode item : sessions) {
                if (!item.isObject()) throw invalidSessionLookup("Session list item must be an object");
                JsonNode titleNode = item.get("title");
                if (titleNode == null || !titleNode.isTextual() || titleNode.textValue().isBlank()) {
                    throw invalidSessionLookup("Session list item title must be a non-empty string");
                }
                if (!plan.exactTitle().equals(titleNode.textValue())) continue;
                String id = requiredLookupText(item.get("id"), "Exact-title session id");
                String directory = requiredLookupText(item.get("directory"),
                        "Exact-title session directory");
                Path reported = lookupDirectory(directory);
                if (!reported.equals(plan.canonicalDirectory())) {
                    throw invalidSessionLookup("Exact-title session belongs to a different directory");
                }
                validateOrRegisterBinding(id, plan, connection);
                matches.add(attestation(id, plan));
            }
            return new SessionLookup(true, matches);
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 404 || failure.getStatusCode().value() == 405) {
                return new SessionLookup(false, List.of());
            }
            throw new SessionFailure("OPENCODE_SESSION_LOOKUP_FAILED", failure.getMessage());
        } catch (SessionFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new SessionFailure("OPENCODE_SESSION_LOOKUP_FAILED", failure.getMessage());
        }
    }

    MessageLookup findPrompt(OpenCodeSession session, PromptRequest expectedRequest,
                             String persistedRequestSha256) {
        if (expectedRequest == null || expectedRequest.messageId() == null) {
            throw new SessionFailure("OPENCODE_PROMPT_LOOKUP_INVALID_REQUEST",
                    "Exact prompt recovery requires a deterministic message id");
        }
        String calculated = OpenCodeClient.promptRequestSha256(expectedRequest);
        if (!Objects.equals(calculated, persistedRequestSha256)) {
            throw new SessionFailure("OPENCODE_PROMPT_REQUEST_HASH_MISMATCH",
                    "Persisted prompt request hash does not match the exact request");
        }
        try {
            JsonNode body = http.client(sessionConnections.resolve(session)).get()
                    .uri(uri -> directoryUri(uri, "/session/{id}/message/{messageId}",
                            session.worktree(), Map.of("id", session.id(),
                                    "messageId", expectedRequest.messageId())))
                    .retrieve().body(JsonNode.class);
            validatePromptMessage(body, expectedRequest);
            return new MessageLookup(true, true, calculated);
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 404) return new MessageLookup(true, false, null);
            throw new SessionFailure("OPENCODE_PROMPT_LOOKUP_FAILED", failure.getMessage());
        } catch (SessionFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new SessionFailure("OPENCODE_PROMPT_LOOKUP_FAILED", failure.getMessage());
        }
    }

    private OpenCodeConnectionDetails requireCurrentPlan(SessionCreationPlan plan) {
        if (plan == null) throw new SessionFailure("OPENCODE_SESSION_CREATION_PLAN_INVALID",
                "Session creation plan is required");
        try {
            if (!plan.canonicalDirectory().toRealPath().equals(plan.canonicalDirectory())) {
                throw stalePlan("The frozen session directory is no longer canonical");
            }
        } catch (SessionFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw stalePlan("The frozen session directory is no longer available");
        }
        OpenCodeConnectionDetails connection = connectionSupplier.get();
        String fingerprint = OpenCodeSessionConnectionGuard.endpointFingerprint(connection.baseUrl());
        String generation = runtimeGeneration(connection, fingerprint);
        if (plan.managed() != connection.managed()
                || !Objects.equals(plan.runtimeGenerationId(), generation)
                || !Objects.equals(plan.endpointFingerprint(), fingerprint)
                || !Objects.equals(plan.internalMcpServer(),
                connection.managed() ? connection.internalMcpServer() : null)) {
            throw stalePlan("The frozen OpenCode endpoint or runtime generation has changed");
        }
        if (!OpenCodeClient.permissionPolicyDigest(plan.permissionPolicy())
                .equals(plan.permissionPolicyDigest())
                || !OpenCodeClient.sessionCreationRequestSha256(plan).equals(plan.createRequestSha256())) {
            throw stalePlan("The frozen model, profile, permission policy, or create request has changed");
        }
        return connection;
    }

    private Map<String, Object> createRequest(SessionCreationPlan plan) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("title", plan.exactTitle());
        if (plan.model() != null) {
            request.put("model", Map.of("id", plan.model().modelId(),
                    "providerID", plan.model().providerId()));
        }
        request.put("permission", plan.permissionPolicy().stream().map(rule -> {
            Map<String, String> value = new LinkedHashMap<>();
            value.put("permission", rule.permission());
            value.put("pattern", rule.pattern());
            value.put("action", rule.action());
            return value;
        }).toList());
        return request;
    }

    private void validateOrRegisterBinding(String id, SessionCreationPlan plan,
                                           OpenCodeConnectionDetails connection) {
        OpenCodeSessionRuntimeBindings.Binding existing = runtimeBindings.find(id).orElse(null);
        OpenCodeSessionRuntimeBindings.OwnershipMode ownership = plan.managed()
                ? OpenCodeSessionRuntimeBindings.OwnershipMode.MANAGED
                : OpenCodeSessionRuntimeBindings.OwnershipMode.EXTERNAL;
        if (existing == null) {
            sessionConnections.created(id, plan.canonicalDirectory(), connection);
            existing = runtimeBindings.find(id).orElse(null);
        }
        if (existing == null) {
            throw new SessionFailure("OPENCODE_SESSION_RUNTIME_BINDING_MISSING",
                    "Exact-title recovery could not persist the remote runtime binding");
        }
        if (existing.ownershipMode() != ownership
                || !Objects.equals(existing.runtimeGenerationId(), plan.runtimeGenerationId())
                || !Objects.equals(existing.endpointFingerprint(), plan.endpointFingerprint())
                || !Objects.equals(existing.internalMcpServer(), plan.internalMcpServer())) {
            throw stalePlan("Exact-title session is bound to a different runtime identity");
        }
    }

    private static SessionAttestation attestation(String id, SessionCreationPlan plan) {
        return new SessionAttestation(id, plan.canonicalDirectory(), plan.exactTitle(),
                plan.runtimeGenerationId(), plan.managed(), plan.internalMcpServer(),
                plan.endpointFingerprint(), plan.model(), plan.profile(), plan.permissionPolicy(),
                plan.permissionPolicyDigest(), plan.creationCredential(), plan.createRequestSha256(),
                SessionAttestationKind.LOCAL_REQUEST_ATTESTED);
    }

    private static void validatePromptMessage(JsonNode body, PromptRequest expected) {
        if (body == null || !body.isObject()) throw invalidPromptLookup(
                "OpenCode prompt lookup returned a malformed 200 response");
        JsonNode info = body.get("info");
        if (info == null || !info.isObject()) throw invalidPromptLookup(
                "OpenCode prompt lookup response is missing message info");
        JsonNode id = info.get("id");
        JsonNode role = info.get("role");
        if (id == null || !id.isTextual() || !expected.messageId().equals(id.textValue())) {
            throw invalidPromptLookup("OpenCode prompt lookup returned a different message id");
        }
        if (role == null || !role.isTextual() || !"user".equals(role.textValue())) {
            throw invalidPromptLookup("OpenCode prompt lookup did not return the original user message");
        }
        JsonNode parts = body.get("parts");
        if (parts == null || !parts.isArray() || parts.size() != expected.files().size() + 1) {
            throw invalidPromptLookup("OpenCode prompt lookup returned different message parts");
        }
        validateTextPart(parts.get(0), expected.text());
        for (int index = 0; index < expected.files().size(); index++) {
            validateFilePart(parts.get(index + 1), expected.files().get(index));
        }
    }

    private static void validateTextPart(JsonNode text, String expected) {
        if (text == null || !text.isObject()
                || !textEquals(text.get("type"), "text")
                || !textEquals(text.get("text"), expected)) {
            throw invalidPromptLookup("OpenCode prompt lookup returned different text content");
        }
    }

    private static void validateFilePart(JsonNode file, FilePart expected) {
        if (file == null || !file.isObject()
                || !textEquals(file.get("type"), "file")
                || !textEquals(file.get("mime"), expected.mediaType())
                || !textEquals(file.get("filename"), expected.filename())
                || !textEquals(file.get("url"), expected.managedUri().normalize().toASCIIString())) {
            throw invalidPromptLookup("OpenCode prompt lookup returned different file content");
        }
    }

    private static JsonNode exactSessionArray(JsonNode body) {
        if (body != null && body.isArray()) return body;
        if (body != null && body.isObject()
                && body.has("sessions") && body.get("sessions").isArray()) {
            return body.get("sessions");
        }
        throw invalidSessionLookup("OpenCode session lookup returned a malformed 200 response");
    }

    private static String requiredLookupText(JsonNode value, String label) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalidSessionLookup(label + " must be a non-empty string");
        }
        return value.textValue();
    }

    private static String requiredText(JsonNode value, String label, String code) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new SessionFailure(code, label + " must be a non-empty string");
        }
        return value.textValue();
    }

    private static JsonNode first(JsonNode body, String direct, String nested, String nestedField) {
        if (body == null || !body.isObject()) return null;
        JsonNode directValue = body.get(direct);
        if (directValue != null) return directValue;
        JsonNode nestedValue = body.get(nested);
        return nestedValue != null && nestedValue.isObject() ? nestedValue.get(nestedField) : null;
    }

    private static Path canonicalRemotePath(String directory, String code) {
        try {
            return Path.of(directory).toRealPath();
        } catch (Exception failure) {
            throw new SessionFailure(code, "OpenCode returned an invalid execution directory");
        }
    }

    private static Path lookupDirectory(String directory) {
        try {
            return Path.of(directory).toRealPath();
        } catch (Exception invalid) {
            throw invalidSessionLookup("Exact-title session directory is not a canonical existing path");
        }
    }

    private static List<SessionPermissionRule> permissionRules(SessionProfile profile,
            List<String> connectedServers, String internalMcpServer) {
        return OpenCodePermissionPolicy.rules(profile, connectedServers, internalMcpServer).stream()
                .map(rule -> new SessionPermissionRule(rule.get("permission"), rule.get("pattern"),
                        rule.get("action")))
                .toList();
    }

    private static boolean candidateProfile(SessionProfile profile) {
        return profile == SessionProfile.DECOMPOSER_CANDIDATE_READ_ONLY
                || profile == SessionProfile.PACKAGE_DESIGN_CANDIDATE_READ_ONLY
                || profile == SessionProfile.PACKAGE_DESIGN_CANDIDATE_INTERACTIVE_READ_ONLY
                || profile == SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS
                || profile == SessionProfile.ROLLING_PACKAGE_CANDIDATE_READ_ONLY;
    }

    private static void requireCandidatePolicy(SessionCreationPlan plan) {
        List<SessionPermissionRule> expected = permissionRules(
                plan.profile(), List.of(), plan.internalMcpServer());
        if (!expected.equals(plan.permissionPolicy())
                || !OpenCodeClient.permissionPolicyDigest(expected).equals(plan.permissionPolicyDigest())) {
            throw stalePlan("The frozen candidate permission policy has changed");
        }
    }

    private static String runtimeGeneration(OpenCodeConnectionDetails connection, String fingerprint) {
        if (!connection.managed()) return "external-" + fingerprint;
        if (connection.generation() == null || connection.generation().isBlank()
                || connection.internalMcpServer() == null || connection.internalMcpServer().isBlank()) {
            throw stalePlan("Managed OpenCode did not expose a complete generation identity");
        }
        return connection.generation();
    }

    private static boolean textEquals(JsonNode node, String expected) {
        return node != null && node.isTextual() && Objects.equals(expected, node.textValue());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static SessionFailure stalePlan(String detail) {
        return new SessionFailure("OPENCODE_SESSION_CREATION_PLAN_STALE", detail);
    }

    private static SessionFailure invalidSessionLookup(String detail) {
        return new SessionFailure("OPENCODE_SESSION_LOOKUP_INVALID_RESPONSE", detail);
    }

    private static SessionFailure invalidPromptLookup(String detail) {
        return new SessionFailure("OPENCODE_PROMPT_LOOKUP_INVALID_RESPONSE", detail);
    }
}
