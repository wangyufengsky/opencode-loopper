package io.opencode.loopper.runtime;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class FakeOpenCodeClientTest {
    @TempDir Path temp;

    @Test
    void readOnlySessionAllowsTheRuntimeDefaultModel() {
        FakeOpenCodeClient client = new FakeOpenCodeClient();

        OpenCodeClient.OpenCodeSession session = client.createReadOnlySession(temp, "Designer", null);
        client.promptAsync(session, "Produce a read-only plan");

        assertThat(client.isReadOnlySession(session.id())).isTrue();
        assertThat(client.modelForSession(session.id())).isNull();
        assertThat(client.sessionStatus(session).completed()).isTrue();
    }
}
