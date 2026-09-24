package io.opencode.loopper.service;

import io.opencode.loopper.service.roles.RolePromptResources;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskDecompositionRow;
import io.opencode.loopper.runtime.MachineRoleContractCatalog;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Builds Decomposer planning, final, and bounded repair prompts. */
final class DesignerDecompositionPromptFactory {
    private static final int MAX_REPAIRS = 2;
    private final ObjectMapper json;
    private final TaskProfileService taskProfiles;
    private final RolePromptComposer rolePrompts;

    DesignerDecompositionPromptFactory(ObjectMapper json, TaskProfileService taskProfiles,
                                       RolePromptComposer rolePrompts) {
        this.json = json;
        this.taskProfiles = taskProfiles;
        this.rolePrompts = rolePrompts;
    }

    String candidate(DesignRequirementRevisionRow revision, String projectRoot, String runId,
                     long expectedSubmissionRevision, String contractVersion, String submitCandidateToolId) {
        return candidate(revision, projectRoot, runId, expectedSubmissionRevision, contractVersion,
                submitCandidateToolId, null);
    }

    String candidate(DesignRequirementRevisionRow revision, String projectRoot, String runId,
                     long expectedSubmissionRevision, String contractVersion, String submitCandidateToolId,
                     Integer correctionLimit) {
        return (RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block01.segment0")
                + String.format("%s", (Object) (rolePrompts.decomposerInstructions(taskProfiles.current(revision.designerSessionId()))))
                + "\n\nProject root: "
                + String.format("%s", (Object) (projectRoot))
                + "\nRequirement revision: R"
                + String.format("%d", (Object) (revision.revision()))
                + RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block01.segment3")
                + String.format("%s", (Object) (numberedSegments(revision)))
                + RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block01.segment4")
                + String.format("%s", (Object) (revision.requirementText()))
                + RolePromptResources.read("prompt.v1.DesignerAcceptanceCandidatePromptFactory.block01.segment4")
                + String.format("%s", (Object) (runId))
                + "\nexpectedSubmissionRevision: "
                + String.format("%d", (Object) (expectedSubmissionRevision))
                + "\ncontractVersion: "
                + String.format("%s", (Object) (contractVersion))
                + "\nexact role submission tool: "
                + String.format("%s", (Object) (submitCandidateToolId))
                + "\n\nCall "
                + String.format("%s", (Object) (submitCandidateToolId))
                + RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block01.segment10")
                + String.format("%s", (Object) (CandidateCorrectionPolicy.prompt(correctionLimit)))
                + RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block01.segment11")
                + String.format("%s", (Object) (gapContract()))
                + RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block01.segment12"));
    }

    String planning(DesignerSessionRow session, ProjectRow project,
                    DesignRequirementRevisionRow revision, boolean retry) {
        return (RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block02.segment0")
                + String.format("%s", (Object) (rolePrompts.decomposerInstructions(taskProfiles.current(session.id()))))
                + RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block02.segment1")
                + String.format("%s", (Object) (project.rootPath()))
                + "\nDesigner session: "
                + String.format("%s", (Object) (session.id()))
                + "\nRequirement revision: R"
                + String.format("%d", (Object) (revision.revision()))
                + String.format("%s", (Object) (retry ? " explicit retry" : ""))
                + RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block01.segment3")
                + String.format("%s", (Object) (numberedSegments(revision)))
                + RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block01.segment4")
                + String.format("%s", (Object) (revision.requirementText()))
                + "\n\n"
                + String.format("%s", (Object) (planningContract() + "\n" + gapContract()))
                + RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block02.segment8"));
    }

    String finalJson(DesignerSemanticContracts.DecompositionPlanEnvelope plan) {
        return (RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block03.segment0")
                + String.format("%s", (Object) (write(plan)))
                + "\n\n"
                + String.format("%s", (Object) (finalContract()))
                + RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block03.segment2"));
    }

    String legacy(DesignerSessionRow session, ProjectRow project,
                  DesignRequirementRevisionRow revision, boolean retry) {
        return (RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block04.segment0")
                + String.format("%s", (Object) (project.rootPath()))
                + "\nDesigner session: "
                + String.format("%s", (Object) (session.id()))
                + "\nRequirement revision: R"
                + String.format("%d", (Object) (revision.revision()))
                + String.format("%s", (Object) (retry ? " explicit retry" : ""))
                + RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block01.segment3")
                + String.format("%s", (Object) (numberedSegments(revision)))
                + RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block01.segment4")
                + String.format("%s", (Object) (revision.requirementText()))
                + RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block04.segment6"));
    }

    String repair(TaskDecompositionRow row, String code, String detail) {
        String frozen = row.planningJson() == null || row.planningJson().isBlank() ? null : row.planningJson();
        return frozen == null ? (RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block05.segment0")
                + String.format("%d", (Object) (row.repairCount()))
                + "/"
                + String.format("%d", (Object) (MAX_REPAIRS))
                + ". Error code: "
                + String.format("%s", (Object) (code))
                + ". Error detail: "
                + String.format("%s", (Object) (safe(detail)))
                + ".\n\n"
                + String.format("%s", (Object) (finalContract()))
                + RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block05.segment5")) : (RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block06.segment0")
                + String.format("%d", (Object) (row.repairCount()))
                + "/"
                + String.format("%d", (Object) (MAX_REPAIRS))
                + ". Error code: "
                + String.format("%s", (Object) (code))
                + ". Error detail: "
                + String.format("%s", (Object) (safe(detail)))
                + ".\n\nFrozen planning:\n"
                + String.format("%s", (Object) (frozen))
                + "\n\n"
                + String.format("%s", (Object) (finalContract()))
                + RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block05.segment5"));
    }

    String planningRepair(TaskDecompositionRow row, DesignRequirementRevisionRow revision,
                          String code, String detail) {
        return (RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block07.segment0")
                + String.format("%d", (Object) (row.planningRepairCount()))
                + "/"
                + String.format("%d", (Object) (MAX_REPAIRS))
                + ". Error code: "
                + String.format("%s", (Object) (code))
                + ". Error detail: "
                + String.format("%s", (Object) (safe(detail)))
                + RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block07.segment4")
                + String.format("%s", (Object) (numberedSegments(revision)))
                + "\n\n"
                + String.format("%s", (Object) (planningContract()))
                + RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block07.segment6"));
    }

    String semanticPatch(TaskDecompositionRow row, String code, String detail) {
        return (RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block08.segment0")
                + String.format("%s", (Object) (code))
                + ". Error detail: "
                + String.format("%s", (Object) (safe(detail)))
                + ".\n\nFrozen semantic object:\n"
                + String.format("%s", (Object) (row.semanticPlanJson()))
                + RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block08.segment3"));
    }

    static String gapContract() {
        return (RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block09.segment0")
                + String.format("%s", (Object) (java.util.Arrays.stream(DesignerSemanticContracts.DesignGapCode.values())
                .map(Enum::name).collect(java.util.stream.Collectors.joining(", "))))
                + RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block09.segment1"));
    }

    private String planningContract() {
        return (String.format("%s", (Object) (MachineRoleContractCatalog.card("DECOMPOSER")))
                + RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block10.segment1"));
    }

    private String finalContract() {
        return RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.block11");
    }

    private String numberedSegments(DesignRequirementRevisionRow revision) {
        if (SourceRequirementContext.source(revision.requirementText())) return RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.source-guidance");
        if (DocumentRequirementContext.document(revision.requirementText())) return RolePromptResources.read("prompt.v1.DesignerDecompositionPromptFactory.document-source-guidance");
        return readSegments(revision.requirementSegmentsJson()).stream()
                .map(segment -> segment.id() + ": " + segment.text())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private List<DesignerSessionService.RequirementSegment> readSegments(String source) {
        try {
            return json.readValue(source, new TypeReference<>() { });
        } catch (Exception failure) {
            throw new IllegalStateException("Frozen requirement segments are unreadable", failure);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to serialize frozen decomposition plan", failure);
        }
    }

    private String safe(String value) {
        return value == null ? "Unknown error" : value.substring(0, Math.min(value.length(), 4_000));
    }
}
