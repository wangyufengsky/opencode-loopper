package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.roles.*;
import io.opencode.loopper.template.*;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h", "loopper.designer-monitor-delay=1h"})
class SourceDesignFlowIntegrationTest {
    @Autowired Flyway flyway;
    @Autowired SourceTemplateService service;
    @Autowired SourceTemplateAdmission admission;
    @Autowired SourceTemplateCoordinator coordinator;
    @Autowired SourceTemplateMapper runs;
    @Autowired SourceTemplateModelMapper models;
    @Autowired SourceModelStore store;
    @Autowired SourceModelExecution execution;
    @Autowired SourceModelReads reads;
    @Autowired SourceArtifactFiles artifacts;
    @Autowired SourceTemplateControl control;
    @Autowired MachineCandidateSubmission submissions;
    @Autowired ProjectService projects;
    @Autowired LoopperProperties properties;
    @Autowired OpenCodeClient client;
    @Autowired InternalMcpRuntimeAccess access;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired SourceTemplateReadService summaries;
    @Autowired RoleConfigurationService roleConfiguration;
    @Autowired RolePublishingService rolePublishing;
    @Autowired RoleReadService roleReads;
    @Autowired RoleArchive roleArchives;
    @TempDir Path temporary;
    private FakeOpenCodeClient fake;
    private String id;
    private SourceTemplateContract contract;
    @BeforeEach void prepare() throws Exception {
        flyway.clean(); flyway.migrate(); fake = (FakeOpenCodeClient) client; fake.reset();
        var credentials = new InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(credentials); access.connected(credentials.generation());
        fake.setManagedRuntime(credentials.generation(), credentials.serverName());
        for (var kind : MachineCandidateKind.values()) if (SourceTemplateProfiles.supports(kind))
            fake.holdProfileOpen(SourceTemplateProfiles.profile(kind), true);
        properties.getOpenCode().setModel("fake/test-model");
        Path source = Files.createDirectory(temporary.resolve("source"));
        Files.writeString(source.resolve("Service.java"), "class Service {\n  int value() { return 3; }\n}\n");
        var project = projects.create("源码设计试验", source.toString(), "fixture");
        var run = service.create(new SourceTemplateRequests.Create(UUID.randomUUID().toString(),
                "DETAILED_DESIGN_WRITING", "1", project.id(), ".", null, null, ""));
        id = run.id(); contract = json.readValue(run.contractJson(), SourceTemplateContract.class);
        admission.start(id, new SourceTemplateRequests.Command(UUID.randomUUID().toString(), run.version()));
        coordinator.advance(id); coordinator.advance(id);
    }
    @Test void completeFlowRequiresIndependentReadsThenPublishesExactPreviewAndDownloadBytes() throws Exception {
        var writer = awaitRole("SOURCE_DETAILED_DESIGN_V1", 0);
        var candidate = candidate(writer);
        complete(writer, candidate);
        var reviewer = awaitRole("SOURCE_DESIGN_REVIEW_V1", 0);
        var review = review(reviewer, false, false);
        assertThat(submit(reviewer, review).outcome()).isEqualTo(MachineCandidateOutcome.REJECTED);
        readDraft(reviewer);
        complete(reviewer, review);
        for (int i = 0; i < 5 && !run().state().equals("COMPLETED"); i++) coordinator.advance(id);
        assertThat(run().state()).isEqualTo("COMPLETED");
        assertThat(runs.coverageCounts(id)).containsExactly(new SourceTemplateMapper.Count("REVIEWED", 1));
        var listed = artifacts.list(id, null, 100).items();
        assertThat(listed).extracting(SourceArtifactMapper.Metadata::name).containsExactly("coverage.md", "module-1-service.md", "overview.md");
        for (var metadata : listed) {
            var item = artifacts.read(id, metadata.id());
            assertThat(Files.readString(artifacts.directory(id).resolve(item.name()))).isEqualTo(item.content());
        }
        var names = new ArrayList<String>();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(artifacts.bundle(id)))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
                var item = listed.stream().filter(a -> a.name().equals(names.getLast())).findFirst().orElseThrow();
                assertThat(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                        .isEqualTo(artifacts.read(id, item.id()).content());
            }
        }
        assertThat(names).containsExactlyElementsOf(listed.stream().map(SourceArtifactMapper.Metadata::name).toList());
    }
    @Test void lostCreateAndPromptAcknowledgementsDoNotDuplicateAndAcceptedNeedsTermination() {
        var row = models.current(id, "SOURCE_DETAILED_DESIGN_V1", 1).getFirst();
        row = store.require(row.id());
        assertThat(row.state()).isEqualTo("CREATING");
        client.createSession(json.readValue(row.creationPlanJson(), OpenCodeClient.SessionCreationPlan.class));
        execution.advance(row.id(), contract); execution.advance(row.id(), contract);
        final String modelId = row.id(); fake.failNextPrompts(1);
        assertThatThrownBy(() -> execution.advance(modelId, contract)).isInstanceOf(SessionFailure.class);
        row = execution.advance(modelId, contract);
        assertThat(fake.createReadOnlySessionCalls()).isEqualTo(1);
        assertThat(fake.promptCalls()).isEqualTo(1);
        assertThat(submit(row, candidate(row)).outcome()).isEqualTo(MachineCandidateOutcome.ACCEPTED);
        assertThat(execution.advance(row.id(), contract).state()).isEqualTo("RUNNING");
        fake.setSessionState(row.externalSessionId(), "COMPLETED");
        assertThat(execution.advance(row.id(), contract).state()).isEqualTo("VALIDATED");
    }
    @Test void importedSourceRoleChangesOnlyNewFrozenModelPromptAndMcpPolicy() throws Exception {
        var oldWriter = models.current(id, "SOURCE_DETAILED_DESIGN_V1", 1).getFirst();
        var oldPrompt = json.readValue(oldWriter.promptJson(), DocumentModelStore.FrozenPrompt.class).text();
        var oldPlan = json.readValue(oldWriter.creationPlanJson(), OpenCodeClient.SessionCreationPlan.class);
        String oldRevision = roleConfiguration.resolveFrozen(
                new RoleConfigurationService.OwnerRef("SOURCE_TEMPLATE_MODEL_RUN", oldWriter.id()),
                "SOURCE_DETAILED_DESIGN_NO_TOOLS").orElseThrow().revisionId();
        byte[] exported = roleReads.export("builtin.source-design-author", null);
        String replacement = "请逐份读取冻结源码，并以可核验的引用编写详细设计。\n";
        var imported = roleArchives.parse(rewriteRoleArchive(exported, (name, text) -> {
            if (name.equals("manifest.yaml")) return text.replace("builtin.source-design-author", "custom.source-design-author")
                    .replace("permissionMode: BASELINE", "permissionMode: INTERSECT")
                    .replace("mcpTools: []", "mcpTools: ['@loopper-internal/get_source_design_work']");
            return name.endsWith("source.design.author.instructions.md") ? replacement : text;
        }));
        assertThat(imported.manifest().roles().getFirst().permissionMode()).isEqualTo("INTERSECT");
        assertThat(imported.manifest().roles().getFirst().mcpTools())
                .containsExactly("@loopper-internal/get_source_design_work");
        var preview = rolePublishing.validate(imported);
        assertThat(preview.diagnostics()).isEmpty();
        rolePublishing.publish(imported, new RolePublishingService.PublishRequest(
                imported.sourceSha256(), "source-role-import-12345", preview.activations()));

        var created = service.create(new SourceTemplateRequests.Create(UUID.randomUUID().toString(),
                "DETAILED_DESIGN_WRITING", "1", run().projectId(), ".", null, null, ""));
        admission.start(created.id(), new SourceTemplateRequests.Command(UUID.randomUUID().toString(), created.version()));
        coordinator.advance(created.id()); coordinator.advance(created.id());
        var newWriter = models.current(created.id(), "SOURCE_DETAILED_DESIGN_V1", 1).getFirst();
        var newPrompt = json.readValue(newWriter.promptJson(), DocumentModelStore.FrozenPrompt.class).text();
        var newPlan = json.readValue(newWriter.creationPlanJson(), OpenCodeClient.SessionCreationPlan.class);
        String newRevision = roleConfiguration.resolveFrozen(
                new RoleConfigurationService.OwnerRef("SOURCE_TEMPLATE_MODEL_RUN", newWriter.id()),
                "SOURCE_DETAILED_DESIGN_NO_TOOLS").orElseThrow().revisionId();
        assertThat(newRevision).isNotEqualTo(oldRevision);
        assertThat(roleConfiguration.resolveFrozen(
                new RoleConfigurationService.OwnerRef("SOURCE_TEMPLATE_RUN", id),
                "SOURCE_DETAILED_DESIGN_NO_TOOLS").orElseThrow().revisionId()).isEqualTo(oldRevision);
        assertThat(oldPrompt).contains("根据当前实现编写详细设计").doesNotContain(replacement);
        assertThat(newPrompt).contains(replacement).contains("候选运行 ID：" + newWriter.id());
        assertThat(oldPlan.permissionPolicy()).contains(
                new OpenCodeClient.SessionPermissionRule(oldPlan.internalMcpServer()
                        + "_list_source_template_files", "*", "allow"));
        assertThat(newPlan.permissionPolicy()).doesNotContain(
                new OpenCodeClient.SessionPermissionRule(newPlan.internalMcpServer()
                        + "_list_source_template_files", "*", "allow"));
        assertThat(newPlan.permissionPolicy()).contains(
                new OpenCodeClient.SessionPermissionRule(newPlan.internalMcpServer()
                        + "_get_source_design_work", "*", "allow"),
                new OpenCodeClient.SessionPermissionRule(newPlan.internalMcpServer()
                        + "_submit_source_detailed_design", "*", "allow"));
        var newContract = json.readValue(created.contractJson(), SourceTemplateContract.class);
        var attached = execution.advance(newWriter.id(), newContract);
        assertThat(roleConfiguration.sessionSnapshot(attached.externalSessionId()).orElseThrow().revisionId())
                .isEqualTo(newRevision);
        assertThat(roleConfiguration.resolveFrozen(
                new RoleConfigurationService.OwnerRef("SOURCE_TEMPLATE_MODEL_RUN", oldWriter.id()),
                "SOURCE_DETAILED_DESIGN_NO_TOOLS").orElseThrow().revisionId()).isEqualTo(oldRevision);
    }
    @Test void unknownStopBlocksRecoveryAndAcceptedOutputSurvivesProvedStopWithoutNewModelCall() {
        var writer = awaitRole("SOURCE_DETAILED_DESIGN_V1", 0);
        assertThat(submit(writer, candidate(writer)).outcome()).isEqualTo(MachineCandidateOutcome.ACCEPTED);
        control.requestStop(id, false, "TEST_INTERRUPTION", "fixture interruption");
        fake.failNextAborts(1); coordinator.checkpoint(id);
        assertThat(run().state()).isEqualTo("STOPPING");
        assertThatThrownBy(() -> control.command(id, "resume", command())).isInstanceOf(ConflictException.class);
        coordinator.checkpoint(id);
        assertThat(run().state()).isEqualTo("WAITING_INPUT");
        // Reusing a candidate already accepted within budget must not spend another model call.
        jdbc.update("UPDATE source_template_run SET contract_json=json_set(contract_json,'$.maxStageAttempts',1) WHERE id=?", id);
        var command = command();
        control.command(id, "resume", command); control.command(id, "resume", command);
        var reused = models.exact(id, writer.candidateKind(), 0, 1).orElseThrow();
        assertThat(reused.attempt()).isEqualTo(1); assertThat(reused.state()).isEqualTo("VALIDATED");
        assertThat(reused.outputSha256()).isEqualTo(store.require(writer.id()).outputSha256());
        assertThat(reused.externalSessionId()).isNull();
        assertThat(fake.createReadOnlySessionCalls()).isEqualTo(1);
        var reviewer = awaitRole("SOURCE_DESIGN_REVIEW_V1", 0); complete(reviewer, review(reviewer, false, true));
        for (int i = 0; i < 5 && !run().state().equals("COMPLETED"); i++) coordinator.advance(id);
        assertThat(run().state()).isEqualTo("COMPLETED");
    }
    @Test void failedBatchRetriesSelectivelyAndChangedModuleRechecksCrossModuleReview() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("multiple"));
        for (String module : List.of("a", "b")) {
            Files.createDirectories(source.resolve(module));
            Files.writeString(source.resolve(module + "/Service.java"), "class Service { int value() { return 3; } }");
        }
        var project = projects.create("多模块设计", source.toString(), "fixture");
        var created = service.create(new SourceTemplateRequests.Create(UUID.randomUUID().toString(),
                "DETAILED_DESIGN_WRITING", "1", project.id(), ".", null, null, ""));
        id = created.id(); contract = json.readValue(created.contractJson(), SourceTemplateContract.class);
        admission.start(id, new SourceTemplateRequests.Command(UUID.randomUUID().toString(), created.version()));
        var first = awaitRole("SOURCE_DETAILED_DESIGN_V1", 0, 0); complete(first, candidate(first));
        var second = awaitRole("SOURCE_DETAILED_DESIGN_V1", 1, 0);
        fake.setSessionState(second.externalSessionId(), "COMPLETED"); execution.advance(second.id(), contract);
        for (int i = 0; i < 5 && !run().state().equals("WAITING_INPUT"); i++) coordinator.advance(id);
        assertThat(run().state()).isEqualTo("WAITING_INPUT");
        assertThat(summaries.batches(id, null, 100).items().stream().filter(SourceTemplateModelMapper.Metadata::retryable)
                .map(SourceTemplateModelMapper.Metadata::id)).containsExactly(second.id());
        control.command(id, "retry", new SourceTemplateControl.Command(UUID.randomUUID().toString(), run().version(), List.of(second.id())));
        var retried = awaitRole("SOURCE_DETAILED_DESIGN_V1", 1, 1); complete(retried, candidate(retried));
        assertThat(models.exact(id, first.candidateKind(), 0, 1).orElseThrow().id()).isEqualTo(first.id());
        var reviewA = awaitRole("SOURCE_DESIGN_REVIEW_V1", 0, 0);
        var reviewB = awaitRole("SOURCE_DESIGN_REVIEW_V1", 1, 0);
        // Reading only the assigned document is insufficient to approve a cross-module design.
        var incomplete = review(reviewA, false, false);
        var input = json.readValue(reviewA.inputJson(), SourceDesign.Input.class);
        var ownDraft = store.require(input.draftModelId()); reads.result(reviewA.id(), ownDraft.id(), ownDraft.outputSha256(), 0);
        assertThat(submit(reviewA, incomplete).outcome()).isEqualTo(MachineCandidateOutcome.REJECTED);
        complete(reviewA, review(reviewA, false, true)); complete(reviewB, review(reviewB, true, true));
        var revision = awaitRole("SOURCE_DETAILED_DESIGN_V1", 1, 2);
        var corrected = candidate(revision);
        complete(revision, new SourceDesign.Candidate(corrected.title(), "返回类型为 int 的固定返回值模块", corrected.sections(), corrected.limitations()));
        var nextA = awaitRole("SOURCE_DESIGN_REVIEW_V1", 0, 1);
        assertThat(nextA.inputSha256()).isNotEqualTo(reviewA.inputSha256());
        assertThat(models.exact(id, first.candidateKind(), 0, 1).orElseThrow().id()).isEqualTo(first.id());
        var nextB = awaitRole("SOURCE_DESIGN_REVIEW_V1", 1, 1);
        complete(nextA, review(nextA, false, true)); complete(nextB, review(nextB, false, true));
        for (int i = 0; i < 5 && !run().state().equals("COMPLETED"); i++) coordinator.advance(id);
        assertThat(run().state()).isEqualTo("COMPLETED");
        assertThat(runs.coverageCounts(id)).containsExactly(new SourceTemplateMapper.Count("REVIEWED", 2));
    }
    @Test void independentReviewRequiresRevisionAndPreservesPriorAttempt() {
        var writer = awaitRole("SOURCE_DETAILED_DESIGN_V1", 0); complete(writer, candidate(writer));
        var reviewer = awaitRole("SOURCE_DESIGN_REVIEW_V1", 0); complete(reviewer, review(reviewer, true, true));
        var repaired = awaitRole("SOURCE_DETAILED_DESIGN_V1", 1);
        assertThat(repaired.inputJson()).contains("缺少接口细节");
        assertThat(store.require(writer.id()).state()).isEqualTo("VALIDATED");
        complete(repaired, candidate(repaired));
        var nextReview = awaitRole("SOURCE_DESIGN_REVIEW_V1", 1);
        assertThat(json.readValue(nextReview.inputJson(), SourceDesign.Input.class).draftModelId()).isEqualTo(repaired.id());
    }
    @Test void forgedCoverageAndUnsafeMarkdownAreRejectedBeforePersistence() {
        var writer = awaitRole("SOURCE_DETAILED_DESIGN_V1", 0);
        var valid = candidate(writer); var section = valid.sections().getFirst();
        var forged = new SourceDesign.Candidate(valid.title(), valid.summary(), List.of(new SourceDesign.Section(
                "service", "接口", section.markdown(), List.of("missing.java"), section.references())), List.of());
        assertThat(submit(writer, forged).outcome()).isEqualTo(MachineCandidateOutcome.REJECTED);
        var unsafe = new SourceDesign.Candidate(valid.title(), valid.summary(), List.of(new SourceDesign.Section(
                "service", "接口", "[打开](javascript:alert(1))", section.paths(), section.references())), List.of());
        assertThat(submit(writer, unsafe).outcome()).isEqualTo(MachineCandidateOutcome.REJECTED);
        assertThat(store.require(writer.id()).outputJson()).isNull();
    }
    @Test void interruptedArtifactWritePreservesUserContentAndRecoversExactFrozenPackage() throws Exception {
        var writer = awaitRole("SOURCE_DETAILED_DESIGN_V1", 0); complete(writer, candidate(writer));
        var reviewer = awaitRole("SOURCE_DESIGN_REVIEW_V1", 0); complete(reviewer, review(reviewer, false, true));
        coordinator.advance(id); assertThat(run().state()).isEqualTo("REPORTING");
        Path directory = artifacts.directory(id); Files.createDirectories(directory);
        Path existing = directory.resolve("overview.md"); Files.writeString(existing, "用户已有文档");
        assertThatThrownBy(() -> coordinator.advance(id)).isInstanceOf(ConflictException.class).hasMessageContaining("不会覆盖");
        assertThat(Files.readString(existing)).isEqualTo("用户已有文档");
        String frozen = artifacts.named(id, "overview.md").content(); int calls = fake.createReadOnlySessionCalls();
        control.requestStop(id, false, "SOURCE_ARTIFACT_FILE_CHANGED", "请保留已有文档后恢复"); coordinator.advance(id);
        assertThat(run().state()).isEqualTo("WAITING_INPUT");
        Files.move(existing, directory.resolve("user-overview.md"));
        control.command(id, "resume", command()); coordinator.advance(id);
        assertThat(run().state()).isEqualTo("COMPLETED"); assertThat(fake.createReadOnlySessionCalls()).isEqualTo(calls);
        assertThat(Files.readString(existing)).isEqualTo(frozen);
        assertThat(Files.readString(directory.resolve("user-overview.md"))).isEqualTo("用户已有文档");
        assertThatThrownBy(() -> artifacts.named(id, "../overview.md")).isInstanceOf(NotFoundException.class);
    }
    @Test void cancelKeepsUnknownWriterBlockedAndDoesNotPermitLaterResumption() {
        var writer = awaitRole("SOURCE_DETAILED_DESIGN_V1", 0);
        int calls = fake.createReadOnlySessionCalls(); var cancellation = command();
        control.command(id, "cancel", cancellation); control.command(id, "cancel", cancellation);
        fake.failNextAborts(1); coordinator.checkpoint(id);
        assertThat(run().state()).isEqualTo("STOPPING");
        assertThat(summaries.overview(id).canResume()).isFalse();
        coordinator.checkpoint(id); assertThat(run().state()).isEqualTo("CANCELLED");
        assertThat(store.require(writer.id()).state()).isEqualTo("STOPPED");
        assertThat(fake.createReadOnlySessionCalls()).isEqualTo(calls);
        assertThatThrownBy(() -> control.command(id, "resume", command())).isInstanceOf(ConflictException.class);
        assertThat(artifacts.list(id, null, 100).items()).isEmpty();
    }
    private SourceTemplateRunRow run() { return admission.require(id); }
    private static byte[] rewriteRoleArchive(byte[] source,
            java.util.function.BiFunction<String, String, String> rewrite) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(source));
                var output = new java.util.zip.ZipOutputStream(bytes)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                output.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                String text = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                output.write(rewrite.apply(entry.getName(), text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private SourceTemplateControl.Command command() { return new SourceTemplateControl.Command(UUID.randomUUID().toString(), run().version(), null); }
    private SourceTemplateModelRow awaitRole(String kind, int attempt) {
        return awaitRole(kind, 0, attempt);
    }
    private SourceTemplateModelRow awaitRole(String kind, int ordinal, int attempt) {
        for (int i = 0; i < 24; i++) {
            coordinator.advance(id);
            var row = models.exact(id, kind, ordinal, 1);
            if (row.isPresent() && row.get().attempt() == attempt && row.get().state().equals("RUNNING")) return row.get();
        }
        throw new AssertionError("role not running: " + kind + " " + run());
    }
    private SourceDesign.Reference source(SourceTemplateModelRow row) {
        String path = json.readValue(row.inputJson(), SourceDesign.Input.class).paths().getFirst();
        var file = runs.files(id).stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
        var read = reads.read(row.id(), file.path(), file.sha256(), 1, 200);
        return new SourceDesign.Reference(file.path(), file.sha256(), 1, read.endLine(), read.content());
    }
    private SourceDesign.Candidate candidate(SourceTemplateModelRow row) {
        var ref = source(row);
        return new SourceDesign.Candidate("Service 设计", "固定返回值模块", List.of(new SourceDesign.Section("service", "Service",
                "## 接口\n\nvalue 返回整数 3，无持久化与并发状态。\n\n```mermaid\nflowchart LR\n A[调用 value] --> B[返回 3]\n```",
                List.of(ref.path()), List.of(ref))), List.of());
    }
    private SourceDesign.Review review(SourceTemplateModelRow row, boolean revise, boolean readDraft) {
        var ref = source(row); if (readDraft) readDraft(row);
        return new SourceDesign.Review(revise ? "REVISE" : "PASS", revise ? "缺少接口细节" : "源码与文档一致",
                List.of(ref.path()), List.of(ref), revise ? List.of(new SourceDesign.Issue("service", "缺少接口细节", "补充返回类型")) : List.of());
    }
    private void readDraft(SourceTemplateModelRow row) {
        for (var draft : models.current(id, "SOURCE_DETAILED_DESIGN_V1", row.generation()))
            for (int part = 0; part < SourceModelReads.partCount(draft.outputJson()); part++)
                reads.result(row.id(), draft.id(), draft.outputSha256(), part);
    }
    private void complete(SourceTemplateModelRow row, Object candidate) {
        var result = submit(row, candidate);
        assertThat(result.outcome()).as("%s", result.problems()).isEqualTo(MachineCandidateOutcome.ACCEPTED);
        fake.setSessionState(row.externalSessionId(), "COMPLETED"); execution.advance(row.id(), contract);
    }
    private MachineCandidateSubmission.SubmissionResult submit(SourceTemplateModelRow row, Object candidate) {
        long version = submissions.find(row.id()).orElseThrow().version();
        return submissions.submit(new MachineCandidateSubmission.SubmitCommand(row.id(), "candidate-" + version,
                json.writeValueAsString(candidate), version, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                MachineCandidateSubmission.SubmissionSchema.ROLE_SPECIFIC_V2));
    }
}
