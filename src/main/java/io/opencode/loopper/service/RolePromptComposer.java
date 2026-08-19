package io.opencode.loopper.service;

import io.opencode.loopper.domain.TestPolicy;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class RolePromptComposer {
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

    public String compilerInstructions(TaskProfileService.View profile) {
        RolePackRegistry.RolePack pack = new RolePackRegistry.RolePack(profile.rolePackId(), profile.rolePackVersion(),
                profile.rolePackId(), profile.executionStrategy(), profile.testPolicy());
        return compilerInstructions(pack, profile.technologies(), profile.testPolicy());
    }
}
