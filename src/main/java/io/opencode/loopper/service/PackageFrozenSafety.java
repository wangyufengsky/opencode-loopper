package io.opencode.loopper.service;

import java.util.List;
import java.util.regex.Pattern;

/** V2 reuses the task safety classifier; explicit frozen restrictions and requested external actions remain separate evidence. */
final class PackageFrozenSafety {
    private static final Pattern RESTRICTED = Pattern.compile(
            "(?:只|仅)允许[^。；;\\n]{0,16}只读|禁止[^。；;\\n]{0,16}(?:外部|发布|推送)"
                    + "|(?:不允许|不得)[^。；;\\n]{0,16}(?:外部|发布|推送)");
    private PackageFrozenSafety() { }

    /** Collection/deictic file requests need no fabricated path resolution to prove a forbidden operation. */
    static List<String> fileRemovalRequests(String original) {
        if (original == null) return List.of();
        var requests = new java.util.ArrayList<String>();
        var removal = Pattern.compile("(?:删除|移除|移动|重命名|迁移)\\s*(?:(?:全部|所有|现有|既有|当前|该|此|本|这些|这个)\\s*)*"
                + "(?:测试(?:文件|代码|用例)?|文件|目录|源码)");
        var inverted = Pattern.compile("(?:将|把)\\s*(?:该|此|本|当前|这些|这个)(?:测试)?(?:文件|目录|源码)\\s*(?:删除|移除|移动|重命名|迁移)");
        for (var clause : original.split("[。；;\\n\\r，,]|但是|不过|同时|并且")) {
            if (DesignerMutationPolarity.negativeOrExample(clause)) continue;
            var direct = removal.matcher(clause);
            var reverse = inverted.matcher(clause);
            if (direct.find() && taskAction(clause, direct.start(), direct.end())
                    || reverse.find() && taskAction(clause, reverse.start(), reverse.end())) requests.add(clause.strip());
        }
        return List.copyOf(requests);
    }

    private static boolean taskAction(String clause, int start, int end) {
        String before = clause.substring(0, start).strip();
        String after = clause.substring(end).strip();
        if (after.matches("^(?:的)?(?:功能|能力|逻辑|机制|接口|事件|场景|行为|测试|标记).*")) return false;
        return before.isEmpty() || before.matches(".*(?:要求|需要|必须|应当|请|直接|并|然后|随后|且)$");
    }

    static boolean externalConflict(String original) {
        if (original == null || !RESTRICTED.matcher(original).find()) return false;
        // Canonicalize named external objects, not the action/negation scope. Domain event publication is untouched.
        String classified = original.replaceAll("(?i)外部\\s*(?:CRM|ERP|SaaS)\\b", "外部系统")
                .replace("外部生产应用", "外部应用")
                .replace("要求覆盖外部", "要求修改外部")
                .replaceAll("(?i)(?:创建|发布)\\s*GitHub\\s+Release\\b", "发版")
                .replace("上传构建产物", "发布构建产物");
        // Bound matching to sentences so an earlier negated write cannot consume a later positive action.
        var safety = new TaskProfileSafetyPolicy();
        return java.util.Arrays.stream(classified.split("[。；;\\n\\r]"))
                .anyMatch(safety::requestsUnsafeOperation);
    }

    static PackageDesignCompilation.Problem blocked(String code, String detail) {
        return new PackageDesignCompilation.Problem(code, "/frozenInput", detail, List.of(),
                PackageDesignCompilation.ProblemClass.SECURITY, false,
                "请求行为满足冻结权限与服务端策略", "已发现相互冲突的请求与限制", "保留双方证据，通过本地反馈解决；候选表达无法放宽权限");
    }

    static PackageDesignCompilation.Problem internal(String code, String detail) {
        return new PackageDesignCompilation.Problem(code, "/compiledPlan", detail, List.of(),
                PackageDesignCompilation.ProblemClass.SYSTEM, false,
                "服务端编译结果保留完整安全约束", "服务端生成的计划未通过内部一致性检查",
                "保留诊断并修复编译器后重新编译；这不是缺少用户需求，不得通过放宽权限或删除验收来修复");
    }
}
