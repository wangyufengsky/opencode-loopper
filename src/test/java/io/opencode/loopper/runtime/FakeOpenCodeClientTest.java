package io.opencode.loopper.runtime;

import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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

    @Test
    void exposesInjectableRuntimeInteractionAndSessionControlRecords() {
        FakeOpenCodeClient client = new FakeOpenCodeClient();
        OpenCodeClient.OpenCodeSession session = client.createSession(temp, "Runtime", null);
        client.setPendingPermission(session.id(), new OpenCodeClient.PendingPermission("perm-1", session.id(), "bash",
                List.of("git push"), Map.of("title", "Publish"), "Publish"));
        client.setSessionTodos(session.id(), List.of(new OpenCodeClient.SessionTodo("todo-1", "Inspect diff", "pending", "high", 0, Map.of())));
        client.setSessionUsage(session.id(), List.of(new OpenCodeClient.UsageRecord("message-1", "opencode", "model-1",
                11L, 13L, null, new BigDecimal("0.012"), "USD", true)));

        assertThat(client.pendingPermissions(session)).singleElement().extracting(OpenCodeClient.PendingPermission::id).isEqualTo("perm-1");
        client.replyPermission(session, "perm-1", OpenCodeClient.PermissionReply.ONCE, "safe here");
        assertThat(client.permissionReplyForRequest("perm-1")).isEqualTo(
                new FakeOpenCodeClient.PermissionReplyCall(session.id(), "perm-1", OpenCodeClient.PermissionReply.ONCE, "safe here"));
        assertThat(client.pendingPermissions(session)).isEmpty();
        assertThat(client.sessionTodos(session)).singleElement().extracting(OpenCodeClient.SessionTodo::content).isEqualTo("Inspect diff");
        assertThat(client.sessionUsage(session)).singleElement().satisfies(usage -> {
            assertThat(usage.messageId()).isEqualTo("message-1");
            assertThat(usage.totalTokens()).isNull();
            assertThat(usage.costAmount()).isEqualByComparingTo("0.012");
        });

        OpenCodeClient.OpenCodeSession fork = client.forkSession(session, "message-1");
        client.revertSession(session, "message-1", "part-1");
        client.summarizeSession(session, new OpenCodeClient.OpenCodeModel("opencode", "model-1", null), true);
        assertThat(fork.id()).startsWith("fake-fork-");
        assertThat(client.sessionTodos(fork)).extracting(OpenCodeClient.SessionTodo::id).containsExactly("todo-1");
        assertThat(client.forkCalls()).singleElement().extracting(FakeOpenCodeClient.ForkCall::messageId).isEqualTo("message-1");
        assertThat(client.revertCalls()).containsExactly(new FakeOpenCodeClient.RevertCall(session.id(), "message-1", "part-1"));
        assertThat(client.summarizeCalls()).containsExactly(new FakeOpenCodeClient.SummarizeCall(session.id(),
                new OpenCodeClient.OpenCodeModel("opencode", "model-1", null), true));
    }

    @Test
    void managedFakePersistsRuntimeBindingBeforeExposingSessionAndFork() {
        InMemoryBindings bindings = new InMemoryBindings();
        FakeOpenCodeClient client = new FakeOpenCodeClient(bindings);
        client.setManagedRuntime("generation-7", "loopper-private-7");

        OpenCodeClient.OpenCodeSession session = client.createReadOnlySession(temp, "Designer", null);
        OpenCodeClient.OpenCodeSession fork = client.forkSession(session, "message-1");

        assertThat(bindings.find(session.id())).hasValueSatisfying(binding -> {
            assertThat(binding.runtimeGenerationId()).isEqualTo("generation-7");
            assertThat(binding.ownershipMode()).isEqualTo(OpenCodeSessionRuntimeBindings.OwnershipMode.MANAGED);
            assertThat(binding.internalMcpServer()).isEqualTo("loopper-private-7");
        });
        assertThat(bindings.find(fork.id())).isPresent();
        assertThat(fork.generation()).isEqualTo("generation-7");
    }

    private static final class InMemoryBindings implements OpenCodeSessionRuntimeBindings {
        private final Map<String, Binding> values = new ConcurrentHashMap<>();

        @Override public void register(Binding binding) { values.put(binding.externalSessionId(), binding); }
        @Override public Optional<Binding> find(String externalSessionId) {
            return Optional.ofNullable(values.get(externalSessionId));
        }
    }
}
