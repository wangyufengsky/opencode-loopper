package io.opencode.loopper.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.TestPolicy;
import io.opencode.loopper.domain.WorkflowTemplate;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.InternalMcpContractCatalog;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/** Offline fixture adapter: real prompt/schema/compiler; no application lifecycle or writable session. */
public final class PackageDesignLunaProbe {
    record Fixture(String id, String requirement, String technology, String target, String symbol, String contractVersion, String projectRoot) { }
    private final ObjectMapper json = new ObjectMapper();

    public static void main(String[] args) throws Exception { new PackageDesignLunaProbe().run(args); }

    private void run(String[] args) throws Exception {
        if (args.length == 1 && (args[0].equals("schema") || args[0].equals("schema-v2"))) {
            System.out.println(json.writeValueAsString(args[0].equals("schema-v2") ? InternalMcpContractCatalog.packageDesignV2InputSchema() : InternalMcpContractCatalog.inputSchema(MachineCandidateKind.PACKAGE_DESIGN_V1)));
            return;
        }
        Fixture fixture = json.readValue(Files.readString(Path.of(args[1])), Fixture.class);
        if (args[0].equals("prompt")) { System.out.println(prompt(fixture, args[2])); return; }
        var session = new PackageDesignLunaSession(json, input(fixture));
        try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
            for (String line; (line = reader.readLine()) != null;) {
                var result = session.submit(line);
                var response = json.readValue(result.responseJson(), new tools.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() { });
                // Kept only in the synthetic offline evidence ledger, never sent back as repair hints.
                response.put("compiledResultJson", session.accepted() == null ? null : session.accepted().compiledResultJson());
                response.put("canonicalCandidateJson", session.accepted() == null ? null : session.accepted().canonicalCandidateJson());
                System.out.println(json.writeValueAsString(response));
            }
        }
    }

    private PackageDesignCompilation.Input input(Fixture fixture) {
        var input = new PackageDesignCompilation.Input(workPackage(fixture), fixture.requirement(), role(fixture),
                List.of(fixture.target()), List.of(), List.of(fixture.symbol()), 6, true);
        return "PACKAGE_DESIGN_V2".equals(fixture.contractVersion()) ? input.withContract(fixture.contractVersion())
                .withEvidence(PackageDesignEvidencePreparation.prepare(Path.of(fixture.projectRoot()), "requirement", fixture.requirement(), List.of(fixture.target()))) : input;
    }

    private WorkPackageRoleService.View role(Fixture fixture) {
        return new WorkPackageRoleService.View("software-" + fixture.technology(), "2026-08-dynamic-v7",
                ExecutionStrategy.OPEN_CODE_IMPLEMENTATION, TestPolicy.REQUIRED, List.of(fixture.technology()));
    }

    private DesignWorkPackageRow workPackage(Fixture fixture) {
        var row = mock(DesignWorkPackageRow.class);
        when(row.id()).thenReturn("package-row");
        when(row.packageId()).thenReturn("WP-1");
        when(row.designerSessionId()).thenReturn("qualification");
        when(row.requirementRevisionId()).thenReturn("requirement");
        return row;
    }

    private String prompt(Fixture fixture, String projectRoot) throws Exception {
        var profiles = mock(TaskProfileService.class);
        var profile = mock(TaskProfileService.View.class);
        when(profile.rolePackVersion()).thenReturn("2026-08-dynamic-v7");
        when(profiles.current("qualification")).thenReturn(profile);
        when(profiles.workflowTemplateIncludingSuperseded("qualification")).thenReturn(WorkflowTemplate.DIRECT_SOFTWARE_DESIGN);
        var row = workPackage(fixture);
        var roles = mock(WorkPackageRoleService.class);
        when(roles.get(row)).thenReturn(role(fixture));
        var context = mock(DesignerPackageContext.class);
        when(context.packageScope(row)).thenReturn(json.writeValueAsString(List.of(fixture.target())));
        when(context.prerequisites("requirement", row)).thenReturn("[]");
        when(context.previousDesign(row)).thenReturn("首次设计");
        var session = mock(DesignerSessionRow.class);
        when(session.id()).thenReturn("qualification");
        when(context.decisions(session, row)).thenReturn("[]");
        var project = mock(ProjectRow.class);
        when(project.rootPath()).thenReturn(projectRoot);
        var revision = mock(DesignRequirementRevisionRow.class);
        when(revision.id()).thenReturn("requirement");
        when(revision.revision()).thenReturn(1);
        when(revision.requirementText()).thenReturn(fixture.requirement());
        var decomposition = mock(TaskDecompositionRow.class);
        when(decomposition.planJson()).thenReturn("{\"packages\":[{\"packageId\":\"WP-1\"}]}");
        String base = new DesignerPackagePromptFactory(profiles, new RolePromptComposer(), roles, context)
                .build(session, project, revision, row, decomposition, false, false, true);
        // Reflection lets the same test-only adapter call the immutable baseline's exact private prompt factory.
        var method = DesignerPackageCandidateOrchestrator.class.getDeclaredMethod("prompt", String.class,
                MachineCandidateSubmission.RunSnapshot.class, String.class);
        method.setAccessible(true);
        var run = mock(MachineCandidateSubmission.RunSnapshot.class);
        when(run.runId()).thenReturn("qualification");
        when(run.correctionLimit()).thenReturn(4);
        if ("PACKAGE_DESIGN_V2".equals(fixture.contractVersion())) return PackageDesignV2Prompt.build(base, fixture.requirement(), run,
                "mcp__qualification__submit_package_design_v2");
        return (String) method.invoke(mock(DesignerPackageCandidateOrchestrator.class), base, run,
                "mcp__qualification__submit_package_design");
    }
}
