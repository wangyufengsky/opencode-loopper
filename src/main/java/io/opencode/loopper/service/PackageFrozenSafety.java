package io.opencode.loopper.service;

import java.util.List;
import java.util.regex.Pattern;

/** V2 reuses the task safety classifier; explicit frozen restrictions and requested external actions remain separate evidence. */
final class PackageFrozenSafety {
    private static final Pattern RESTRICTED = Pattern.compile(
            "(?:只|仅)允许[^。；;\\n]{0,16}只读|禁止[^。；;\\n]{0,16}(?:外部|发布|推送)"
                    + "|(?:不允许|不得)[^。；;\\n]{0,16}(?:外部|发布|推送)");
    private PackageFrozenSafety() { }

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
}
