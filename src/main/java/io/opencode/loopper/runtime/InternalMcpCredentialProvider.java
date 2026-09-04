package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.MachineCandidateKind;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntSupplier;

/** Issues process-generation-scoped credentials for the private loopback MCP. */
public final class InternalMcpCredentialProvider {
    private final IntSupplier loopperPort;
    private final SecureRandom random;

    public InternalMcpCredentialProvider(IntSupplier loopperPort) {
        this(loopperPort, new SecureRandom());
    }

    InternalMcpCredentialProvider(IntSupplier loopperPort, SecureRandom random) {
        this.loopperPort = Objects.requireNonNull(loopperPort, "loopperPort");
        this.random = Objects.requireNonNull(random, "random");
    }

    public Credentials issue() {
        int port = loopperPort.getAsInt();
        if (port < 1 || port > 65_535) {
            throw new IllegalStateException("Loopper HTTP port is not available for internal MCP");
        }
        String generation = UUID.randomUUID().toString();
        String serverName = "loopper_internal_" + randomToken(9).toLowerCase(java.util.Locale.ROOT);
        String bearerToken = randomToken(32);
        URI endpoint = URI.create("http://127.0.0.1:" + port + InternalMcpContractCatalog.ENDPOINT_PATH);
        return new Credentials(generation, serverName, bearerToken, endpoint);
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public record Credentials(String generation, String serverName, String bearerToken, URI endpoint) {
        public Credentials {
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(serverName, "serverName");
            Objects.requireNonNull(bearerToken, "bearerToken");
            Objects.requireNonNull(endpoint, "endpoint");
        }

        public String exactToolName() {
            return serverName + "_" + InternalMcpContractCatalog.legacyToolName();
        }

        public String exactToolName(MachineCandidateKind kind) {
            return serverName + "_" + InternalMcpContractCatalog.toolName(kind);
        }

        @Override
        public String toString() {
            return "Credentials[generation=" + generation + ", serverName=" + serverName
                    + ", bearerToken=<redacted>, endpoint=" + endpoint + "]";
        }
    }
}
