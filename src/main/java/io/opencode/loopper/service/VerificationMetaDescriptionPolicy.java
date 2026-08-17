package io.opencode.loopper.service;

import java.util.Locale;
import java.util.Set;

/**
 * 闭集识别纯验证结果描述。这些描述可以保留为补充验证器，但不能冒充业务验收条件。
 */
final class VerificationMetaDescriptionPolicy {

    private static final Set<String> META_DESCRIPTIONS = Set.of(
            "测试通过",
            "全部测试通过",
            "所有测试通过",
            "全量测试通过",
            "完整测试通过",
            "回归测试通过",
            "全量回归测试通过",
            "构建成功",
            "构建通过",
            "编译成功",
            "编译通过",
            "打包成功",
            "打包通过",
            "项目构建成功",
            "代码编译成功",
            "mvn test通过",
            "maven test通过",
            "gradle test通过",
            "all tests pass",
            "all tests passed",
            "full test suite passes",
            "regression tests pass",
            "build succeeds",
            "build passes",
            "compilation succeeds",
            "compilation passes",
            "package succeeds"
    );

    private VerificationMetaDescriptionPolicy() {
    }

    static boolean isMetaDescription(String value) {
        if (value == null) return false;
        String normalized = value.strip()
                .replaceAll("[。；;.!！]+$", "")
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
        return META_DESCRIPTIONS.contains(normalized);
    }
}
