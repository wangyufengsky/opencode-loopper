package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.SessionFailure;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Resolves a Session only against the runtime identity recorded when it was created. */
final class OpenCodeSessionConnectionGuard {
    private final Supplier<OpenCodeConnectionDetails> currentConnection;
    private final OpenCodeSessionRuntimeBindings bindings;
    private final boolean requirePersistentBinding;
    private final Map<String, OpenCodeConnectionDetails> frozenConnections = new ConcurrentHashMap<>();

    OpenCodeSessionConnectionGuard(Supplier<OpenCodeConnectionDetails> currentConnection,
                                   OpenCodeSessionRuntimeBindings bindings,
                                   boolean requirePersistentBinding) {
        this.currentConnection = currentConnection;
        this.bindings = bindings;
        this.requirePersistentBinding = requirePersistentBinding;
    }

    OpenCodeClient.OpenCodeSession created(String sessionId, java.nio.file.Path worktree,
                                           OpenCodeConnectionDetails connection) {
        OpenCodeSessionRuntimeBindings.Binding binding = binding(sessionId, connection);
        bindings.register(binding);
        frozenConnections.put(sessionId, connection);
        return new OpenCodeClient.OpenCodeSession(sessionId, worktree, connection.generation(),
                connection.internalMcpServer());
    }

    OpenCodeConnectionDetails resolve(OpenCodeClient.OpenCodeSession session) {
        OpenCodeConnectionDetails current = currentConnection.get();
        OpenCodeConnectionDetails frozen = frozenConnections.get(session.id());
        OpenCodeSessionRuntimeBindings.Binding persisted = bindings.find(session.id()).orElse(null);
        if (persisted != null) {
            validateSessionProjection(session, persisted);
            validateCurrent(current, persisted);
            if (frozen != null) validateCurrent(frozen, persisted);
            return frozen == null ? current : frozen;
        }
        if (requirePersistentBinding) {
            throw new SessionFailure("OPENCODE_SESSION_RUNTIME_BINDING_MISSING",
                    "The recovered OpenCode session has no durable runtime binding");
        }
        validateLegacyProjection(session, current);
        return frozen == null ? current : frozen;
    }

    private OpenCodeSessionRuntimeBindings.Binding binding(String sessionId,
                                                           OpenCodeConnectionDetails connection) {
        String fingerprint = endpointFingerprint(connection.baseUrl());
        if (connection.managed()) {
            if (blank(connection.generation()) || blank(connection.internalMcpServer())) {
                throw mismatch("Managed OpenCode did not expose a complete generation identity");
            }
            return new OpenCodeSessionRuntimeBindings.Binding(sessionId, connection.generation(),
                    OpenCodeSessionRuntimeBindings.OwnershipMode.MANAGED, fingerprint,
                    connection.internalMcpServer());
        }
        return new OpenCodeSessionRuntimeBindings.Binding(sessionId, "external-" + fingerprint,
                OpenCodeSessionRuntimeBindings.OwnershipMode.EXTERNAL, fingerprint, null);
    }

    private void validateSessionProjection(OpenCodeClient.OpenCodeSession session,
                                           OpenCodeSessionRuntimeBindings.Binding binding) {
        if (binding.ownershipMode() == OpenCodeSessionRuntimeBindings.OwnershipMode.LEGACY_UNKNOWN) {
            throw new SessionFailure("OPENCODE_SESSION_RUNTIME_BINDING_UNKNOWN",
                    "The recovered OpenCode session predates durable runtime identity and cannot be contacted safely");
        }
        if (!blank(session.generation()) && !session.generation().equals(binding.runtimeGenerationId())) {
            throw mismatch("The session generation does not match its durable runtime binding");
        }
        if (!blank(session.internalMcpServer())
                && !Objects.equals(session.internalMcpServer(), binding.internalMcpServer())) {
            throw mismatch("The session MCP identity does not match its durable runtime binding");
        }
    }

    private void validateCurrent(OpenCodeConnectionDetails connection,
                                 OpenCodeSessionRuntimeBindings.Binding binding) {
        if (!endpointFingerprint(connection.baseUrl()).equals(binding.endpointFingerprint())) {
            throw mismatch("The session belongs to a different OpenCode server endpoint");
        }
        if (binding.ownershipMode() == OpenCodeSessionRuntimeBindings.OwnershipMode.MANAGED) {
            if (!connection.managed()
                    || !Objects.equals(connection.generation(), binding.runtimeGenerationId())
                    || !Objects.equals(connection.internalMcpServer(), binding.internalMcpServer())) {
                throw mismatch("The session belongs to a different managed OpenCode generation");
            }
        } else if (connection.managed()) {
            throw mismatch("An external OpenCode session cannot bind to a managed runtime");
        }
    }

    private void validateLegacyProjection(OpenCodeClient.OpenCodeSession session,
                                          OpenCodeConnectionDetails current) {
        if (!blank(session.generation())
                && (!Objects.equals(session.generation(), current.generation())
                || !Objects.equals(session.internalMcpServer(), current.internalMcpServer()))) {
            throw mismatch("The session belongs to a different managed OpenCode generation");
        }
    }

    static String endpointFingerprint(URI endpoint) {
        if (endpoint == null) throw mismatch("OpenCode runtime endpoint is missing");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(endpoint.normalize().toASCIIString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static SessionFailure mismatch(String detail) {
        return new SessionFailure("OPENCODE_SESSION_GENERATION_MISMATCH", detail);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
