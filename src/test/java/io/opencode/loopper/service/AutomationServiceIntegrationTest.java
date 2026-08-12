package io.opencode.loopper.service;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.AutomationApprovalMode;
import io.opencode.loopper.domain.AutomationTriggerType;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.LoopSpecTemplateVersionRow;
import io.opencode.loopper.persistence.ProjectRow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(classes = LoopperApplication.class, properties = {"loopper.opencode.mode=fake", "loopper.monitor-delay=1h", "loopper.automation-monitor-delay=1h"})
class AutomationServiceIntegrationTest {
    @Autowired private Flyway flyway;
    @Autowired private ProjectService projects;
    @Autowired private LoopSpecTemplateService templates;
    @Autowired private AutomationService automation;
    @Autowired private LoopperMapper mapper;
    @Autowired private ObjectMapper json;
    @MockitoSpyBean private LoopDraftService drafts;
    @TempDir Path temp;

    @BeforeEach void reset() { flyway.clean(); flyway.migrate(); }

    @Test
    void creationIsDisabledAndWebhookIsLoopbackTokenProtectedAndDedupedByDelivery() throws Exception {
        ProjectRow project = projects.create("automation", gitProject("webhook"));
        var template = templates.create("safe template", "");
        var version = templates.createVersion(template.id(), spec(project.id()), false);
        var created = automation.create(new AutomationService.RuleInput("incoming", project.id(), version.id(), AutomationTriggerType.WEBHOOK,
                Map.of(), "ENABLED", AutomationApprovalMode.AUTO_START));
        assertThat(created.rule().state()).isEqualTo("DISABLED");
        assertThat(created.rule().approvalMode()).isEqualTo(AutomationApprovalMode.REVIEW_REQUIRED);
        assertThat(created.webhookToken()).isNotBlank();
        assertThat(created.rule().triggerConfig()).doesNotContainKey("token");
        assertThatThrownBy(() -> automation.webhook(created.rule().id(), created.webhookToken(), "127.0.0.1", "{}", "one"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("disabled");

        automation.update(created.rule().id(), new AutomationService.RuleInput("incoming", project.id(), version.id(), AutomationTriggerType.WEBHOOK,
                Map.of(), "ENABLED", AutomationApprovalMode.REVIEW_REQUIRED), created.rule().version());
        assertThatThrownBy(() -> automation.webhook(created.rule().id(), created.webhookToken(), "10.0.0.2", "{}", "one"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("loopback");
        var first = automation.webhook(created.rule().id(), created.webhookToken(), "127.0.0.1", "{}", "delivery-1");
        var duplicate = automation.webhook(created.rule().id(), created.webhookToken(), "127.0.0.1", "{}", "delivery-1");
        assertThat(first.state()).isEqualTo("REVIEW_REQUIRED");
        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(automation.runs(created.rule().id())).hasSize(1);
    }

    @Test
    void admissionExceptionStillCommitsAFailedRunWithEvidence() throws Exception {
        ProjectRow project = projects.create("automation-failure", gitProject("failure"));
        var template = templates.create("approved template", "");
        var version = templates.createVersion(template.id(), spec(project.id()), true);
        var created = automation.create(new AutomationService.RuleInput("manual", project.id(), version.id(),
                AutomationTriggerType.MANUAL, Map.of(), null, null));
        var enabled = automation.update(created.rule().id(), new AutomationService.RuleInput("manual", project.id(), version.id(),
                AutomationTriggerType.MANUAL, Map.of(), "ENABLED", AutomationApprovalMode.AUTO_START), created.rule().version());
        doThrow(new ConflictException("INJECTED_DRAFT_FAILURE", "injected after run detection"))
                .when(drafts).create(any(LoopSpec.class));

        var failed = automation.manual(enabled.id());

        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(mapper.listAutomationRuns(enabled.id())).singleElement().satisfies(run -> {
            assertThat(run.state()).isEqualTo("FAILED");
            assertThat(run.endedAt()).isNotBlank();
            assertThat(run.evidenceJson()).contains("INJECTED_DRAFT_FAILURE", "injected after run detection");
        });
    }

    @Test
    void workspacePreviewRejectsATamperedVersionHashWithoutWriting() throws Exception {
        ProjectRow project = projects.create("automation-export", gitProject("export"));
        var template = templates.create("exported template", "hash protected");
        templates.createVersion(template.id(), spec(project.id()), false);
        int templatesBefore = mapper.listLoopSpecTemplates().size();
        int rulesBefore = mapper.listAutomationRules().size();
        var tree = json.valueToTree(automation.exportWorkspace());
        ObjectNode version = (ObjectNode) tree.path("templates").get(0).path("versions").get(0);
        version.put("specSha256", "0".repeat(64));

        assertThatThrownBy(() -> automation.previewWorkspace(json.writeValueAsString(tree)))
                .isInstanceOfSatisfying(BadRequestException.class,
                        failure -> assertThat(failure.code()).isEqualTo("AUTOMATION_IMPORT_HASH_MISMATCH"));
        assertThat(mapper.listLoopSpecTemplates()).hasSize(templatesBefore);
        assertThat(mapper.listAutomationRules()).hasSize(rulesBefore);
    }

    @Test
    void persistedLegacyTemplateAutomationKeepsV1Behavior() throws Exception {
        ProjectRow project = projects.create("legacy-automation", gitProject("legacy"));
        var template = templates.create("legacy template", "persisted before v2");
        LoopSpec legacySpec = new LoopSpec("v1", project.id(), "legacy", "",
                List.of(new LoopSpec.StageSpec("legacy check", List.of(), List.of(), List.of(),
                        List.of(new LoopSpec.VerifierSpec("FILE_NOT_EXISTS", null, "never-created.txt", null,
                                List.of(), List.of(), false)))), null, null, null, null);
        String source = json.writeValueAsString(legacySpec);
        LoopSpecTemplateVersionRow legacyVersion = new LoopSpecTemplateVersionRow(UUID.randomUUID().toString(),
                template.id(), 1, source, "legacy-sha", true, false, Instant.now().toString());
        assertThat(mapper.insertLoopSpecTemplateVersion(legacyVersion)).isEqualTo(1);
        var created = automation.create(new AutomationService.RuleInput("legacy manual", project.id(),
                legacyVersion.id(), AutomationTriggerType.MANUAL, Map.of(), null, null));
        var enabled = automation.update(created.rule().id(), new AutomationService.RuleInput("legacy manual",
                project.id(), legacyVersion.id(), AutomationTriggerType.MANUAL, Map.of(), "ENABLED",
                AutomationApprovalMode.REVIEW_REQUIRED), created.rule().version());

        var run = automation.manual(enabled.id());

        assertThat(run.state()).isEqualTo("REVIEW_REQUIRED");
        assertThat(drafts.spec(drafts.get(run.draftId())).schemaVersion()).isEqualTo("v1");
    }

    @Test
    void workspacePreviewRejectsDuplicateIdsAndWrongOwnersButAllowsRulesToShareAnImmutableVersion() throws Exception {
        ProjectRow project = projects.create("automation-integrity", gitProject("integrity"));
        var template = templates.create("integrity template", "");
        var version = templates.createVersion(template.id(), spec(project.id()), false);
        automation.create(new AutomationService.RuleInput("one", project.id(), version.id(), AutomationTriggerType.MANUAL, Map.of(), null, null));
        ObjectNode export = (ObjectNode) json.valueToTree(automation.exportWorkspace());

        ObjectNode duplicateTemplate = export.deepCopy();
        duplicateTemplate.withArray("templates").add(export.path("templates").get(0).deepCopy());
        assertThatThrownBy(() -> automation.previewWorkspace(json.writeValueAsString(duplicateTemplate)))
                .isInstanceOfSatisfying(BadRequestException.class, failure -> assertThat(failure.code()).isEqualTo("AUTOMATION_IMPORT_TEMPLATE_DUPLICATE"));

        ObjectNode wrongOwner = export.deepCopy();
        ((ObjectNode) wrongOwner.path("templates").get(0).path("versions").get(0)).put("templateId", "other-template");
        assertThatThrownBy(() -> automation.previewWorkspace(json.writeValueAsString(wrongOwner)))
                .isInstanceOfSatisfying(BadRequestException.class, failure -> assertThat(failure.code()).isEqualTo("AUTOMATION_IMPORT_VERSION_OWNER"));

        ObjectNode duplicateRuleReference = export.deepCopy();
        ObjectNode secondRule = ((ObjectNode) duplicateRuleReference.path("rules").get(0)).deepCopy();
        secondRule.put("id", "another-rule");
        duplicateRuleReference.withArray("rules").add(secondRule);
        assertThat(automation.previewWorkspace(json.writeValueAsString(duplicateRuleReference)).ruleCount()).isEqualTo(2);
    }

    @Test
    void reviewConfirmationFailureStillReplacesReviewRunWithDurableFailureEvidence() throws Exception {
        ProjectRow project = projects.create("automation-review-failure", gitProject("review-failure"));
        var template = templates.create("review template", "");
        var version = templates.createVersion(template.id(), spec(project.id()), false);
        var created = automation.create(new AutomationService.RuleInput("review", project.id(), version.id(), AutomationTriggerType.MANUAL, Map.of(), null, null));
        var enabled = automation.update(created.rule().id(), new AutomationService.RuleInput("review", project.id(), version.id(),
                AutomationTriggerType.MANUAL, Map.of(), "ENABLED", AutomationApprovalMode.REVIEW_REQUIRED), created.rule().version());
        var waiting = automation.manual(enabled.id());
        doThrow(new ConflictException("INJECTED_CONFIRM_FAILURE", "confirmation exploded"))
                .when(drafts).confirm(eq(waiting.draftId()), any(), eq("AUTOMATION"));

        var failed = automation.confirmReview(waiting.id(), "approve");

        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(mapper.findAutomationRun(waiting.id()).orElseThrow().evidenceJson())
                .contains("INJECTED_CONFIRM_FAILURE", "confirmation exploded");
    }

    @Test
    void automaticAdmissionMarksDirectQueueWithAutomationSource() throws Exception {
        Path root = Files.createDirectory(temp.resolve("direct-automation"));
        ProjectRow project = projects.create("automation-source", root.toString());
        var template = templates.create("source template", "");
        var version = templates.createVersion(template.id(), spec(project.id()), true);
        var created = automation.create(new AutomationService.RuleInput("source", project.id(), version.id(), AutomationTriggerType.MANUAL, Map.of(), null, null));
        var enabled = automation.update(created.rule().id(), new AutomationService.RuleInput("source", project.id(), version.id(),
                AutomationTriggerType.MANUAL, Map.of(), "ENABLED", AutomationApprovalMode.AUTO_START), created.rule().version());

        var run = automation.manual(enabled.id());

        assertThat(run.taskId()).isNotBlank();
        assertThat(mapper.findTaskQueue(run.taskId()).orElseThrow().source()).isEqualTo("AUTOMATION");
    }

    @Test
    void workspaceImportWritesOnlyAfterConfirmationAndReturnsFreshWebhookToken() throws Exception {
        ProjectRow project = projects.create("automation-import", gitProject("import"));
        var template = templates.create("portable template", "preview first");
        var version = templates.createVersion(template.id(), spec(project.id()), false);
        automation.create(new AutomationService.RuleInput("portable hook", project.id(), version.id(),
                AutomationTriggerType.WEBHOOK, Map.of(), null, null));
        String exported = json.writeValueAsString(automation.exportWorkspace());
        int templatesBefore = mapper.listLoopSpecTemplates().size();
        int rulesBefore = mapper.listAutomationRules().size();

        var preview = automation.previewWorkspace(exported);

        assertThat(mapper.listLoopSpecTemplates()).hasSize(templatesBefore);
        assertThat(mapper.listAutomationRules()).hasSize(rulesBefore);
        var imported = automation.confirmWorkspaceImport(preview.previewId());
        assertThat(imported.templates()).singleElement().satisfies(row -> assertThat(row.versions()).hasSize(1));
        assertThat(imported.rules()).singleElement().satisfies(mutation -> {
            assertThat(mutation.rule().state()).isEqualTo("DISABLED");
            assertThat(mutation.rule().approvalMode()).isEqualTo(AutomationApprovalMode.REVIEW_REQUIRED);
            assertThat(mutation.webhookToken()).isNotBlank();
        });
        assertThat(mapper.listLoopSpecTemplates()).hasSize(templatesBefore + 1);
        assertThat(mapper.listAutomationRules()).hasSize(rulesBefore + 1);
        assertThat(exported).doesNotContain("webhookToken", "webhookTokenHash");
    }

    @Test
    void fiveFieldCronFiresOnlyOneRunForTheCurrentDueMinute() throws Exception {
        ProjectRow project = projects.create("automation-cron", gitProject("cron"));
        var template = templates.create("cron template", "");
        var version = templates.createVersion(template.id(), spec(project.id()), false);
        var created = automation.create(new AutomationService.RuleInput("each minute", project.id(), version.id(),
                AutomationTriggerType.CRON, Map.of("expression", "* * * * *", "timezone", "Asia/Shanghai"), null, null));
        automation.update(created.rule().id(), new AutomationService.RuleInput("each minute", project.id(), version.id(),
                AutomationTriggerType.CRON, created.rule().triggerConfig(), "ENABLED", AutomationApprovalMode.REVIEW_REQUIRED), created.rule().version());

        automation.poll();
        automation.poll();

        assertThat(automation.runs(created.rule().id())).singleElement()
                .satisfies(run -> assertThat(run.state()).isEqualTo("REVIEW_REQUIRED"));
    }

    @Test
    void gitHeadTriggerPersistsABaselineBeforeDetectingAChange() throws Exception {
        ProjectRow project = projects.create("automation-git", gitProject("head"));
        var template = templates.create("head template", "");
        var version = templates.createVersion(template.id(), spec(project.id()), false);
        var created = automation.create(new AutomationService.RuleInput("head changed", project.id(), version.id(),
                AutomationTriggerType.GIT_HEAD_CHANGED, Map.of(), null, null));
        automation.update(created.rule().id(), new AutomationService.RuleInput("head changed", project.id(), version.id(),
                AutomationTriggerType.GIT_HEAD_CHANGED, Map.of(), "ENABLED", AutomationApprovalMode.REVIEW_REQUIRED), created.rule().version());

        automation.poll();
        assertThat(automation.runs(created.rule().id())).isEmpty();
        assertThat(mapper.findAutomationRule(created.rule().id()).orElseThrow().lastObservedHead()).isNotBlank();

        Path root = Path.of(project.rootPath());
        Files.writeString(root.resolve("change.txt"), "changed");
        run(root, "git", "add", "change.txt");
        run(root, "git", "commit", "-m", "change");
        automation.poll();

        assertThat(automation.runs(created.rule().id())).singleElement().satisfies(run -> {
            assertThat(run.triggerType()).isEqualTo("GIT_HEAD_CHANGED");
            assertThat(run.state()).isEqualTo("REVIEW_REQUIRED");
        });
    }

    private LoopSpec spec(String projectId) {
        return new LoopSpec("v2", projectId, "verify", null, List.of(new LoopSpec.StageSpec("check", null, null, null,
                List.of(new LoopSpec.VerifierSpec("PROCESS", List.of("mvn", "test"), null, null,
                        null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, List.of(), List.of("AC-1"), "TEST", List.of("automation scenario"))),
                List.of(new LoopSpec.AcceptanceCriterion("AC-1", "automation scenario passes")), null)),
                null, null, null, null);
    }
    private String gitProject(String suffix) throws Exception {
        Path root = Files.createDirectory(temp.resolve("git-" + suffix)); Files.writeString(root.resolve("README.md"), "fixture");
        run(root, "git", "init"); run(root, "git", "config", "user.email", "t@example.invalid"); run(root, "git", "config", "user.name", "t");
        run(root, "git", "add", "README.md"); run(root, "git", "commit", "-m", "initial"); return root.toString();
    }
    private void run(Path root, String... command) throws Exception { Process process = new ProcessBuilder(command).directory(root.toFile()).start(); if (process.waitFor() != 0) throw new AssertionError(new String(process.getErrorStream().readAllBytes())); }
}
