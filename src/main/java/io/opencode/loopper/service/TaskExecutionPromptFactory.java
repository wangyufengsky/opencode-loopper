package io.opencode.loopper.service;

import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.domain.TestPolicy;
import io.opencode.loopper.persistence.LoopperTaskMapper;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskArtifactRow;
import io.opencode.loopper.persistence.TaskRow;
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

    TaskExecutionPromptFactory(LoopperTaskMapper mapper, ObjectMapper json, RolePromptComposer rolePrompts) {
        this.mapper = mapper;
        this.json = json;
        this.rolePrompts = rolePrompts;
    }

    String prompt(TaskRow task, LoopSpec spec, StageRow stage, Path workspace, String recovery) {
        if (stage.ordinal() < 0 || stage.ordinal() >= spec.stages().size()) {
            throw new TaskFailure("STAGE_CONTRACT_MISSING", "Current Stage has no matching frozen StageSpec");
        }
        LoopSpec.StageSpec stageContract = spec.stages().get(stage.ordinal());
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
                + "\nAll reads, writes, AgentBridge tool calls, searches, and commands must target this checkout and its current Task branch."
                + "\nDo not switch branches, create another worktree, or write outside this workspace."
                + "\nGoal: " + spec.goal() + "\nContext: " + spec.context() + "\nStage: " + stageContract.objective()
                + "\nAuthoritative current StageSpec (including acceptance criteria, Judge rubrics and runtime):\n"
                + json.writeValueAsString(stageContract)
                + "\nImplement every current acceptance criterion, including JUDGE/BOTH criteria. "
                + "Frozen design and retry summaries explain prior decisions; this StageSpec owns current acceptance. "
                + "Do not weaken tests or alter acceptance merely to obtain a pass."
                + "\nLoopper starts and stops verificationRuntime during verification and allocates {{LOOPPER_PORT}}. "
                + "Make the declared startup and readiness contract work; do not leave a competing service running."
                + (designContext.isBlank() ? "" : "\nConfirmed package design context (read-only and frozen at Task confirmation):"
                + "\nUse this snapshot to preserve architecture, implementation decisions, risks, and acceptance rationale. "
                + "If it conflicts with Goal, Context, Stage, path rules, Deliverables, or current StageSpec, the structured LoopSpec and current StageSpec are authoritative."
                + "\n----- BEGIN CONFIRMED DESIGN -----\n" + designContext + "\n----- END CONFIRMED DESIGN -----")
                + "\nLanguage requirement: 使用简体中文撰写面向用户的进度说明、结论、评审和最终总结。"
                + "代码、命令、路径、标识符、JSON 字段名、协议枚举值以及要求精确匹配的字面量保持原样；"
                + "仅当用户目标明确要求其他语言时才切换语言。报告实际修改、验证命令和结果及未解决项；不得把计划或 Todo 状态当作验收证据。\n" + recovery;
    }

    String todoInstructions() {
        return """

                OpenCode Todo is available for this implementation Session. It is a non-authoritative progress
                projection only: Task/Stage/Attempt/Verifier/Judge state remains controlled by Loopper. When the
                work has three or more meaningful steps, use todowrite to keep a concise plan with exactly one
                IN_PROGRESS item, include focused tests and verification, mark an item COMPLETED only after both
                work and its verification finish, and leave blockers visible instead of claiming completion.
                """;
    }

    private String designContext(String taskId, StageRow stage) {
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
