package io.opencode.loopper.service;

import io.opencode.loopper.template.SourceTestProfile;
import java.util.*;
import java.util.function.Function;

/** Shared mandatory test-only change policy; callers supply authenticated before/after content. */
public final class SourceTestScope {
    private SourceTestScope(){ }
    public static void check(Map<String,SourceTestTree.File> before,Map<String,SourceTestTree.File> after,SourceTestProfile profile,
            Function<String,String> currentText,Function<String,String> originalText) {
        var names = new TreeSet<String>(before.keySet()); names.addAll(after.keySet());
        var violations = new ArrayList<String>();
        for (String path : names) {
            var old = before.get(path); var current = after.get(path);
            if (Objects.equals(old, current)) continue;
            if (current == null) { violations.add(path + "：禁止删除或重命名已有文件"); continue; }
            if (!current.kind().equals("FILE") || old != null && !old.kind().equals("FILE")) {
                violations.add(path + "：不允许创建或改变符号链接及特殊文件"); continue;
            }
            boolean test = test(path, profile);
            if (!writable(path, profile)) {
                violations.add(path + "：不属于冻结测试源码或夹具范围"); continue;
            }
            if (test) {
                if (current.size() > SourceTreeCapture.MAX_FILE_BYTES) { violations.add(path + "：测试文件超过校验上限"); continue; }
                String value = currentText.apply(path);
                String original = "";
                if (old != null) {
                    original = originalText.apply(path);
                    if (original == null || !SourceExistingTests.preserved(path, original, value))
                        violations.add(path + "：已有测试正文或断言被移除或改写，请保留已有测试，用新增测试文件补齐场景");
                }
                if (original == null) original="";
                if (disabledCount(value) > disabledCount(original)) violations.add(path + "：检测到屏蔽测试执行的语法");
            }
        }
        if (!violations.isEmpty()) throw new io.opencode.loopper.domain.TaskFailure("SOURCE_TEST_WRITE_RANGE_VIOLATION",
                "单元测试模板范围检查未通过：" + String.join("；", violations.stream().limit(20).toList()));
    }
    public static boolean fixture(String path,SourceTestProfile profile){return profile.modules().stream().flatMap(m->m.fixtureRoots().stream()).anyMatch(root->SourceTestProfiles.beneath(path,root));}
    public static boolean writable(String path,SourceTestProfile profile){boolean fixture=fixture(path,profile);return (fixture||test(path,profile))&&SourcePathPolicy.exclusion(path,fixture)==null;}
    public static boolean test(String path,SourceTestProfile profile){return profile.modules().stream().anyMatch(m->m.testRoots().stream().anyMatch(root->SourceTestProfiles.beneath(path,root))&&testSource(path,m.framework()));}
    private static boolean testSource(String path, String framework) {
        if (Set.of("junit", "testng").contains(framework)) return path.matches(".*\\.(java|kt|scala)$");
        if (framework.equals("pytest")) return path.endsWith(".py");
        return SourcePathPolicy.testPath(path) && path.matches(".*\\.[jt]sx?$");
    }
    public static boolean disabled(String value) {
        return disabledCount(value) > 0;
    }
    private static long disabledCount(String value) {
        return java.util.regex.Pattern.compile("@(?:[\\w$]+\\.)*(?:Disabled|Ignore)\\b|enabled\\s*=\\s*false|\\b(?:describe|it|test)\\.(?:skip|only)\\b|\\b(?:xdescribe|xit|xtest)\\s*\\(|pytest\\.mark\\.(?:skip|xfail)|unittest\\.skip").matcher(value).results().count();
    }
}
