package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class InternalMcpCredentialProviderTest {
    @Test
    void issuesFreshLoopbackCredentialsForEveryManagedGenerationWithoutRenderingTheToken() {
        InternalMcpCredentialProvider provider = new InternalMcpCredentialProvider(() -> 18083);

        InternalMcpCredentialProvider.Credentials first = provider.issue();
        InternalMcpCredentialProvider.Credentials second = provider.issue();

        assertThat(first.endpoint().toString())
                .isEqualTo("http://127.0.0.1:18083/api/internal-mcp-streamable");
        assertThat(first.serverName()).startsWith("loopper_internal_");
        assertThat(first.exactToolName())
                .isEqualTo(first.serverName() + "_submit_candidate");
        assertThat(first.exactToolName(io.opencode.loopper.domain.MachineCandidateKind.REVIEWER_REPORT_V1))
                .isEqualTo(first.serverName() + "_submit_reviewer_report");
        assertThat(first.generation()).isNotEqualTo(second.generation());
        assertThat(first.serverName()).isNotEqualTo(second.serverName());
        assertThat(first.bearerToken()).isNotEqualTo(second.bearerToken());
        assertThat(first.toString()).doesNotContain(first.bearerToken());
    }

    @Test
    void runtimeAccessAuthorizesOnlyTheActiveGenerationToken() {
        InternalMcpCredentialProvider provider = new InternalMcpCredentialProvider(() -> 18083);
        InternalMcpRuntimeAccess access = new InternalMcpRuntimeAccess();
        InternalMcpCredentialProvider.Credentials first = provider.issue();
        InternalMcpCredentialProvider.Credentials second = provider.issue();

        access.activate(first);
        assertThat(access.matchesBearer("Bearer " + first.bearerToken())).isTrue();
        assertThat(access.matchesBearer("Bearer " + first.bearerToken() + "x")).isFalse();

        access.activate(second);
        assertThat(access.matchesBearer("Bearer " + first.bearerToken())).isFalse();
        assertThat(access.matchesBearer("Bearer " + second.bearerToken())).isTrue();
        assertThat(access.current().orElseThrow().generation()).isEqualTo(second.generation());
    }

    @Test
    void configurationPrefersTheActualBoundPortAndRejectsAnUnboundRandomPort() {
        MockEnvironment bound = new MockEnvironment()
                .withProperty("server.port", "0")
                .withProperty("local.server.port", "18083");
        InternalMcpCredentialProvider provider = new InternalMcpCredentialProvider(
                () -> OpenCodeConfiguration.boundHttpPort(bound));

        assertThat(provider.issue().endpoint().getPort()).isEqualTo(18083);

        MockEnvironment unbound = new MockEnvironment().withProperty("server.port", "0");
        InternalMcpCredentialProvider unavailable = new InternalMcpCredentialProvider(
                () -> OpenCodeConfiguration.boundHttpPort(unbound));
        org.assertj.core.api.Assertions.assertThatThrownBy(unavailable::issue)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("port");
    }
}
