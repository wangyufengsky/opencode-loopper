package io.opencode.loopper.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Holds only the currently active private-MCP generation and authorizes its bearer. */
public final class InternalMcpRuntimeAccess {
    private final AtomicReference<InternalMcpCredentialProvider.Credentials> current = new AtomicReference<>();
    private final AtomicReference<InternalMcpReadiness> readiness = new AtomicReference<>(InternalMcpReadiness.inactive());

    public void activate(InternalMcpCredentialProvider.Credentials credentials) {
        current.set(credentials);
        readiness.set(InternalMcpReadiness.connecting(credentials));
    }

    public Optional<InternalMcpCredentialProvider.Credentials> current() {
        return Optional.ofNullable(current.get());
    }

    public InternalMcpReadiness readiness() {
        return readiness.get();
    }

    public void connected(String generation) {
        InternalMcpCredentialProvider.Credentials active = current.get();
        if (active != null && active.generation().equals(generation)) {
            readiness.set(InternalMcpReadiness.connected(active));
        }
    }

    public void unavailable(String generation, String detail) {
        InternalMcpCredentialProvider.Credentials active = current.get();
        if (active != null && active.generation().equals(generation)) {
            readiness.set(InternalMcpReadiness.unavailable(active, detail));
        }
    }

    public void clear(String generation) {
        InternalMcpCredentialProvider.Credentials active = current.get();
        if (active != null && active.generation().equals(generation) && current.compareAndSet(active, null)) {
            readiness.set(InternalMcpReadiness.inactive());
        }
    }

    public boolean matchesBearer(String authorization) {
        InternalMcpCredentialProvider.Credentials active = current.get();
        if (active == null || authorization == null) return false;
        byte[] expected = ("Bearer " + active.bearerToken()).getBytes(StandardCharsets.UTF_8);
        byte[] supplied = authorization.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, supplied);
    }
}
