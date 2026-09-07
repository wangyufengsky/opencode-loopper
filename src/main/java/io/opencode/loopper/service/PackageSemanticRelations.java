package io.opencode.loopper.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded DAG validation; a valid graph proves references and branch coverage, not natural-language truth. */
final class PackageSemanticRelations {
    static final int MAX_NODES = 32;
    static final int MAX_DEPTH = 4;
    record Relation(String key, String operator, List<String> operands, List<String> sourceRefs) { }
    record Issue(String code, String pointer, String detail) { }
    record Graph(List<Relation> relations, Map<String, List<String>> branchScenarios, List<Issue> issues) {
        boolean valid() { return issues.isEmpty(); }
    }

    Graph validate(List<Relation> relations, Set<String> scenarioKeys, Set<String> sourceRefs) {
        if (relations == null) return invalid("PACKAGE_RELATIONS_REQUIRED", "/relations", "关系集合必须是数组；无组合关系时提交空数组");
        if (relations.size() > MAX_NODES) return invalid("PACKAGE_RELATION_LIMIT", "/relations", "最多32个关系节点；请提取独立场景或经本地反馈拆分工作包，不得截断分支");
        List<Issue> issues = new ArrayList<>();
        Map<String, Relation> byKey = index(relations, scenarioKeys, issues);
        for (int index = 0; index < relations.size(); index++) validateNode(relations.get(index), index, byKey, scenarioKeys, sourceRefs, issues);
        if (!issues.isEmpty()) return new Graph(List.of(), Map.of(), List.copyOf(issues));
        Map<String, List<String>> branches = new LinkedHashMap<>();
        for (int index = 0; index < relations.size(); index++) {
            var relation = relations.get(index);
            try {
                LinkedHashSet<String> leaves = new LinkedHashSet<>();
                expand(relation.key(), byKey, scenarioKeys, new HashSet<>(), 1, leaves);
                branches.put(relation.key(), List.copyOf(leaves));
            } catch (InvalidGraph invalid) { issues.add(new Issue(invalid.code, "/relations/" + index + "/operands", invalid.getMessage())); }
        }
        return new Graph(List.copyOf(relations), Map.copyOf(branches), List.copyOf(issues));
    }

    private Map<String, Relation> index(List<Relation> relations, Set<String> scenarios, List<Issue> issues) {
        Map<String, Relation> result = new LinkedHashMap<>();
        for (int index = 0; index < relations.size(); index++) {
            Relation relation = relations.get(index);
            if (relation == null || relation.key() == null || relation.key().isBlank()) {
                issues.add(new Issue("PACKAGE_RELATION_KEY_REQUIRED", "/relations/" + index + "/key", "每个关系需要非空的局部 key"));
            } else if (scenarios.contains(relation.key()) || result.putIfAbsent(relation.key(), relation) != null) {
                issues.add(new Issue("PACKAGE_RELATION_KEY_DUPLICATE", "/relations/" + index + "/key", "关系 key 必须唯一且不能与场景 key 冲突"));
            }
        }
        return result;
    }

    private void validateNode(Relation relation, int index, Map<String, Relation> relations, Set<String> scenarios,
                              Set<String> sources, List<Issue> issues) {
        if (relation == null) return;
        String pointer = "/relations/" + index;
        if (!Set.of("all", "any", "unless").contains(String.valueOf(relation.operator()))) {
            issues.add(new Issue("PACKAGE_RELATION_OPERATOR_INVALID", pointer + "/operator", "只支持 all、any、unless"));
        }
        if (relation.operands() == null || relation.operands().size() < 2 || relation.operands().size() > 32
                || "unless".equals(relation.operator()) && relation.operands().size() != 2) {
            issues.add(new Issue("PACKAGE_RELATION_OPERANDS_INVALID", pointer + "/operands", "all/any需要2至32个分支；unless恰好两个，依次为基础行为和例外行为"));
        } else {
            Set<String> unique = new HashSet<>();
            for (int operand = 0; operand < relation.operands().size(); operand++) {
                String ref = relation.operands().get(operand);
                if (ref == null || !(scenarios.contains(ref) || relations.containsKey(ref)) || !unique.add(ref)) {
                    issues.add(new Issue("PACKAGE_RELATION_REFERENCE_INVALID", pointer + "/operands/" + operand, "每个分支须引用不同的已声明场景或关系；或条件不能只留下一个分支"));
                }
            }
        }
        if (relation.sourceRefs() == null || relation.sourceRefs().isEmpty() || relation.sourceRefs().stream().anyMatch(ref -> ref == null || !sources.contains(ref))) {
            issues.add(new Issue("PACKAGE_RELATION_SOURCE_INVALID", pointer + "/sourceRefs", "关系须引用当前运行冻结的原始需求来源"));
        }
    }

    private void expand(String key, Map<String, Relation> relations, Set<String> scenarios, Set<String> visiting,
                        int depth, LinkedHashSet<String> leaves) {
        if (scenarios.contains(key)) { leaves.add(key); return; }
        if (!visiting.add(key)) throw new InvalidGraph("PACKAGE_RELATION_CYCLE", "关系存在循环；移除回边并保留各分支的实际行为");
        if (depth > MAX_DEPTH) throw new InvalidGraph("PACKAGE_RELATION_DEPTH", "关系最多4层；请提取独立场景或拆分工作包，不能丢弃嵌套条件");
        for (String operand : relations.get(key).operands()) expand(operand, relations, scenarios, visiting, depth + 1, leaves);
        visiting.remove(key);
    }

    private Graph invalid(String code, String pointer, String detail) { return new Graph(List.of(), Map.of(), List.of(new Issue(code, pointer, detail))); }
    private static final class InvalidGraph extends RuntimeException {
        private final String code;
        InvalidGraph(String code, String detail) { super(detail); this.code = code; }
    }
}
