package io.opencode.loopper.runtime;

/** Internal readiness state; API adapters must redact generation and private server identity. */
public record InternalMcpReadiness(String status, String generation, String serverName, String detail) {
    public static InternalMcpReadiness inactive() {
        return new InternalMcpReadiness("INACTIVE", null, null, null);
    }

    public static InternalMcpReadiness connecting(InternalMcpCredentialProvider.Credentials credentials) {
        return new InternalMcpReadiness("CONNECTING", credentials.generation(), credentials.serverName(), null);
    }

    public static InternalMcpReadiness connected(InternalMcpCredentialProvider.Credentials credentials) {
        return new InternalMcpReadiness("CONNECTED", credentials.generation(), credentials.serverName(), null);
    }

    public static InternalMcpReadiness unavailable(InternalMcpCredentialProvider.Credentials credentials, String detail) {
        return new InternalMcpReadiness("UNAVAILABLE", credentials.generation(), credentials.serverName(), detail);
    }
}
