package io.opencode.loopper.service;

import io.opencode.loopper.service.roles.RolePromptResources;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.domain.TestPolicy;
import io.opencode.loopper.persistence.LoopperTaskMapper;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskArtifactRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.OpenCodeClient.SessionProfile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Builds bounded implementation prompts from frozen Task and package context. */
final class TaskExecutionPromptFactory {
    private static final String DESIGN_CONTEXT = "DESIGN_CONTEXT";
    private static final String DECOMPOSITION_CONTEXT = "DECOMPOSITION_CONTEXT";
    private static final String PACKAGE_DESIGN = "WORK_PACKAGE_DESIGN";
    private static final String PACKAGE_HANDOFF = "WORK_PACKAGE_COMPILATION_SUMMARY";
    private static final int MAX_DESIGN_CONTEXT_CHARS = 12_000;
    private final LoopperTaskMapper mapper;
    private final ObjectMapper json;
    private final RolePromptComposer rolePrompts;
    private final RoleSessions roleSessions;

    TaskExecutionPromptFactory(LoopperTaskMapper mapper, ObjectMapper json, RolePromptComposer rolePrompts) {
        this(mapper, json, rolePrompts, null);
    }

    TaskExecutionPromptFactory(LoopperTaskMapper mapper, ObjectMapper json, RolePromptComposer rolePrompts,
                               RoleSessions roleSessions) {
        this.mapper = mapper;
        this.json = json;
        this.rolePrompts = rolePrompts;
        this.roleSessions = roleSessions;
    }

    String prompt(TaskRow task, LoopSpec spec, StageRow stage, Path workspace, String recovery) {
        return RoleSessions.render(roleSessions, "TASK", task.id(), SessionProfile.IMPLEMENTATION, null,
                () -> promptUnscoped(task, spec, stage, workspace, recovery));
    }

    private String promptUnscoped(TaskRow task, LoopSpec spec, StageRow stage, Path workspace, String recovery) {
        LoopSpec.StageSpec stageContract = stageContract(spec, stage);
        String designContext = designContext(task.id(), stage);
        TestPolicy testPolicy;
        try {
            testPolicy = TestPolicy.valueOf(stage.testPolicy());
        } catch (RuntimeException missingLegacySnapshot) {
            testPolicy = TestPolicy.REQUIRED;
        }
        String roleInstructions = rolePrompts.implementationInstructions(stage.rolePackId(), stage.rolePackVersion(),
                readStringList(stage.technologiesJson()), testPolicy);
        return roleInstructions + "\nAuthoritative execution workspace: " + workspace
                + "\nWorkspace branch: " + task.branchName()
                + RolePromptResources.read("prompt.v1.TaskExecutionPromptFactory.workspace-guidance")
                + "\nGoal: " + spec.goal() + "\nContext: " + spec.context() + "\nStage: " + stageContract.objective()
                + "\nAuthoritative current StageSpec (including acceptance criteria, Judge rubrics and runtime):\n"
                + json.writeValueAsString(stageContract)
                + RolePromptResources.read("prompt.v1.TaskExecutionPromptFactory.acceptance-guidance")
                + RolePromptResources.read("prompt.v1.TaskExecutionPromptFactory.verification-runtime-guidance")
                + (designContext.isBlank() ? "" : "\nConfirmed package design context (read-only and frozen at Task confirmation):"
                + RolePromptResources.read("prompt.v1.TaskExecutionPromptFactory.design-context-guidance")
                + "\n----- BEGIN CONFIRMED DESIGN -----\n" + designContext + "\n----- END CONFIRMED DESIGN -----")
                + RolePromptResources.read("prompt.v1.TaskExecutionPromptFactory.language-guidance") + recovery;
    }

    LoopSpec.StageSpec stageContract(LoopSpec spec, StageRow stage) {
        if (stage.packageRunId() == null) {
            if (stage.ordinal() < 0 || stage.ordinal() >= spec.stages().size()) throw missingContract();
            return spec.stages().get(stage.ordinal());
        }
        String frozen = mapper.frozenStageContract(stage.id()).orElseThrow(TaskExecutionPromptFactory::missingContract);
        var contract = json.readValue(frozen, LoopSpec.StageSpec.class);
        if (!contract.objective().equals(stage.objective()) || !java.util.Objects.equals(contract.workPackageId(), stage.workPackageId())
                || !json.valueToTree(contract.verifiers()).equals(json.readTree(stage.verifiersJson()))) throw missingContract();
        return contract;
    }
    private static TaskFailure missingContract() {
        return new TaskFailure("STAGE_CONTRACT_MISSING", "当前阶段缺少匹配的冻结合同，不能使用历史阶段序号推断新计划");
    }

    String todoInstructions() {
        return RolePromptResources.read("prompt.v1.TaskExecutionPromptFactory.block01");
    }

    private String designContext(String taskId, StageRow stage) {
        if (stage.packageRunId() != null) {
            var frozen = mapper.frozenStageDesign(stage.id());
            if (frozen.isPresent()) {
                String content = frozen.get();
                return content.length() <= MAX_DESIGN_CONTEXT_CHARS ? content : content.substring(0, MAX_DESIGN_CONTEXT_CHARS)
                        + "\n… 冻结设计正文已截断；完整设计保留在关联工作包，阶段合同与冻结需求读取仍然有效。";
            }
        }
        if (stage.workPackageId() != null && !stage.workPackageId().isBlank()) {
            return packageContext(taskId, stage.workPackageId());
        }
        String content = mapper.findFirstTaskArtifactByKind(taskId, DESIGN_CONTEXT)
                .map(TaskArtifactRow::content).orElse("");
        if (content.length() <= MAX_DESIGN_CONTEXT_CHARS) return content;
        return content.substring(0, MAX_DESIGN_CONTEXT_CHARS)
                + "\n… confirmed design context truncated for this execution prompt; the complete snapshot remains persisted on the Task …";
    }

    private String packageContext(String taskId, String workPackageId) {
        List<TaskArtifactRow> artifacts = mapper.listTaskArtifacts(taskId);
        String design = artifacts.stream().filter(artifact -> PACKAGE_DESIGN.equals(artifact.kind()))
                .filter(artifact -> workPackageId.equals(metadataText(artifact, "workPackageId")))
                .map(TaskArtifactRow::content).findFirst().orElse("");
        TaskArtifactRow decomposition = artifacts.stream()
                .filter(artifact -> DECOMPOSITION_CONTEXT.equals(artifact.kind())).findFirst().orElse(null);
        List<String> dependencies = new ArrayList<>();
        String globalConstraints = "[]";
        if (decomposition != null) {
            JsonNode root = decomposition(decomposition);
            globalConstraints = root.path("globalConstraints").toString();
            boolean packageFound = false;
            for (JsonNode item : root.path("workPackages")) {
                if (!item.isObject() || !item.path("id").isTextual()) invalidDecomposition();
                if (!workPackageId.equals(item.path("id").asText())) continue;
                packageFound = true;
                if (!item.path("dependencies").isArray()) invalidDecomposition();
                for (JsonNode dependency : item.path("dependencies")) {
                    if (!dependency.isTextual() || dependency.asText().isBlank()) invalidDecomposition();
                    dependencies.add(dependency.asText());
                }
            }
            if (!packageFound) {
                throw new TaskFailure("DECOMPOSITION_CONTEXT_INVALID",
                        "Persisted decomposition context does not contain the current work package");
            }
        }
        String handoffs = artifacts.stream().filter(artifact -> PACKAGE_HANDOFF.equals(artifact.kind()))
                .filter(artifact -> dependencies.contains(metadataText(artifact, "workPackageId")))
                .map(artifact -> metadataText(artifact, "workPackageId") + ": "
                        + metadataText(artifact, "handoffSummary"))
                .collect(java.util.stream.Collectors.joining("\n"));
        return "Work package: " + workPackageId + "\nGlobal constraints: " + globalConstraints
                + "\nPrerequisite package handoffs:\n" + (handoffs.isBlank() ? "none" : handoffs)
                + "\n----- BEGIN CURRENT PACKAGE DESIGN -----\n" + design
                + "\n----- END CURRENT PACKAGE DESIGN -----";
    }

    private JsonNode decomposition(TaskArtifactRow artifact) {
        try {
            JsonNode root = json.readTree(artifact.content());
            if (root == null || !root.isObject() || !root.path("globalConstraints").isArray()
                    || !root.path("workPackages").isArray()) invalidDecomposition();
            return root;
        } catch (TaskFailure failure) {
            throw failure;
        } catch (Exception invalid) {
            throw new TaskFailure("DECOMPOSITION_CONTEXT_INVALID",
                    "Persisted decomposition context cannot be read safely");
        }
    }

    private void invalidDecomposition() {
        throw new TaskFailure("DECOMPOSITION_CONTEXT_INVALID",
                "Persisted decomposition context has an invalid object shape");
    }

    private String metadataText(TaskArtifactRow artifact, String field) {
        try {
            return json.readTree(artifact.metadataJson()).path(field).asText("");
        } catch (Exception unreadable) {
            return "";
        }
    }

    private List<String> readStringList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return json.readValue(value, new TypeReference<>() { });
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }
}
