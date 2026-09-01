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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeOpenCodeClientTest {
    @TempDir Path temp;

    @Test
    void exactRecoveryUsesFrozenCreationPlanAndCanonicalPromptHash() {
        FakeOpenCodeClient client = new FakeOpenCodeClient();
        String credential = "0123456789abcdefghijklmnopqrstuvwxyz_ABCD12";
        OpenCodeClient.OpenCodeModel model = new OpenCodeClient.OpenCodeModel(
                "opencode-go", "deepseek-v4-flash", false);

        OpenCodeClient.SessionCreationPlan plan = client.prepareSessionCreation(temp,
                "Acceptance legacy", model, OpenCodeClient.SessionProfile.COMPILER_BINDING_NO_TOOLS,
                credential);
        OpenCodeClient.SessionAttestation created = client.createSession(plan);

        assertThat(plan.exactTitle()).contains(credential);
        assertThat(plan.permissionPolicyDigest())
                .isEqualTo(OpenCodeClient.permissionPolicyDigest(plan.permissionPolicy()));
        assertThat(plan.createRequestSha256())
                .isEqualTo(OpenCodeClient.sessionCreationRequestSha256(plan));
        assertThat(created.attestationKind())
                .isEqualTo(OpenCodeClient.SessionAttestationKind.LOCAL_REQUEST_ATTESTED);
        assertThat(client.findSessionsByExactTitle(plan).matches()).containsExactly(created);

        OpenCodeClient.PromptRequest request = new OpenCodeClient.PromptRequest(
                "Choose candidate 1", null, null, new OpenCodeClient.ResponseFormat.Text(),
                "message-1", List.of());
        String requestSha256 = OpenCodeClient.promptRequestSha256(request);
        client.promptAsync(created.session(), request);

        assertThat(client.findPromptMessage(created.session(), request, requestSha256))
                .isEqualTo(new OpenCodeClient.MessageLookup(true, true, requestSha256));
        OpenCodeClient.PromptRequest drifted = new OpenCodeClient.PromptRequest(
                "Choose candidate 2", null, null, new OpenCodeClient.ResponseFormat.Text(),
                "message-1", List.of());
        assertThatThrownBy(() -> client.findPromptMessage(created.session(), drifted, requestSha256))
                .isInstanceOf(io.opencode.loopper.domain.SessionFailure.class)
                .hasMessageContaining("hash");
        assertThatThrownBy(() -> client.prepareSessionCreation(temp, "Acceptance legacy",
                new OpenCodeClient.OpenCodeModel("", "deepseek-v4-flash", false),
                OpenCodeClient.SessionProfile.COMPILER_BINDING_NO_TOOLS, credential))
                .isInstanceOf(io.opencode.loopper.domain.SessionFailure.class)
                .hasMessageContaining("provider and id");
    }

    @Test
    void candidatePlanningIsLocalAndReadinessRejectsManagedRuntimeDrift() {
        FakeOpenCodeClient client = new FakeOpenCodeClient();
        client.setManagedRuntime("generation-7", "loopper-private-7");
        String credential = "0123456789abcdefghijklmnopqrstuvwxyz_ABCD12";

        OpenCodeClient.SessionCreationPlan plan = client.prepareCandidateSessionCreationLocally(temp,
                "Acceptance internal", null,
                OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS,
                credential);

        assertThat(plan.managed()).isTrue();
        assertThat(plan.runtimeGenerationId()).isEqualTo("generation-7");
        assertThat(client.createSessionCalls()).isZero();
        assertThat(client.createReadOnlySessionCalls()).isZero();
        assertThat(client.promptCalls()).isZero();
        client.requireCandidateSessionReady(plan);

        client.setManagedRuntime("generation-8", "loopper-private-8");
        assertThatThrownBy(() -> client.requireCandidateSessionReady(plan))
                .isInstanceOf(io.opencode.loopper.domain.SessionFailure.class)
                .hasMessageContaining("runtime generation has changed");
    }

    @Test
    void reviewerCandidatePlanningRequiresManagedRuntimeAndFreezesExactPrivatePermission() {
        FakeOpenCodeClient client = new FakeOpenCodeClient();
        String credential = "0123456789abcdefghijklmnopqrstuvwxyz_ABCD12";

        assertThatThrownBy(() -> client.prepareCandidateSessionCreationLocally(temp,
                "Reviewer internal", null,
                OpenCodeClient.SessionProfile.REVIEWER_CANDIDATE_READ_ONLY,
                credential))
                .isInstanceOf(io.opencode.loopper.domain.SessionFailure.class)
                .hasMessageContaining("managed OpenCode generation");

        client.setManagedRuntime("generation-reviewer", "loopper-private-reviewer");
        OpenCodeClient.SessionCreationPlan plan = client.prepareCandidateSessionCreationLocally(temp,
                "Reviewer internal", null,
                OpenCodeClient.SessionProfile.REVIEWER_CANDIDATE_READ_ONLY,
                credential);

        assertThat(plan.permissionPolicy()).containsExactly(
                new OpenCodeClient.SessionPermissionRule("*", "*", "deny"),
                new OpenCodeClient.SessionPermissionRule("read", "*", "allow"),
                new OpenCodeClient.SessionPermissionRule("glob", "*", "allow"),
                new OpenCodeClient.SessionPermissionRule("grep", "*", "allow"),
                new OpenCodeClient.SessionPermissionRule("read", ".env", "deny"),
                new OpenCodeClient.SessionPermissionRule("read", ".env.*", "deny"),
                new OpenCodeClient.SessionPermissionRule("read", ".env.example", "allow"),
                new OpenCodeClient.SessionPermissionRule("external_directory", "*", "deny"),
                new OpenCodeClient.SessionPermissionRule(
                        "loopper-private-reviewer_submit_candidate", "*", "allow"));
    }

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
