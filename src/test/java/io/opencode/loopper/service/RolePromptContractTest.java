package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.opencode.loopper.runtime.MachineRoleContractCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RolePromptContractTest {
    @Test void routerActuallyReceivesEveryCurrentArtifactKind() {
        var client = mock(io.opencode.loopper.runtime.OpenCodeClient.class);
        var path = java.nio.file.Path.of(".");
        var remote = new io.opencode.loopper.runtime.OpenCodeClient.OpenCodeSession("router", path);
        when(client.healthy()).thenReturn(true);
        when(client.createSession(eq(path), anyString(), any(), eq(io.opencode.loopper.runtime.OpenCodeClient.SessionProfile.ROUTER_NO_TOOLS)))
                .thenReturn(remote);
        var json = new ObjectMapper();
        var router = new TaskSemanticRouter(client, new io.opencode.loopper.config.LoopperProperties(), json, new AiOutputExtractor(json));
        assertThat(router.start(path, "调研并形成报告").externalSessionId()).isEqualTo("router");
        var request = org.mockito.ArgumentCaptor.forClass(io.opencode.loopper.runtime.OpenCodeClient.PromptRequest.class);
        verify(client).promptAsync(eq(remote), request.capture());
        for (var kind : io.opencode.loopper.domain.ArtifactKind.values()) assertThat(request.getValue().text()).contains(kind.name());
        assertThat(request.getValue().text()).contains("ANALYSIS_REPORT", "exactly one value");
        assertThat(io.opencode.loopper.runtime.OpenCodeClient.STRUCTURED_AGENT_PROMPT)
                .contains("new payload", "returned revision", "same exact submit tool", "not final");
    }

    @Test void packageShapeIncludingNonEmptyReviewPassesTheProductionCodec() {
        var decoded = new PackageDesignCandidateCodec(new ObjectMapper())
                .decode(PackageDesignCandidatePromptContract.readyExample(), 3);
        assertThat(decoded.problems()).isEmpty();
        assertThat(decoded.candidate().reviews()).hasSize(1);
        for (var code : DesignerSemanticContracts.DesignGapCode.values()) {
            assertThat(PackageDesignCandidatePromptContract.instructions()).contains(code.name());
        }
    }

    @Test void closedChoiceExampleUsesTheRealNestedContract() {
        String contract = DesignerClosedChoiceContract.outputContract();
        String example = contract.substring(contract.indexOf('{'), contract.lastIndexOf('}') + 1);
        var parser = new DesignerClosedChoiceContract(new ObjectMapper(), new AiOutputExtractor(new ObjectMapper()));
        assertThat(parser.inspectCandidateBoundary(example)).isEqualTo(DesignerClosedChoiceContract.CandidateBoundary.SAFE);
        assertThat(parser.parse(example).value().capabilityPreferences()).hasSize(1);
    }

    @Test void everyRequirementBranchReceivesSpecializationWithoutRequiringAnotherQuestion() {
        RolePromptComposer roles = new RolePromptComposer();
        DesignerConversationPromptFactory factory = new DesignerConversationPromptFactory();
        for (String role : List.of("software-java", "software-python", "software-node", "software-mixed",
                "software-generic", "document-markdown-docx", "tabular-conversion", "local-maintenance", "read-only-report")) {
            TaskProfileService.View profile = mock(TaskProfileService.View.class);
            when(profile.rolePackId()).thenReturn(role);
            when(profile.rolePackVersion()).thenReturn(RolePackRegistry.VERSION);
            for (boolean direct : List.of(true, false)) for (boolean nativeTool : List.of(true, false)) {
                String prompt = factory.requirementDiscussion(direct, roles.requirementDesignerInstructions(profile),
                        "/project", "session", "prior answer", "feedback", false, true, nativeTool);
                assertThat(prompt).contains(role, "prior answer", "unresolved decisions")
                        .doesNotContain("mandatory question must ask the user to choose the task type");
            }
            String answered = factory.requirementDiscussion(false, roles.requirementDesignerInstructions(profile),
                    "/project", "session", "prior answer", "feedback", false, false, false);
            assertThat(answered).contains(role, "Do not ask another question", "only when the current phase permits questions");
        }
    }

    @Test void assembledPackagePromptSelectsTheCurrentSnapshotAndStageLimit() {
        var profiles = mock(TaskProfileService.class);
        var profile = mock(TaskProfileService.View.class);
        when(profiles.current("session")).thenReturn(profile);
        when(profile.rolePackVersion()).thenReturn(RolePackRegistry.VERSION);
        var roles = mock(WorkPackageRoleService.class);
        var workPackage = mock(io.opencode.loopper.persistence.DesignWorkPackageRow.class);
        when(workPackage.packageId()).thenReturn("WP-2");
        when(roles.get(workPackage)).thenReturn(new WorkPackageRoleService.View("software-java", RolePackRegistry.VERSION,
                io.opencode.loopper.domain.ExecutionStrategy.OPEN_CODE_IMPLEMENTATION,
                io.opencode.loopper.domain.TestPolicy.REQUIRED, List.of("java")));
        var session = mock(io.opencode.loopper.persistence.DesignerSessionRow.class);
        when(session.id()).thenReturn("session");
        var factory = new DesignerPackagePromptFactory(profiles, new RolePromptComposer(), roles, mock(DesignerPackageContext.class));
        when(profiles.workflowTemplateIncludingSuperseded("session"))
                .thenReturn(io.opencode.loopper.domain.WorkflowTemplate.FULL_PACKAGE_DESIGN);
        when(session.taskId()).thenReturn("rolling-task");
        String rolling = factory.build(session, mock(io.opencode.loopper.persistence.ProjectRow.class),
                mock(io.opencode.loopper.persistence.DesignRequirementRevisionRow.class), workPackage,
                mock(io.opencode.loopper.persistence.TaskDecompositionRow.class), false, false);
        assertThat(rolling).contains("latest read-only checkpoint", "Use 1-3 stages", "software-java")
                .doesNotContain("intentionally absent", "immutable pre-execution", "covers:[]", "forbidDeletes=true");
    }

    @Test void rollingAndPreExecutionSnapshotsHaveDistinctEvidenceSemantics() {
        assertThat(DesignerPackagePromptFactory.repositoryContext(true)).contains("latest read-only checkpoint",
                "implemented packages", "navigation only").doesNotContain("pre-execution", "intentionally absent");
        assertThat(DesignerPackagePromptFactory.repositoryContext(false)).contains("pre-execution",
                "approval alone is not proof").doesNotContain("guaranteed to execute successfully");
        assertThat(MachineRoleContractCatalog.legacySemanticCompilerCard()).contains("criteria", "sourceRefs", "v3")
                .doesNotContain("Do not decide outcome", "Do not invent commands, paths, ids, criteria");
    }
}
