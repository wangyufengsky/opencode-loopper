package io.opencode.loopper.service;

import io.opencode.loopper.domain.TestPolicy;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class RolePromptComposer {
    public String requirementDesignerInstructions(TaskProfileService.View profile) {
        String specialized = switch (profile.rolePackId()) {
            case "software-python" -> "Treat this as Python software work. Ask about invocation, input/output, error semantics, portability, and whether repository tests exist. Never introduce Java or Maven assumptions.";
            case "software-node" -> "Treat this as Node/Vue/TypeScript software work. Ask about runtime, package manager, UI/runtime boundary, and the repository's actual test command.";
            case "document-markdown-docx" -> "Treat this as document authoring. Ask about target path/format, audience, required sections, tables, and unsupported images/embedded objects. Do not turn it into a code task.";
            case "tabular-conversion" -> "Treat this as a one-off bounded table conversion. Ask about the exact managed input and output paths, Sheet selection, formulas, merged cells, and empty-row policy. Do not propose a reusable script.";
            case "read-only-report" -> "Treat this as a read-only review/research report. Ask about review scope, severity or research criteria, and required evidence granularity. Do not propose edits or a Task.";
            case "local-maintenance" -> "Treat this as safe local maintenance. Ask for exact files and observable outcome. Explicitly exclude deletion, service control, Git submission/push/release, external applications, and external writes.";
            default -> "Treat this as Java software work only when repository and requirement evidence support Java; require observable behavior and a focused repository-native test.";
        };
        String ambiguity = profile.decisionRequired()
                ? "The Router result is ambiguous. One mandatory question must ask the user to choose the task type/artifact before confirmation."
                : "The frozen provisional profile is authoritative unless the user explicitly overrides it in Loopper.";
        return header(profile) + "\n" + specialized + "\n" + ambiguity;
    }

    public String decomposerInstructions(TaskProfileService.View profile) {
        return header(profile) + "\n" + switch (profile.workflowTemplate()) {
            case PACKAGED_ARTIFACT -> "Decompose by document chapters or coherent audience-facing sections. Each package must produce a structured fragment that can be deterministically aggregated; never split by implementation layer.";
            case LOCAL_MAINTENANCE -> "Use one package for a simple bounded maintenance change. Complex maintenance may use vertical packages, but every package must preserve the no-delete/no-service/no-external-write boundary.";
            default -> profile.rolePackId().equals("software-python")
                    ? "Decompose Python work by usable vertical behavior. A reusable conversion script is software, not a one-off tabular artifact."
                    : profile.rolePackId().equals("software-node")
                    ? "Decompose Node/frontend work by user-visible vertical capability, not by frontend/backend/test layers."
                    : "Decompose software by vertical observable capability and keep production behavior with its focused test.";
        };
    }

    public String packageDesignerInstructions(TaskProfileService.View profile, String rolePackId,
                                              List<String> technologies, TestPolicy testPolicy) {
        RolePackRegistry.RolePack pack = new RolePackRegistry.RolePack(rolePackId, profile.rolePackVersion(), rolePackId,
                profile.executionStrategy(), testPolicy);
        return compilerInstructions(pack, technologies, testPolicy).replace("Role Pack:", "Work-package Role Pack:")
                + "\n" + (rolePackId.equals("document-markdown-docx")
                ? "Design one self-contained document fragment with headings, paragraphs, lists, code blocks and tables; include no executable test."
                : rolePackId.equals("local-maintenance")
                ? "Design only bounded local file maintenance and state forbidDeletes=true; never authorize deletion, services, Git publication, or external writes."
                : "Design only the current package using its detected stack and repository-native evidence.");
    }

    public String reviewerInstructions(TaskProfileService.View profile) {
        return header(profile) + "\nAct as an independent read-only Reviewer. Use only read/glob/grep. Every concrete finding must cite a managed relative file path and exact line as path:line. Separate confirmed findings, limitations, and recommendations. Never emit instructions to modify files or claim a Task was created.";
    }

    public String compilerInstructions(RolePackRegistry.RolePack pack, List<String> technologies,
                                       TestPolicy testPolicy) {
        String stack = technologies == null || technologies.isEmpty() ? "未确定技术栈" : String.join("/", technologies);
        String testing = switch (testPolicy) {
            case REQUIRED -> technologies != null && technologies.stream().anyMatch("python"::equalsIgnoreCase)
                    ? "必须使用仓库识别出的 pytest 或 unittest 给出显式目标的聚焦 TEST；不得注入其他语言的测试示例。"
                    : "必须按已识别测试框架给出聚焦 TEST；不得套用其他语言的测试示例。";
            case OPTIONAL -> "优先使用仓库已有测试；无测试体系的独立脚本可用 SELF_CHECK 与原生制品验收。";
            case NOT_APPLICABLE -> "不得生成 PROCESS TEST；使用专属文档、表格或报告证据。";
        };
        return "Role Pack: " + pack.id() + "@" + pack.version() + "（" + pack.displayName() + "）。\n"
                + "技术/制品上下文：" + stack + "。\n" + testing;
    }

    public String compilerInstructions(String rolePackId, String rolePackVersion,
                                       io.opencode.loopper.domain.ExecutionStrategy executionStrategy,
                                       List<String> technologies, TestPolicy testPolicy) {
        return compilerInstructions(new RolePackRegistry.RolePack(rolePackId, rolePackVersion, rolePackId,
                executionStrategy, testPolicy), technologies, testPolicy);
    }

    public String compilerInstructions(TaskProfileService.View profile) {
        RolePackRegistry.RolePack pack = new RolePackRegistry.RolePack(profile.rolePackId(), profile.rolePackVersion(),
                profile.rolePackId(), profile.executionStrategy(), profile.testPolicy());
        return compilerInstructions(pack, profile.technologies(), profile.testPolicy());
    }

    private static String header(TaskProfileService.View profile) {
        return "Frozen Role Pack " + profile.rolePackId() + "@" + profile.rolePackVersion()
                + "; intent=" + profile.intent() + "; workflow=" + profile.workflowTemplate()
                + "; execution=" + profile.executionStrategy() + "; testPolicy=" + profile.testPolicy() + ".";
    }
}
