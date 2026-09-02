package io.opencode.loopper.api;

import io.opencode.loopper.domain.MachineCandidateOutcome;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.runtime.InternalMcpCredentialProvider;
import io.opencode.loopper.runtime.InternalMcpRuntimeAccess;
import io.opencode.loopper.runtime.OpenCodeAttachmentResources;
import io.opencode.loopper.service.MachineCandidateSubmission;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalMcpServerIntegrationTest {
    private final InternalMcpRuntimeAccess access = new InternalMcpRuntimeAccess();
    private final AtomicReference<MachineCandidateSubmission.SubmitCommand> submitted = new AtomicReference<>();
    private InternalMcpCredentialProvider.Credentials credentials;
    private InternalMcpServerConfiguration.InternalMcpServerRuntime runtime;
    private MockMvc mvc;
    private final OpenCodeAttachmentResources resources = new OpenCodeAttachmentResources(access);

    @BeforeEach
    void setUp() {
        credentials = new InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(credentials);
        InternalMcpServerConfiguration configuration = new InternalMcpServerConfiguration();
        runtime = configuration.internalMcpServerRuntime(submissions(), new ObjectMapper(), resources, "test");
        mvc = MockMvcBuilders.routerFunctions(runtime.routerFunction())
                .addFilters(new InternalMcpStreamableBearerFilter(access))
                .build();
    }

    @AfterEach
    void close() {
        runtime.close();
    }

    @Test
    void loopbackBearerServerListsOnlySubmitCandidateAndDelegatesTheObjectPayload() throws Exception {
        String initialize = rpc(1, "initialize",
                "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}");

        mvc.perform(post("/api/internal-mcp-streamable").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM).content(initialize))
                .andExpect(status().isUnauthorized());
        mvc.perform(internal(initialize, null).with(request -> { request.setRemoteAddr("10.0.0.8"); return request; }))
                .andExpect(status().isForbidden());
        mvc.perform(internal(initialize, null).with(request -> { request.setRemoteAddr("localhost"); return request; }))
                .andExpect(status().isForbidden());

        MvcResult initialized = mvc.perform(internal(initialize, null))
                .andExpect(status().isOk()).andReturn();
        String sessionId = initialized.getResponse().getHeader("Mcp-Session-Id");
        assertThat(sessionId).isNotBlank();
        mvc.perform(internal("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}",
                        sessionId))
                .andExpect(status().isAccepted());

        MvcResult listed = mvc.perform(internal(rpc(2, "tools/list", "{}"), sessionId))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(listed)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("submit_candidate")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"candidate\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("get_project_context"))));

        String arguments = "{\"name\":\"submit_candidate\",\"arguments\":{"
                + "\"runId\":\"run-1\",\"idempotencyKey\":\"key-1\","
                + "\"candidate\":{\"normalizedGoal\":\"目标\"},\"expectedSubmissionRevision\":3}}";
        MvcResult called = mvc.perform(internal(rpc(3, "tools/call", arguments), sessionId))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(called)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("REJECTED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("allowedValues")));

        MachineCandidateSubmission.SubmitCommand command = submitted.get();
        assertThat(command.runId()).isEqualTo("run-1");
        assertThat(command.idempotencyKey()).isEqualTo("key-1");
        assertThat(command.expectedSubmissionRevision()).isEqualTo(3);
        assertThat(command.submissionChannel()).isEqualTo(
                MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);
        assertThat(command.candidateJson()).contains("normalizedGoal", "目标");
    }

    @Test
    void unexpectedSubmissionFailureReturnsOnlyTheStablePublicError() throws Exception {
        runtime.close();
        runtime = new InternalMcpServerConfiguration().internalMcpServerRuntime(
                failingSubmissions(), new ObjectMapper(), resources, "test");
        mvc = MockMvcBuilders.routerFunctions(runtime.routerFunction())
                .addFilters(new InternalMcpStreamableBearerFilter(access))
                .build();

        MvcResult initialized = mvc.perform(internal(rpc(1, "initialize",
                        "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
                                + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}"), null))
                .andExpect(status().isOk()).andReturn();
        String sessionId = initialized.getResponse().getHeader("Mcp-Session-Id");
        mvc.perform(internal("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}",
                        sessionId))
                .andExpect(status().isAccepted());

        String arguments = "{\"name\":\"submit_candidate\",\"arguments\":{"
                + "\"runId\":\"run-1\",\"idempotencyKey\":\"key-1\","
                + "\"candidate\":{\"normalizedGoal\":\"目标\"},\"expectedSubmissionRevision\":0}}";
        MvcResult called = mvc.perform(internal(rpc(2, "tools/call", arguments), sessionId))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(called)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "INTERNAL_CANDIDATE_SUBMISSION_FAILED")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sqlite-secret-table"))));
    }

    @Test
    void privateResourcesAreNonEnumerableButGrantedImmutableTextCanBeRead() throws Exception {
        var file = java.nio.file.Files.createTempFile("mcp-resource-test-", ".txt");
        try {
            byte[] bytes = "Synthetic resource marker ORCHID-7319".getBytes(StandardCharsets.UTF_8);
            java.nio.file.Files.write(file, bytes);
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            String uri = (String) resources.prepare("ses-resource", credentials.generation(), credentials.serverName(),
                    List.of(new io.opencode.loopper.runtime.OpenCodeClient.FilePart("reference.txt", "text/plain", file.toUri(), hash)))
                    .getFirst().get("url");
            MvcResult initialized = mvc.perform(internal(rpc(1, "initialize",
                    "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}"), null))
                    .andExpect(status().isOk()).andReturn();
            String sessionId = initialized.getResponse().getHeader("Mcp-Session-Id");
            assertThat(initialized.getResponse().getContentAsString()).contains("resources");
            mvc.perform(internal("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}", sessionId))
                    .andExpect(status().isAccepted());
            String list = result(rpc(2, "resources/list", "{}"), sessionId);
            assertThat(list).contains("\"resources\":[]").doesNotContain(uri, "reference.txt", "ORCHID");
            assertThat(result(rpc(3, "resources/templates/list", "{}"), sessionId)).contains(OpenCodeAttachmentResources.URI_TEMPLATE).doesNotContain(uri);
            String read = result(rpc(4, "resources/read", "{\"uri\":\"" + uri + "\"}"), sessionId);
            assertThat(read).contains("ORCHID-7319", "text/plain").doesNotContain(file.toString());
            resources.revoke("ses-resource");
            assertThat(result(rpc(5, "resources/read", "{\"uri\":\"" + uri + "\"}"), sessionId))
                    .contains("error").doesNotContain("ORCHID-7319", file.toString());
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    private String result(String body, String sessionId) throws Exception {
        MvcResult call = mvc.perform(internal(body, sessionId)).andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(call)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void candidateCannotBypassFailedAttachmentDelivery() throws Exception {
        runtime.close();
        var gated = org.mockito.Mockito.mock(OpenCodeAttachmentResources.class);
        var submission = org.mockito.Mockito.mock(MachineCandidateSubmission.class);
        var run = org.mockito.Mockito.mock(MachineCandidateSubmission.RunSnapshot.class);
        org.mockito.Mockito.when(run.externalSessionId()).thenReturn("ses-unverified");
        org.mockito.Mockito.when(submission.find("run-1")).thenReturn(Optional.of(run));
        org.mockito.Mockito.doThrow(new io.opencode.loopper.domain.SessionFailure("ATTACHMENT_MCP_NOT_READ", "Attachment not verified"))
                .when(gated).awaitDelivery("ses-unverified");
        runtime = new InternalMcpServerConfiguration().internalMcpServerRuntime(submission, new ObjectMapper(), gated, "test");
        mvc = MockMvcBuilders.routerFunctions(runtime.routerFunction()).addFilters(new InternalMcpStreamableBearerFilter(access)).build();
        String sessionId = mvc.perform(internal(rpc(1, "initialize",
                "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}"), null))
                .andReturn().getResponse().getHeader("Mcp-Session-Id");
        mvc.perform(internal("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}", sessionId)).andExpect(status().isAccepted());
        assertThat(result(rpc(2, "tools/call", "{\"name\":\"submit_candidate\",\"arguments\":{\"runId\":\"run-1\",\"idempotencyKey\":\"key\",\"candidate\":{},\"expectedSubmissionRevision\":0}}"), sessionId))
                .contains("ATTACHMENT_MCP_NOT_READ");
        org.mockito.Mockito.verify(submission, org.mockito.Mockito.never()).submit(org.mockito.ArgumentMatchers.any());
    }

    private MachineCandidateSubmission submissions() {
        return new MachineCandidateSubmission() {
            @Override public RunSnapshot open(OpenCommand command) { throw new UnsupportedOperationException(); }
            @Override public SubmissionResult submit(SubmitCommand command) {
                submitted.set(command);
                return new SubmissionResult(command.runId(), MachineCandidateOutcome.REJECTED,
                        MachineCandidateRunState.OPEN, 1, 2, true,
                        List.of(new Problem("VALUE_NOT_ALLOWED", "/workflowType", "请选择闭集值",
                                List.of("DIRECT_SOFTWARE_DESIGN", "FULL_PACKAGE_DESIGN"))),
                        null, 4, "{\"outcome\":\"REJECTED\",\"retryable\":true,"
                        + "\"problems\":[{\"code\":\"VALUE_NOT_ALLOWED\","
                        + "\"allowedValues\":[\"DIRECT_SOFTWARE_DESIGN\",\"FULL_PACKAGE_DESIGN\"]}],"
                        + "\"submissionRevision\":4}");
            }
            @Override public RunSnapshot close(CloseCommand command) { throw new UnsupportedOperationException(); }
            @Override public Optional<RunSnapshot> find(String runId) { return Optional.empty(); }
            @Override public Optional<SubmissionResult> terminal(String runId) { return Optional.empty(); }
        };
    }

    private MachineCandidateSubmission failingSubmissions() {
        return new MachineCandidateSubmission() {
            @Override public RunSnapshot open(OpenCommand command) { throw new UnsupportedOperationException(); }
            @Override public SubmissionResult submit(SubmitCommand command) {
                throw new IllegalStateException("sqlite-secret-table leaked by driver");
            }
            @Override public RunSnapshot close(CloseCommand command) { throw new UnsupportedOperationException(); }
            @Override public Optional<RunSnapshot> find(String runId) { return Optional.empty(); }
            @Override public Optional<SubmissionResult> terminal(String runId) { return Optional.empty(); }
        };
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder internal(
            String body, String sessionId) {
        var request = post("/api/internal-mcp-streamable")
                .header("Authorization", "Bearer " + credentials.bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(body.getBytes(StandardCharsets.UTF_8));
        if (sessionId != null) request.header("Mcp-Session-Id", sessionId);
        return request;
    }

    private static String rpc(int id, String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method
                + "\",\"params\":" + params + "}";
    }
}
