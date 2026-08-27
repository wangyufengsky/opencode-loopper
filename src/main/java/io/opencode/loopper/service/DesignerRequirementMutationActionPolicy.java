package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.MutationOperation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Binds requirement mutation verbs to repository paths without treating business nouns as commands. */
final class DesignerRequirementMutationActionPolicy {
    private static final List<String> WRITE_TERMS = List.of(
            "新增", "修改", "实现", "写入", "创建", "更新", "调整", "补齐", "改造", "生成", "增加",
            "修复", "变更", "替换", "编辑");
    private static final List<String> DELETE_TERMS = List.of("删除", "移除");
    private static final List<String> MOVE_TERMS = List.of("移动", "重命名", "迁移");
    private static final List<String> BUSINESS_PREDICATES = List.of("实现", "支持", "增加", "新增", "创建", "生成");
    private static final List<String> BUSINESS_NOUN_SUFFIXES = List.of(
            "能力", "功能", "逻辑", "标记", "任务", "流程", "机制", "语义", "组件", "服务", "处理", "脚本");
    private static final List<String> ACTION_CONNECTORS = List.of("并", "且", "后", "然后", "再", "同时", "随后");
    private static final List<MutationTerm> TERMS = terms();

    MutationOperation operation(String clause, List<String> paths) {
        List<LocatedTerm> located = locatedTerms(clause, paths);
        if (located.isEmpty()) return null;
        int firstPath = firstPathIndex(clause, paths);
        return located.stream().filter(term -> firstPath < 0 || term.index() < firstPath)
                .max(java.util.Comparator.comparingInt(LocatedTerm::index))
                .orElse(located.getFirst()).operation();
    }

    boolean hasMultipleOperations(String clause, List<String> paths) {
        if (paths == null || paths.isEmpty()) return false;
        Set<MutationOperation> operations = new LinkedHashSet<>();
        locatedTerms(clause, paths).forEach(term -> operations.add(term.operation()));
        return operations.size() > 1;
    }

    boolean hasWriteAction(String value) {
        String normalized = normalize(value);
        return WRITE_TERMS.stream().anyMatch(normalized::contains);
    }

    private static int firstPathIndex(String clause, List<String> paths) {
        if (clause == null || paths == null || paths.isEmpty()) return -1;
        int result = Integer.MAX_VALUE;
        for (String path : paths) {
            int index = clause.indexOf(path);
            if (index >= 0) result = Math.min(result, index);
        }
        return result == Integer.MAX_VALUE ? -1 : result;
    }

    private static List<LocatedTerm> locatedTerms(String value, List<String> paths) {
        String normalized = normalize(value);
        List<LocatedTerm> result = new ArrayList<>();
        for (MutationTerm candidate : TERMS) {
            int from = 0;
            while (from < normalized.length()) {
                int index = normalized.indexOf(candidate.term(), from);
                if (index < 0) break;
                if (!insidePath(value, index, candidate, paths)
                        && !businessNoun(normalized, index, candidate, paths)) {
                    result.add(new LocatedTerm(index, candidate.operation()));
                }
                from = index + candidate.term().length();
            }
        }
        result.sort(java.util.Comparator.comparingInt(LocatedTerm::index));
        return List.copyOf(result);
    }

    private static boolean insidePath(String clause, int termIndex, MutationTerm term, List<String> paths) {
        if (clause == null || paths == null) return false;
        for (String path : paths) {
            int pathIndex = clause.indexOf(path);
            if (pathIndex < 0 || termIndex < pathIndex || termIndex >= pathIndex + path.length()) continue;
            if (!path.matches("\\p{IsHan}+(?:/\\p{IsHan}+)+")) return true;
            int relative = termIndex - pathIndex;
            int coreStart = hanSlashCoreStart(path);
            int coreEnd = hanSlashCoreEnd(path);
            return relative >= coreStart && relative + term.term().length() <= coreEnd;
        }
        return false;
    }

    private static int hanSlashCoreStart(String symbol) {
        String left = symbol.substring(0, symbol.indexOf('/'));
        for (String term : WRITE_TERMS) {
            if (left.startsWith(term) && left.length() > term.length()) return term.length();
        }
        return 0;
    }

    private static int hanSlashCoreEnd(String symbol) {
        int slash = symbol.lastIndexOf('/');
        int selected = symbol.length();
        for (String connector : ACTION_CONNECTORS) {
            int index = symbol.lastIndexOf(connector);
            if (index <= slash) continue;
            String tail = symbol.substring(index + connector.length());
            if (TERMS.stream().anyMatch(term -> tail.startsWith(term.term()))) selected = Math.min(selected, index);
        }
        return selected;
    }

    private static boolean businessNoun(String clause, int index, MutationTerm term, List<String> paths) {
        if (term.operation() == MutationOperation.WRITE) return false;
        if (obviousBusinessNoun(clause, index, term, paths)) return true;
        if (purposeBusinessNoun(clause, index, term, paths)) return true;
        if (governsPath(clause, index + term.term().length(), paths)) return false;
        if (index > 0 && clause.charAt(index - 1) == '的') return true;
        if (precededByBusinessPredicate(clause, index)) return true;
        String suffix = clause.substring(index + term.term().length());
        int writeIndex = firstIndexOfAny(suffix, WRITE_TERMS);
        if (writeIndex < 0 || writeIndex > 24) return false;
        String between = suffix.substring(0, writeIndex);
        return between.isEmpty() || !containsAny(between, ACTION_CONNECTORS);
    }

    private static boolean obviousBusinessNoun(
            String clause,
            int index,
            MutationTerm term,
            List<String> paths
    ) {
        if (paths == null) return false;
        int actionEnd = index + term.term().length();
        int pathIndex = paths.stream().mapToInt(path -> clause.indexOf(path, actionEnd))
                .filter(candidate -> candidate >= 0).min().orElse(-1);
        if (pathIndex < 0) return false;
        String between = clause.substring(actionEnd, pathIndex).strip();
        String suffix = BUSINESS_NOUN_SUFFIXES.stream().filter(between::startsWith).findFirst().orElse(null);
        if (suffix == null) return false;
        String afterNoun = between.substring(suffix.length());
        return precededByBusinessPredicate(clause, index)
                || WRITE_TERMS.stream().anyMatch(afterNoun::contains);
    }

    private static boolean purposeBusinessNoun(
            String clause,
            int index,
            MutationTerm term,
            List<String> paths
    ) {
        if (paths == null || term.operation() != MutationOperation.MOVE_SOURCE) return false;
        int actionEnd = index + term.term().length();
        int pathIndex = paths.stream().mapToInt(path -> clause.indexOf(path, actionEnd))
                .filter(candidate -> candidate >= 0).min().orElse(-1);
        if (pathIndex < 0) return false;
        String between = clause.substring(actionEnd, pathIndex).strip();
        if (WRITE_TERMS.stream().noneMatch(between::startsWith)) return false;
        String prefix = clause.substring(Math.max(0, index - 24), index);
        return prefix.endsWith("为数据库") || prefix.endsWith("为了数据库")
                || prefix.endsWith("为数据") || prefix.endsWith("为了数据");
    }

    private static boolean governsPath(String clause, int actionEnd, List<String> paths) {
        if (paths == null) return false;
        for (String path : paths) {
            int pathIndex = clause.indexOf(path, actionEnd);
            if (pathIndex < 0) continue;
            String between = clause.substring(actionEnd, pathIndex).strip();
            if (between.length() <= 64
                    && !hasFollowingMutationAction(between)) return true;
        }
        return false;
    }

    private static boolean hasFollowingMutationAction(String between) {
        for (MutationTerm mutationTerm : TERMS) {
            int index = between.indexOf(mutationTerm.term());
            while (index >= 0) {
                String prefix = between.substring(0, index);
                if (ACTION_CONNECTORS.stream().anyMatch(prefix::endsWith)) return true;
                index = between.indexOf(mutationTerm.term(), index + mutationTerm.term().length());
            }
        }
        return false;
    }

    private static boolean precededByBusinessPredicate(String clause, int index) {
        int predicateIndex = -1;
        String predicate = null;
        for (String candidate : BUSINESS_PREDICATES) {
            int found = clause.lastIndexOf(candidate, index - 1);
            if (found > predicateIndex) {
                predicateIndex = found;
                predicate = candidate;
            }
        }
        if (predicateIndex < 0 || index - predicateIndex > 24) return false;
        String between = clause.substring(predicateIndex + predicate.length(), index);
        return !between.contains("/") && !containsAny(between, ACTION_CONNECTORS);
    }

    private static int firstIndexOfAny(String value, List<String> terms) {
        return terms.stream().mapToInt(value::indexOf).filter(index -> index >= 0).min().orElse(-1);
    }

    private static boolean containsAny(String value, List<String> terms) {
        return terms.stream().anyMatch(value::contains);
    }

    private static List<MutationTerm> terms() {
        List<MutationTerm> result = new ArrayList<>();
        WRITE_TERMS.forEach(term -> result.add(new MutationTerm(term, MutationOperation.WRITE)));
        DELETE_TERMS.forEach(term -> result.add(new MutationTerm(term, MutationOperation.DELETE_REQUEST)));
        MOVE_TERMS.forEach(term -> result.add(new MutationTerm(term, MutationOperation.MOVE_SOURCE)));
        return List.copyOf(result);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record MutationTerm(String term, MutationOperation operation) { }
    private record LocatedTerm(int index, MutationOperation operation) { }
}
