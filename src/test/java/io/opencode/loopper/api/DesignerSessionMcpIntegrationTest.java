package io.opencode.loopper.api;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.LoopDraftStatus;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.DesignerMessageRow;
import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskArtifactRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.FakeOpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.service.DesignerSessionService;
import io.opencode.loopper.service.DesignerEventHub;
import io.opencode.loopper.service.LoopDraftService;
import io.opencode.loopper.service.ProjectService;
import io.opencode.loopper.service.TaskService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.opencode.model=opencode/deepseek-v4-flash-free", "loopper.monitor-delay=1h",
        "loopper.designer-monitor-delay=1h", "loopper.mcp.bearer-token=designer-mcp-test-token",
        "spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.name=opencode-loopper", "spring.ai.mcp.server.version=0.1.1",
        "spring.ai.mcp.server.annotation-scanner.enabled=false",
        "spring.ai.mcp.server.capabilities.resource=false", "spring.ai.mcp.server.capabilities.prompt=false", "spring.ai.mcp.server.capabilities.completion=false",
        "spring.ai.mcp.server.streamable-http.mcp-endpoint=/api/mcp-streamable", "spring.ai.mcp.server.streamable-http.disallow-delete=true"})
@AutoConfigureMockMvc
class DesignerSessionMcpIntegrationTest {
    private static final String TOKEN = "designer-mcp-test-token";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private Flyway flyway;
    @Autowired private ProjectService projects;
    @Autowired private DesignerSessionService designerSessions;
    @Autowired private DesignerEventHub designerEvents;
    @Autowired private LoopDraftService drafts;
    @Autowired private TaskService tasks;
    @Autowired private LoopperMapper mapper;
    @Autowired private OpenCodeClient openCode;
    @Autowired private ToolCallbackProvider loopperMcpToolCallbackProvider;
    @Autowired private ApplicationContext context;
    @Autowired private org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties springAiMcpProperties;
    @Autowired private org.springframework.core.env.Environment environment;
    @TempDir Path temp;

    @BeforeEach
    void resetDatabase() {
        flyway.clean();
        flyway.migrate();
        ((FakeOpenCodeClient) openCode).reset();
    }

    @Test
    void designerHandoffPersistsOnlyActualReadOnlyAssistantOutputAndReusesTheCompletedSession() throws Exception {
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        ProjectRow project = projects.create("designer-fixture", Files.createDirectory(temp.resolve("project")).toString());
        LoopDraftRow boundDraft = drafts.create(spec(project.id()));
        LoopSpec firstSpec = withGoal(spec(project.id()), "Preserve the verifier list with a synchronized LoopSpec");
        fake.setDesignerOutput(designerOutput("# Actual assistant plan\n\nPreserve the verifier list.", firstSpec));

        MvcResult created = mvc.perform(post("/api/designer-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"" + project.id() + "\",\"draftId\":\"" + boundDraft.id() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessMode").value("READ_ONLY"))
                .andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.draft.id").value(boundDraft.id()))
                .andExpect(jsonPath("$.messages[0].role").value("SYSTEM"))
                .andReturn();
        String sessionId = body(created).path("id").asText();
        assertThat(designerSessions.messages(sessionId)).singleElement().satisfies(message -> {
            assertThat(message.designerSessionId()).isEqualTo(sessionId);
            assertThat(message.role()).isEqualTo("SYSTEM");
        });

        mvc.perform(post("/api/designer-sessions/{id}/messages", sessionId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Please preserve the verifier list\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.persistedMessages[0].role").value("USER"))
                .andExpect(jsonPath("$.persistedMessages[1].role").value("SYSTEM"))
                .andExpect(jsonPath("$.notice").value(org.hamcrest.Matchers.containsString("actual OpenCode assistant text")));

        DesignerSessionRow dispatched = designerSessions.get(sessionId);
        assertThat(dispatched.externalSessionId()).isNotBlank();
        assertThat(dispatched.externalSessionState()).isEqualTo("RUNNING");
        assertThat(fake.isReadOnlySession(dispatched.externalSessionId())).isTrue();
        assertThat(fake.modelForSession(dispatched.externalSessionId()))
                .isEqualTo(new OpenCodeClient.OpenCodeModel("opencode", "deepseek-v4-flash-free", null));
        assertThat(fake.promptForSession(dispatched.externalSessionId()))
                .contains("well-structured Markdown document", "fenced `mermaid` diagram", "Never draw flows with ASCII art")
                .contains("Prefer 2 to 6 dependency-ordered stages", "single stage only when the requested change is genuinely atomic")
                .contains("rows map one-to-one and in the same order to machine `stages`")
                .contains("Every stage must leave the project in a coherent, safe-to-stop state")
                .contains("Do not defer all tests or functional validation to the final stage")
                .contains("final full-regression verifier may supplement but never replace")
                .contains("JSON field is exactly `command`", "Never rename this JSON field to `argv`, `args`, or `cmd`")
                .contains("Never assume Maven Wrapper is present", "use `./mvnw` only when an executable `mvnw` is checked in")
                .doesNotContain("[\"./mvnw\", \"clean\", \"compile\"]")
                .contains("default to Simplified Chinese", "protocol enum values")
                .contains("LOOPSPEC_JSON_START", boundDraft.id(), project.id())
                .contains("Please preserve the verifier list");
        assertThat(designerSessions.messages(sessionId)).noneMatch(message -> message.role().equals("ASSISTANT"));
        assertThatThrownBy(() -> designerSessions.appendUserMessage(sessionId, "Unsafe concurrent prompt"))
                .hasMessageContaining("still processing");

        designerSessions.pollActiveHandoffs();
        DesignerSessionRow completed = designerSessions.get(sessionId);
        assertThat(completed.state()).isEqualTo("COMPLETED");
        assertThat(completed.externalSessionState()).isEqualTo("COMPLETED");
        assertThat(designerSessions.messages(sessionId)).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("ASSISTANT");
            assertThat(message.content()).isEqualTo("# Actual assistant plan\n\nPreserve the verifier list.");
            assertThat(message.deliveryState()).isEqualTo("PERSISTED");
        });
        assertThat(drafts.get(boundDraft.id()).version()).isEqualTo(1);
        assertThat(drafts.spec(drafts.get(boundDraft.id()))).isEqualTo(firstSpec);

        String externalSessionId = completed.externalSessionId();
        LoopSpec secondSpec = withGoal(firstSpec, "Refined synchronized LoopSpec");
        fake.setDesignerOutput(designerOutput("## Actual second-turn assistant plan", secondSpec));
        designerSessions.appendUserMessage(sessionId, "Refine the first plan");
        assertThat(designerSessions.get(sessionId).externalSessionId()).isEqualTo(externalSessionId);
        designerSessions.pollActiveHandoffs();
        assertThat(designerSessions.messages(sessionId).stream().filter(message -> message.role().equals("ASSISTANT")).map(message -> message.content()).toList())
                .containsExactly("# Actual assistant plan\n\nPreserve the verifier list.", "## Actual second-turn assistant plan");
        assertThat(drafts.spec(drafts.get(boundDraft.id()))).isEqualTo(secondSpec);
    }

    @Test
    void taskHistoryReturnsThePersistedDesignerConversationAndConfirmedLoopSpec() throws Exception {
        ProjectRow project = projects.create("history-fixture", Files.createDirectory(temp.resolve("history-project")).toString());
        LoopDraftRow draft = drafts.create(spec(project.id()));
        DesignerSessionRow designer = designerSessions.create(project.id(), draft.id(), "Design a durable history view");
        TaskRow task = drafts.confirm(draft.id(), "Durable design history");

        mvc.perform(get("/api/tasks/{id}", task.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasDesignHistory").value(true));

        mvc.perform(get("/api/tasks/{id}/design-history", task.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(task.id()))
                .andExpect(jsonPath("$.taskTitle").value("Durable design history"))
                .andExpect(jsonPath("$.draft.id").value(draft.id()))
                .andExpect(jsonPath("$.draft.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.draft.spec.goal").value(spec(project.id()).goal()))
                .andExpect(jsonPath("$.designerSession.id").value(designer.id()))
                .andExpect(jsonPath("$.designerSession.accessMode").value("READ_ONLY"))
                .andExpect(jsonPath("$.designerSession.messages[*].content")
                        .value(org.hamcrest.Matchers.hasItem("Design a durable history view")));
    }

    @Test
    void confirmedDesignerMarkdownIsFrozenAndSentToEveryExecutionAgentAttempt() throws Exception {
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        ProjectRow project = projects.create("frozen-design-context", gitProject());
        LoopDraftRow draft = drafts.create(spec(project.id()));
        String snapshotOnlyTail = "SNAPSHOT_ONLY_TAIL";
        String confirmedMarkdown = """
                # 已确认的 ServerAop 设计

                - 复用现有测试基类与 Mock 约定。
                - 不修改生产代码，失败时保留诊断证据。
                """.trim() + "\n\n" + "x".repeat(12_100) + snapshotOnlyTail;
        fake.setDesignerOutput(designerOutput(confirmedMarkdown, spec(project.id())));
        DesignerSessionRow designer = designerSessions.create(project.id(), draft.id(), "设计 ServerAop 单元测试");
        designerSessions.pollActiveHandoffs();

        TaskRow task = drafts.confirm(draft.id(), "执行已确认设计");

        TaskArtifactRow snapshot = tasks.artifacts(task.id()).stream()
                .filter(artifact -> "DESIGN_CONTEXT".equals(artifact.kind())).findFirst().orElseThrow();
        assertThat(snapshot.name()).isEqualTo("confirmed-designer-design.md");
        assertThat(snapshot.contentType()).isEqualTo("text/markdown");
        assertThat(snapshot.content()).isEqualTo(confirmedMarkdown);
        assertThat(snapshot.metadataJson()).contains(draft.id(), designer.id());

        // A later conversation record must not rewrite the Task's confirmation-time snapshot.
        mapper.insertDesignerMessage(new DesignerMessageRow("later-design-message", designer.id(),
                mapper.nextDesignerMessageOrdinal(designer.id()), "ASSISTANT", "## 未确认的后续设计", "PERSISTED",
                "2026-08-07T00:00:00Z"));

        tasks.start(task.id());
        ExecutionSessionRow firstSession = mapper.activeSessions(task.id()).getFirst();
        String firstPrompt = fake.promptForSession(firstSession.externalSessionId());
        assertThat(firstPrompt)
                .contains("Confirmed Designer design snapshot", "BEGIN CONFIRMED DESIGN", confirmedMarkdown.substring(0, 200))
                .contains("structured LoopSpec and Verifier contract are authoritative")
                .contains("complete snapshot remains persisted on the Task")
                .doesNotContain(snapshotOnlyTail)
                .doesNotContain("未确认的后续设计");

        var firstAttempt = tasks.attempts(task.id()).getFirst();
        tasks.sessionFailed(task.id(), firstAttempt.id(), "NETWORK", "retry with frozen context");
        ExecutionSessionRow retrySession = mapper.activeSessions(task.id()).getFirst();
        assertThat(fake.promptForSession(retrySession.externalSessionId()))
                .contains(confirmedMarkdown.substring(0, 200))
                .doesNotContain(snapshotOnlyTail)
                .doesNotContain("未确认的后续设计");
        assertThat(tasks.artifacts(task.id()).stream().filter(artifact -> "DESIGN_CONTEXT".equals(artifact.kind())))
                .singleElement().extracting(TaskArtifactRow::content).isEqualTo(confirmedMarkdown);
    }

    @Test
    void designerQuestionsAreExposedAndCanBeAnsweredOrRejectedBeforeGenerationContinues() throws Exception {
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        ProjectRow project = projects.create("designer-question",
                Files.createDirectory(temp.resolve("question-project")).toString());
        LoopDraftRow boundDraft = drafts.create(spec(project.id()));
        DesignerSessionRow designer = designerSessions.create(project.id(), boundDraft.id(), "Design another chain");
        DesignerSessionRow running = designerSessions.get(designer.id());
        String externalSessionId = running.externalSessionId();
        OpenCodeClient.PendingQuestion pending = new OpenCodeClient.PendingQuestion("question-1", externalSessionId,
                List.of(
                        new OpenCodeClient.QuestionPrompt("Which scope?", "Scope", List.of(
                                new OpenCodeClient.QuestionOption("New chain", "Add a new business chain"),
                                new OpenCodeClient.QuestionOption("Tests only", "Only test existing chains")), false, false),
                        new OpenCodeClient.QuestionPrompt("Which domain?", "Domain", List.of(
                                new OpenCodeClient.QuestionOption("XML", "Stay in the current domain"),
                                new OpenCodeClient.QuestionOption("Payment", "Use an independent domain")), false, true)));
        fake.setPendingQuestion(externalSessionId, pending);

        designerSessions.pollActiveHandoffs();

        assertThat(designerSessions.get(designer.id()).state()).isEqualTo("RUNNING");
        assertThat(designerSessions.get(designer.id()).externalSessionState()).isEqualTo("WAITING_INPUT");
        mvc.perform(get("/api/designer-sessions/{id}", designer.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingQuestions[0].id").value("question-1"))
                .andExpect(jsonPath("$.pendingQuestions[0].questions[0].options[0].label").value("New chain"))
                .andExpect(jsonPath("$.pendingQuestions[0].questions[1].custom").value(true));

        mvc.perform(post("/api/designer-sessions/{id}/questions/{questionId}/reply", designer.id(), "question-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[[\"New chain\",\"Tests only\"],[\"XML\"]]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("QUESTION_ANSWER_MULTIPLE_FORBIDDEN"));

        mvc.perform(post("/api/designer-sessions/{id}/questions/{questionId}/reply", designer.id(), "question-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[[\"New chain\"],[\"由 Designer 决定\"]]}"))
                .andExpect(status().isNoContent());
        assertThat(fake.answersForQuestion("question-1"))
                .containsExactly(List.of("New chain"), List.of("由 Designer 决定"));
        assertThat(designerSessions.get(designer.id()).externalSessionState()).isEqualTo("RUNNING");
        mvc.perform(get("/api/designer-sessions/{id}", designer.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingQuestions").isEmpty());

        OpenCodeClient.PendingQuestion rejected = new OpenCodeClient.PendingQuestion("question-2", externalSessionId,
                List.of(new OpenCodeClient.QuestionPrompt("Continue?", "Confirm", List.of(), false, true)));
        fake.setPendingQuestion(externalSessionId, rejected);
        mvc.perform(post("/api/designer-sessions/{id}/questions/{questionId}/reject", designer.id(), "question-2"))
                .andExpect(status().isNoContent());
        assertThat(fake.wasQuestionRejected("question-2")).isTrue();
    }

    @Test
    void designerAcceptsArgvAsAWeakModelAliasAndPersistsCanonicalCommand() throws Exception {
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        Path root = Files.createDirectory(temp.resolve("argv-alias-project"));
        Path wrapper = Files.writeString(root.resolve("mvnw"), "#!/bin/sh\nexit 0\n");
        wrapper.toFile().setExecutable(true);
        ProjectRow project = projects.create("designer-argv-alias", root.toString());
        LoopDraftRow boundDraft = drafts.create(spec(project.id()));
        LoopSpec processSpec = new LoopSpec("v1", project.id(), "Compile and verify", "",
                List.of(new LoopSpec.StageSpec("Compile", List.of("src/**"), List.of(), List.of("classes"),
                        List.of(new LoopSpec.VerifierSpec("PROCESS", List.of("./mvnw", "-q", "compile"),
                                null, null, List.of(), List.of(), null, "PASS")))),
                LoopSpec.Limits.defaults(), null, null, null);
        String weakModelJson = json.writeValueAsString(processSpec).replace("\"command\":", "\"argv\":");
        fake.setDesignerOutput("## Compile plan\n\n<!-- LOOPSPEC_JSON_START -->\n```json\n"
                + weakModelJson + "\n```\n<!-- LOOPSPEC_JSON_END -->");
        DesignerSessionRow designer = designerSessions.create(project.id(), boundDraft.id(), "Generate a plan");

        designerSessions.pollActiveHandoffs();

        DesignerSessionRow completed = designerSessions.get(designer.id());
        assertThat(completed.state()).isEqualTo("COMPLETED");
        assertThat(completed.externalSessionState()).isEqualTo("COMPLETED");
        LoopSpec synchronizedSpec = drafts.spec(drafts.get(boundDraft.id()));
        assertThat(synchronizedSpec.stages().getFirst().verifiers().getFirst().command())
                .containsExactly("./mvnw", "-q", "compile");
        String persistedJson = drafts.get(boundDraft.id()).specJson();
        assertThat(persistedJson).contains("\"command\":[\"./mvnw\",\"-q\",\"compile\"]")
                .doesNotContain("\"argv\"");
    }

    @Test
    void unavailableOrPromptFailureStaysPendingOrSessionScopedWithoutMutatingTasks() throws Exception {
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        ProjectRow project = projects.create("designer-failure", Files.createDirectory(temp.resolve("failure-project")).toString());
        DesignerSessionRow unavailable = designerSessions.create(project.id(), null);
        fake.setHealthy(false);
        designerSessions.appendUserMessage(unavailable.id(), "Plan safely while offline");
        DesignerSessionRow pending = designerSessions.get(unavailable.id());
        assertThat(pending.state()).isEqualTo("PENDING_HANDOFF");
        assertThat(pending.externalSessionId()).isNull();
        assertThat(pending.externalSessionState()).isEqualTo("UNAVAILABLE");
        assertThat(designerSessions.messages(unavailable.id())).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("SYSTEM");
            assertThat(message.content()).contains("SYSTEM_ERROR[SESSION]");
        });

        fake.setHealthy(true);
        fake.failNextPrompts(1);
        DesignerSessionRow broken = designerSessions.create(project.id(), null);
        designerSessions.appendUserMessage(broken.id(), "Trigger a deterministic transport failure");
        DesignerSessionRow failed = designerSessions.get(broken.id());
        assertThat(failed.state()).isEqualTo("SESSION_ERROR");
        assertThat(failed.externalSessionId()).isNotBlank();
        assertThat(designerSessions.messages(broken.id())).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("SYSTEM");
            assertThat(message.deliveryState()).isEqualTo("SESSION_ERROR");
            assertThat(message.content()).contains("OPENCODE_PROMPT_FAILED", "no task was changed");
        });

        DesignerSessionRow retrying = designerSessions.create(project.id(), null);
        designerSessions.appendUserMessage(retrying.id(), "Provider reports an explicit retry state");
        DesignerSessionRow dispatched = designerSessions.get(retrying.id());
        fake.setSessionState(dispatched.externalSessionId(), "RETRY");
        designerSessions.pollActiveHandoffs();
        assertThat(designerSessions.get(retrying.id()).state()).isEqualTo("SESSION_ERROR");
        assertThat(designerSessions.messages(retrying.id())).anySatisfy(message -> {
            assertThat(message.deliveryState()).isEqualTo("SESSION_ERROR");
            assertThat(message.content()).contains("OPENCODE_DESIGNER_RETRY", "no task was changed");
        });
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void designerPublishesLiveMarkdownAndImmediateProviderErrors() throws Exception {
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        ProjectRow project = projects.create("designer-live", Files.createDirectory(temp.resolve("live-project")).toString());
        LoopDraftRow boundDraft = drafts.create(spec(project.id()));
        fake.setDesignerOutput(designerOutput("## Live plan\n\nFirst visible chunk.", spec(project.id())));
        DesignerSessionRow designer = designerSessions.create(project.id(), boundDraft.id(), "Stream the plan");
        fake.setSessionState(designer.externalSessionId(), "RUNNING");

        designerSessions.pollActiveHandoffs();

        DesignerEventHub.DesignerEvent partial = designerEvents.latest(designer.id());
        assertThat(partial.type()).isEqualTo("PARTIAL");
        assertThat(partial.runtimeConnected()).isTrue();
        assertThat(partial.content()).contains("First visible chunk").doesNotContain("LOOPSPEC_JSON_START");

        fake.setSessionStatus(designer.externalSessionId(), "TIMED_OUT", "provider request timed out");
        designerSessions.pollActiveHandoffs();

        DesignerEventHub.DesignerEvent failed = designerEvents.latest(designer.id());
        assertThat(failed.type()).isEqualTo("ERROR");
        assertThat(failed.detail()).contains("OPENCODE_DESIGNER_TIMED_OUT", "provider request timed out");
        mvc.perform(get("/api/designer-sessions/{id}/events", designer.id()).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());
    }

    @Test
    void rejectedDesignerLoopSpecIsAutomaticallyCorrectedWithoutMutatingATask() throws Exception {
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        ProjectRow project = projects.create("designer-invalid-spec",
                Files.createDirectory(temp.resolve("invalid-spec-project")).toString());
        LoopDraftRow boundDraft = drafts.create(spec(project.id()));
        LoopSpec gitDiffOnly = new LoopSpec("v1", project.id(), "Invalid acceptance contract", "",
                List.of(new LoopSpec.StageSpec("Change files", List.of("src/**"), List.of(), List.of("change"),
                        List.of(new LoopSpec.VerifierSpec("GIT_DIFF", null, null, true,
                                List.of("src/**"), List.of(), true)))),
                LoopSpec.Limits.defaults(), null, null, "Continue");
        fake.setDesignerOutput(designerOutput("## Proposed plan", gitDiffOnly));
        DesignerSessionRow designer = designerSessions.create(project.id(), boundDraft.id(), "Generate a plan");

        designerSessions.pollActiveHandoffs();

        DesignerSessionRow repairing = designerSessions.get(designer.id());
        assertThat(repairing.state()).isEqualTo("RUNNING");
        assertThat(repairing.externalSessionState()).isEqualTo("REPAIRING_LOOPSPEC_1");
        assertThat(drafts.get(boundDraft.id()).version()).isZero();
        assertThat(designerSessions.messages(designer.id())).anySatisfy(message -> {
            assertThat(message.deliveryState()).isEqualTo("AUTO_REPAIR");
            assertThat(message.content()).contains("LOOPSPEC_AUTO_REPAIR 1/2", "不会生成代码", "不会生成代码、修改项目或创建 Task");
        });
        assertThat(fake.promptForSession(repairing.externalSessionId()))
                .contains("protocol-repair turn only", "Do not inspect more files", "GIT_DIFF only checks change scope")
                .contains("prefer 2 to 6 dependency-ordered stages", "split by independently deliverable behavior")
                .contains("Every stage must own functional acceptance", "Do not defer all functional validation to the final stage")
                .contains("final full-regression verifier may supplement, but never replace")
                .contains("never synchronized", "LOOPSPEC_JSON_START");
        assertThat(mapper.listTasks()).isEmpty();

        LoopSpec corrected = new LoopSpec("v1", project.id(), "Generate ServerAop unit tests", "Use existing test conventions",
                List.of(new LoopSpec.StageSpec("Add and verify tests", List.of("src/test/**"), List.of(), List.of("ServerAop tests"),
                        List.of(new LoopSpec.VerifierSpec("PROCESS", List.of("mvn", "-q", "test", "-Dtest=ServerAopTest"),
                                null, null, List.of(), List.of(), null, null)))),
                LoopSpec.Limits.defaults(), null, null, "Continue from verifier evidence");
        fake.setDesignerOutput(designerOutput("## Corrected test plan", corrected));
        designerSessions.pollActiveHandoffs();

        DesignerSessionRow completed = designerSessions.get(designer.id());
        assertThat(completed.state()).isEqualTo("COMPLETED");
        assertThat(drafts.spec(drafts.get(boundDraft.id()))).isEqualTo(corrected);
        assertThat(designerSessions.messages(designer.id())).noneMatch(message -> message.deliveryState().equals("SESSION_ERROR"));
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void missingMavenWrapperIsReturnedToDesignerForAutomaticCorrection() throws Exception {
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        Path root = Files.createDirectory(temp.resolve("project-without-wrapper"));
        Files.writeString(root.resolve("pom.xml"), "<project />");
        ProjectRow project = projects.create("designer-without-wrapper", root.toString());
        LoopDraftRow boundDraft = drafts.create(spec(project.id()));
        LoopSpec wrapperSpec = new LoopSpec("v1", project.id(), "Compile", "",
                List.of(new LoopSpec.StageSpec("Compile", List.of("src/**"), List.of(), List.of("classes"),
                        List.of(new LoopSpec.VerifierSpec("PROCESS", List.of("./mvnw", "-q", "compile"),
                                null, null, List.of(), List.of(), null, null)))),
                LoopSpec.Limits.defaults(), null, null, null);
        fake.setDesignerOutput(designerOutput("## Compile plan", wrapperSpec));
        DesignerSessionRow designer = designerSessions.create(project.id(), boundDraft.id(), "Generate a plan");

        designerSessions.pollActiveHandoffs();

        DesignerSessionRow repairing = designerSessions.get(designer.id());
        assertThat(repairing.externalSessionState()).isEqualTo("REPAIRING_LOOPSPEC_1");
        assertThat(fake.promptForSession(repairing.externalSessionId()))
                .contains("./mvnw is not present in the registered project root")
                .contains("Maven Wrapper is optional")
                .contains("Never assume Maven Wrapper is present");
        assertThat(drafts.get(boundDraft.id()).version()).isZero();
        assertThat(mapper.listTasks()).isEmpty();

        LoopSpec corrected = new LoopSpec("v1", project.id(), "Compile", "",
                List.of(new LoopSpec.StageSpec("Compile", List.of("src/**"), List.of(), List.of("classes"),
                        List.of(new LoopSpec.VerifierSpec("PROCESS", List.of("mvn", "-q", "compile"),
                                null, null, List.of(), List.of(), null, null)))),
                LoopSpec.Limits.defaults(), null, null, null);
        fake.setDesignerOutput(designerOutput("## Corrected compile plan", corrected));
        designerSessions.pollActiveHandoffs();

        assertThat(designerSessions.get(designer.id()).state()).isEqualTo("COMPLETED");
        assertThat(drafts.spec(drafts.get(boundDraft.id()))).isEqualTo(corrected);
    }

    @Test
    void parseableCollapsedMavenArgumentsAreNormalizedWithoutDesignerRetry() throws Exception {
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        Path root = Files.createDirectory(temp.resolve("project-with-collapsed-maven-arguments"));
        Files.writeString(root.resolve("pom.xml"), "<project />");
        ProjectRow project = projects.create("designer-collapsed-maven-arguments", root.toString());
        LoopDraftRow boundDraft = drafts.create(spec(project.id()));
        LoopSpec invalid = new LoopSpec("v1", project.id(), "Run focused test", "",
                List.of(new LoopSpec.StageSpec("Test", List.of("src/**"), List.of(), List.of("test passes"),
                        List.of(new LoopSpec.VerifierSpec("PROCESS",
                                List.of("mvn", "test -Dtest=Base64FieldTest -pl upfs-common"),
                                null, null, List.of(), List.of(), null, null)))),
                LoopSpec.Limits.defaults(), null, null, null);
        fake.setDesignerOutput(designerOutput("## Test plan", invalid));
        DesignerSessionRow designer = designerSessions.create(project.id(), boundDraft.id(), "Generate a plan");

        designerSessions.pollActiveHandoffs();

        DesignerSessionRow completed = designerSessions.get(designer.id());
        assertThat(completed.state()).isEqualTo("COMPLETED");
        assertThat(completed.externalSessionState()).isEqualTo("COMPLETED");
        assertThat(fake.promptCalls()).isEqualTo(1);
        assertThat(drafts.spec(drafts.get(boundDraft.id())).stages().getFirst()
                .verifiers().getFirst().command())
                .containsExactly("mvn", "test", "-Dtest=Base64FieldTest", "-pl", "upfs-common");
        assertThat(designerSessions.messages(designer.id()))
                .noneMatch(message -> message.deliveryState().equals("AUTO_REPAIR"))
                .noneMatch(message -> message.deliveryState().equals("SESSION_ERROR"));
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void unparseableCollapsedMavenArgumentsTriggerDesignerRetry() throws Exception {
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        Path root = Files.createDirectory(temp.resolve("project-with-unparseable-maven-arguments"));
        Files.writeString(root.resolve("pom.xml"), "<project />");
        ProjectRow project = projects.create("designer-unparseable-maven-arguments", root.toString());
        LoopDraftRow boundDraft = drafts.create(spec(project.id()));
        LoopSpec invalid = new LoopSpec("v1", project.id(), "Run focused test", "",
                List.of(new LoopSpec.StageSpec("Test", List.of("src/**"), List.of(), List.of("test passes"),
                        List.of(new LoopSpec.VerifierSpec("PROCESS",
                                List.of("mvn", "test -Dtest='Base64FieldTest"),
                                null, null, List.of(), List.of(), null, null)))),
                LoopSpec.Limits.defaults(), null, null, null);
        fake.setDesignerOutput(designerOutput("## Test plan", invalid));
        DesignerSessionRow designer = designerSessions.create(project.id(), boundDraft.id(), "Generate a plan");

        designerSessions.pollActiveHandoffs();

        DesignerSessionRow repairing = designerSessions.get(designer.id());
        assertThat(repairing.state()).isEqualTo("RUNNING");
        assertThat(repairing.externalSessionState()).isEqualTo("REPAIRING_LOOPSPEC_1");
        assertThat(fake.promptForSession(repairing.externalSessionId()))
                .contains("stages[0].verifiers[0].command[1]", "cannot be parsed safely", "unclosed quote");
        assertThat(drafts.get(boundDraft.id()).version()).isZero();
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void repeatedInvalidDesignerOutputStopsAfterTwoAutomaticRepairs() throws Exception {
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        ProjectRow project = projects.create("designer-repair-limit",
                Files.createDirectory(temp.resolve("repair-limit-project")).toString());
        LoopDraftRow boundDraft = drafts.create(spec(project.id()));
        LoopSpec gitDiffOnly = new LoopSpec("v1", project.id(), "Still invalid", "",
                List.of(new LoopSpec.StageSpec("Change files", List.of("src/**"), List.of(), List.of("change"),
                        List.of(new LoopSpec.VerifierSpec("GIT_DIFF", null, null, true,
                                List.of("src/**"), List.of(), true)))),
                LoopSpec.Limits.defaults(), null, null, "Continue");
        fake.setDesignerOutput(designerOutput("## Invalid plan", gitDiffOnly));
        DesignerSessionRow designer = designerSessions.create(project.id(), boundDraft.id(), "Generate a plan");

        designerSessions.pollActiveHandoffs();
        designerSessions.pollActiveHandoffs();
        designerSessions.pollActiveHandoffs();

        DesignerSessionRow failed = designerSessions.get(designer.id());
        assertThat(failed.state()).isEqualTo("SESSION_ERROR");
        assertThat(failed.externalSessionState()).isEqualTo("FAILED");
        assertThat(fake.promptCalls()).isEqualTo(3);
        assertThat(drafts.get(boundDraft.id()).version()).isZero();
        assertThat(designerSessions.messages(designer.id()).stream()
                .filter(message -> "AUTO_REPAIR".equals(message.deliveryState()))).hasSize(2);
        assertThat(designerSessions.messages(designer.id())).anySatisfy(message -> {
            assertThat(message.deliveryState()).isEqualTo("SESSION_ERROR");
            assertThat(message.content()).contains("LOOPSPEC_SYNC_FAILED", "GIT_DIFF only checks change scope");
        });

        long messageCount = designerSessions.messages(designer.id()).size();
        designerSessions.pollActiveHandoffs();
        assertThat(designerSessions.messages(designer.id())).hasSize((int) messageCount);
    }

    @Test
    void mcpUsesBearerAndPreservesFullSpecWhileEnforcingHumanConfirmationAndIdempotency() throws Exception {
        ProjectRow project = projects.create("mcp-fixture", gitProject());
        LoopDraftRow boundDraft = drafts.create(withGoal(spec(project.id()), "Initial placeholder"));
        DesignerSessionRow designer = designerSessions.create(project.id(), boundDraft.id(), null);

        mvc.perform(post("/api/mcp").contentType(MediaType.APPLICATION_JSON).content(rpc(1, "initialize", "{\"protocolVersion\":\"2025-03-26\"}")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(-32001));
        mvc.perform(mcp(rpc(1, "initialize", "{\"protocolVersion\":\"2025-03-26\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-03-26"))
                .andExpect(jsonPath("$.result.serverInfo.version").value("0.1.1"));
        MvcResult list = mvc.perform(mcp(rpc(2, "tools/list", "{}"))).andExpect(status().isOk()).andReturn();
        assertThat(list.getResponse().getContentAsString())
                .contains("get_project_context", "propose_loop_spec", "validate_loop_spec", "create_task", "start_task", "get_task_status")
                .contains("designerSessionId", "additionalProperties");

        LoopSpec fullSpec = spec(project.id());
        String proposalArgs = "{\"designerSessionId\":\"" + designer.id() + "\",\"projectId\":\"" + project.id()
                + "\",\"spec\":" + json.writeValueAsString(fullSpec) + "}";
        MvcResult proposal = mvc.perform(mcp(rpc(3, "tools/call", "{\"name\":\"propose_loop_spec\",\"arguments\":" + proposalArgs + "}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.structuredContent.spec.stages[0].allowedPaths[0]").value("src/**"))
                .andExpect(jsonPath("$.result.structuredContent.spec.stages[0].verifiers[0].type").value("FILE_EXISTS"))
                .andReturn();
        String proposedDraftId = body(proposal).at("/result/structuredContent/draft/id").asText();
        assertThat(proposedDraftId).isEqualTo(boundDraft.id());
        LoopDraftRow proposedDraft = drafts.get(proposedDraftId);
        assertThat(proposedDraft.status()).isEqualTo(LoopDraftStatus.DRAFT_READY.name());
        assertThat(drafts.spec(proposedDraft)).isEqualTo(fullSpec);

        mvc.perform(mcp(rpc(4, "tools/call", "{\"name\":\"validate_loop_spec\",\"arguments\":{\"draftId\":\""
                + proposedDraftId + "\",\"version\":1}}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.structuredContent.valid").value(true));
        mvc.perform(mcp(rpc(5, "tools/call", "{\"name\":\"create_task\",\"arguments\":{\"draftId\":\""
                + proposedDraftId + "\"}}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content[0].text").value(org.hamcrest.Matchers.containsString("DRAFT_NOT_CONFIRMED")));

        // This simulates a separately human-confirmed draft. MCP must create the task only now,
        // and a repeated call must return the same isolated task rather than duplicate it.
        LoopDraftRow confirmed = new LoopDraftRow(proposedDraft.id(), proposedDraft.projectId(), proposedDraft.goal(), proposedDraft.specJson(),
                LoopDraftStatus.CONFIRMED.name(), proposedDraft.createdAt(), proposedDraft.updatedAt(), proposedDraft.version());
        assertThat(mapper.updateDraft(confirmed)).isEqualTo(1);
        MvcResult first = mvc.perform(mcp(rpc(6, "tools/call", "{\"name\":\"create_task\",\"arguments\":{\"draftId\":\""
                + proposedDraftId + "\"}}"))).andExpect(status().isOk()).andExpect(jsonPath("$.result.isError").value(false)).andReturn();
        MvcResult repeated = mvc.perform(mcp(rpc(7, "tools/call", "{\"name\":\"create_task\",\"arguments\":{\"draftId\":\""
                + proposedDraftId + "\"}}"))).andExpect(status().isOk()).andExpect(jsonPath("$.result.isError").value(false)).andReturn();
        String taskId = body(first).at("/result/structuredContent/id").asText();
        assertThat(body(repeated).at("/result/structuredContent/id").asText()).isEqualTo(taskId);

        mvc.perform(mcp(rpc(8, "tools/call", "{\"name\":\"start_task\",\"arguments\":{\"taskId\":\"" + taskId + "\"}}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.isError").value(false));
        mvc.perform(mcp(rpc(9, "tools/call", "{\"name\":\"get_task_status\",\"arguments\":{\"taskId\":\"" + taskId + "\"}}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.structuredContent.task.id").value(taskId));
    }

    @Test
    void springAiStreamableServerRegistersTheSixToolsAndRequiresBearer() throws Exception {
        ProjectRow project = projects.create("streamable-fixture", Files.createDirectory(temp.resolve("streamable-project")).toString());
        assertThat(java.util.Arrays.stream(loopperMcpToolCallbackProvider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name()).toList())
                .containsExactlyInAnyOrder("get_project_context", "propose_loop_spec", "validate_loop_spec", "create_task", "start_task", "get_task_status");
        String proposalSchema = java.util.Arrays.stream(loopperMcpToolCallbackProvider.getToolCallbacks())
                .filter(callback -> callback.getToolDefinition().name().equals("propose_loop_spec"))
                .findFirst().orElseThrow().getToolDefinition().inputSchema();
        assertThat(proposalSchema).contains("designerSessionId", "projectId", "spec", "stages", "verifiers");
        assertThat(environment.getProperty("spring.ai.mcp.server.protocol")).isEqualTo("STREAMABLE");
        assertThat(springAiMcpProperties.getProtocol().name()).isEqualTo("STREAMABLE");
        assertThat(springAiMcpProperties.getCapabilities().isTool()).isTrue();
        assertThat(springAiMcpProperties.getCapabilities().isResource()).isFalse();
        assertThat(springAiMcpProperties.getCapabilities().isPrompt()).isFalse();
        assertThat(springAiMcpProperties.getCapabilities().isCompletion()).isFalse();
        assertThat(context.getBeanNamesForType(org.springframework.web.servlet.function.RouterFunction.class))
                .as("Spring AI Streamable RouterFunction is registered")
                .contains("webMvcStreamableServerRouterFunction");

        String initialize = rpc(10, "initialize", "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"loopper-test\",\"version\":\"1\"}}");
        mvc.perform(post("/api/mcp-streamable").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM).content(initialize))
                .andExpect(status().isUnauthorized());
        MvcResult initialized = mvc.perform(streamable(initialize, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.serverInfo.name").value("opencode-loopper"))
                .andExpect(jsonPath("$.result.serverInfo.version").value("0.1.1"))
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-03-26"))
                .andReturn();
        String sessionId = initialized.getResponse().getHeader("Mcp-Session-Id");
        assertThat(sessionId).isNotBlank();
        JsonNode capabilities = body(initialized).path("result").path("capabilities");
        assertThat(capabilities.has("tools")).isTrue();
        assertThat(capabilities.has("resources")).isFalse();
        assertThat(capabilities.has("prompts")).isFalse();
        assertThat(capabilities.has("completions")).isFalse();

        mvc.perform(streamable("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}", sessionId))
                .andExpect(status().isAccepted());
        MvcResult listed = mvc.perform(streamable(rpc(11, "tools/list", "{}"), sessionId))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(listed)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("get_project_context"),
                        org.hamcrest.Matchers.containsString("propose_loop_spec"),
                        org.hamcrest.Matchers.containsString("create_task"))));
        String contextArguments = "{\"name\":\"get_project_context\",\"arguments\":{\"projectId\":\"" + project.id() + "\"}}";
        MvcResult contextResult = mvc.perform(streamable(rpc(12, "tools/call", contextArguments), sessionId))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(contextResult)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString(project.id()),
                        org.hamcrest.Matchers.containsString("READ_ONLY"))));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder mcp(String request) {
        return post("/api/mcp").header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON).content(request);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder streamable(String request, String sessionId) {
        var builder = post("/api/mcp-streamable").header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM).content(request);
        if (sessionId != null) builder.header("Mcp-Session-Id", sessionId);
        return builder;
    }

    private String rpc(int id, String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}";
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private LoopSpec spec(String projectId) {
        return new LoopSpec("v1", projectId, "Implement the validated designer plan", "Keep the worktree isolated",
                List.of(new LoopSpec.StageSpec("Implement the plan", List.of("src/**"), List.of(".env"), List.of("README.md"),
                        List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null, "README.md", null, List.of("README.md"), List.of(), true)))),
                new LoopSpec.Limits(2, 3, 2, 2, 3600L, 120L, 60L), new LoopSpec.ModelSpec("opencode", "deepseek", false),
                new LoopSpec.SessionPolicy(true, true), "Continue from verified evidence");
    }

    private LoopSpec withGoal(LoopSpec source, String goal) {
        return new LoopSpec(source.schemaVersion(), source.projectId(), goal, source.context(), source.stages(),
                source.limits(), source.model(), source.sessionPolicy(), source.nextAttemptPromptTemplate());
    }

    private String designerOutput(String markdown, LoopSpec spec) throws Exception {
        return markdown + "\n\n<!-- LOOPSPEC_JSON_START -->\n```json\n"
                + json.writeValueAsString(spec) + "\n```\n<!-- LOOPSPEC_JSON_END -->";
    }

    private String gitProject() throws Exception {
        Path root = Files.createDirectory(temp.resolve("git-project"));
        Files.writeString(root.resolve("README.md"), "fixture");
        run(root, "git", "init");
        run(root, "git", "config", "user.email", "test@example.invalid");
        run(root, "git", "config", "user.name", "test");
        run(root, "git", "add", "README.md");
        run(root, "git", "commit", "-m", "initial");
        return root.toString();
    }

    private void run(Path root, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new AssertionError(output);
    }
}
