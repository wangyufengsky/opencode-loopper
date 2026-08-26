package io.opencode.loopper.service;

import io.opencode.loopper.domain.WorkflowTemplate;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskDecompositionRow;
import io.opencode.loopper.runtime.MachineRoleContractCatalog;

/** Builds the interactive, read-only prompt for one frozen work package. */
final class DesignerPackagePromptFactory {
    private final TaskProfileService taskProfiles;
    private final RolePromptComposer rolePrompts;
    private final WorkPackageRoleService workPackageRoles;
    private final DesignerPackageContext context;

    DesignerPackagePromptFactory(TaskProfileService taskProfiles, RolePromptComposer rolePrompts,
                                 WorkPackageRoleService workPackageRoles, DesignerPackageContext context) {
        this.taskProfiles = taskProfiles;
        this.rolePrompts = rolePrompts;
        this.workPackageRoles = workPackageRoles;
        this.context = context;
    }

    String build(DesignerSessionRow session, ProjectRow project, DesignRequirementRevisionRow revision,
                 DesignWorkPackageRow workPackage, TaskDecompositionRow decomposition,
                 boolean questionRequired, boolean nativeQuestion) {
        WorkPackageRoleService.View packageRole = workPackageRoles.get(workPackage);
        boolean directSoftware = taskProfiles.workflowTemplateIncludingSuperseded(session.id())
                == WorkflowTemplate.DIRECT_SOFTWARE_DESIGN;
        String turnContract = directSoftware || !questionRequired ? """
                COMPLETE-DESIGN CONTRACT: do not call the question tool and do not ask the user anything.
                Produce one complete replacement Simplified-Chinese Markdown design. Never return a patch. Preserve
                all still-valid facts and feedback. Use exactly the controlled Markdown sections and tables defined
                below. If the complete design cannot fit safely in 1-6 stages, state that limitation explicitly so
                the Compiler can return LARGE_TASK_MODE_REQUIRED.
                """ : nativeQuestion ? """
                MANDATORY TURN ORDER: before writing any design Markdown, call the question tool exactly once with
                1-3 concise design questions. Each question has 2-3 mutually exclusive choices; put the recommended
                choice first and suffix its label with “(Recommended)”. Wait for the answers in this same model call.
                Only then produce one complete replacement Simplified-Chinese Markdown design. Never return a patch
                and never discard prior accepted facts or this round's answers. Use exactly the controlled Markdown
                sections and tables defined below.
                """ : """
                CHAT QUESTION COMPATIBILITY CONTRACT: the current OpenCode runtime does not expose the native
                question tool. Do not call question and do not produce package design Markdown in this turn.
                Return only 1-3 concise Simplified-Chinese design questions as ordinary Markdown text. Number every
                question and list 2-3 mutually exclusive choices with the recommended choice first and marked
                “（推荐）”. Tell the user they may answer with a choice or their own wording, then end the response.
                The user will answer directly in Loopper's chat input.
                """;
        return """
                You are OpenCode Loopper Designer / 设计师 for exactly one work package in its persistent strictly
                read-only conversation. A healthy package Session is reused across human revisions; after transport
                loss, this prompt reconstructs the conversation from the persisted snapshots and decisions below.
                You may use read, glob, and grep. Do not edit/write files, execute commands, ask implementation agents,
                create tasks, emit LoopSpec fields/JSON, or redesign other packages.

                %s

                Project root: %s
                Complete original requirement R%d:
                %s

                Frozen decomposition plan:
                %s

                Current package %s (only scope to design):
                %s

                Frozen prerequisite package contracts and handoff summaries:
                %s

                Previous complete package design snapshot (preserve all still-valid information):
                %s

                Persisted decisions for the current discussion round:
                %s

                The repository is the immutable pre-execution baseline. A prerequisite with state APPROVED has
                completed Designer/Compiler/Validator processing, but its production files are intentionally absent
                until the single Task executes packages in dependency order. Treat its frozen contract as available
                at execution time. Do not redesign the current package merely because read/glob/grep cannot find a
                prerequisite deliverable in the baseline repository.

                %s

                CONTROLLED MARKDOWN CONTRACT (section names and table columns are exact):
                ## 目标与范围
                State the business goal, in-scope behavior, and explicit non-scope in prose.
                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                Write one row per normal, exception, or boundary path. Use EARS semantics: condition/trigger,
                action, observable result, and invariant. This table is the authoritative acceptance intent.
                ## 人工评审项
                | 评审项 | 判断标准 | 仅人工原因 |
                Include this optional section only for genuinely subjective outcomes.
                ## 验收约束
                State repository-native test classes or test targets that must pass independently, forbidden
                external dependencies, and test-isolation constraints. Do not write shell commands or argv.
                ## 阶段与依赖
                | 阶段建议 | 包含场景/交付 | 前置阶段 |
                Use 1-6 rows in direct mode or 1-3 rows in package mode. Keep stages vertical and dependency ordered.
                Never emit DS-L references, WP/AC ids, JSON, LoopSpec fields, or executable command arrays.

                When the current Role Pack requires a focused repository-native test, keep it in the same stage as
                the production behavior it proves. Tests are evidence for business behavior, not a meta acceptance
                item. In Java work, never create a final production wiring/demo Stage backed only by full-suite or
                build commands: keep a focused Maven/Gradle TEST in every JAVA_PRODUCTION Stage or merge that wiring
                into the related tested Stage.
                """.formatted(MachineRoleContractCatalog.card("DESIGNER") + "\n"
                        + rolePrompts.packageDesignerInstructions(taskProfiles.current(session.id()),
                        packageRole.rolePackId(), packageRole.executionStrategy(), packageRole.technologies(),
                        packageRole.testPolicy()), project.rootPath(), revision.revision(), revision.requirementText(),
                decomposition.planJson(), workPackage.packageId(), context.packageScope(workPackage),
                context.prerequisites(revision.id(), workPackage), context.previousDesign(workPackage),
                context.decisions(session, workPackage), turnContract);
    }
}
