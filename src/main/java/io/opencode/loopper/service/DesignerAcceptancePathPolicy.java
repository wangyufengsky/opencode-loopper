package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.*;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Extracts only standalone repository-relative path rules from Designer-owned prose. */
final class DesignerAcceptancePathPolicy {
    private static final Pattern HAN_ONLY_SEGMENTS = Pattern.compile("\\p{IsHan}+(?:/\\p{IsHan}+)+");
    private static final Pattern API_ROUTE = Pattern.compile(
            "(?i)^/(?:api|v\\d+)(?:/[a-z0-9._~{}:$-]+)+$");
    private static final List<String> ROUTE_TYPES = List.of("接口", "端点", "路由", "route", "endpoint");
    private static final List<String> POSITIVE_TERMS = List.of(
            "新增", "修改", "实现", "写入", "创建", "更新", "调整", "补齐", "改造", "生成", "增加",
            "修复", "变更", "替换", "编辑", "生产代码", "测试代码", "测试", "配置", "脚本", "文档",
            "文件", "交付", "范围内", "包含");
    private static final List<String> DELETE_TERMS = List.of("删除", "移除");
    private static final List<String> MOVE_TERMS = List.of("移动", "重命名", "迁移");

    List<String> paths(Catalog catalog, List<Integer> factIndexes) {
        LinkedHashSet<Integer> selected = new LinkedHashSet<>(factIndexes == null ? List.of() : factIndexes);
        return catalog.facts().stream()
                .filter(fact -> selected.contains(fact.index()))
                .filter(fact -> fact.kind() == FactKind.DELIVERABLE || fact.kind() == FactKind.SCOPE)
                .map(Fact::title).filter(this::isStandaloneRule).distinct().toList();
    }

    List<String> paths(List<String> candidates) {
        if (candidates == null) return List.of();
        return candidates.stream().filter(this::isStandaloneRule).distinct().toList();
    }

    List<String> precisePaths(Catalog catalog, List<Integer> factIndexes) {
        return paths(catalog, factIndexes).stream().filter(this::isPreciseRule).toList();
    }

    List<String> precisePaths(List<String> candidates) {
        return paths(candidates).stream().filter(this::isPreciseRule).toList();
    }

    List<String> positivePaths(Catalog catalog, List<Integer> factIndexes) {
        LinkedHashSet<Integer> selected = new LinkedHashSet<>(factIndexes == null ? List.of() : factIndexes);
        return catalog.facts().stream().filter(fact -> selected.contains(fact.index()))
                .filter(fact -> mutationSemantics(fact) == MutationFactSemantics.WRITE)
                .map(this::factPath).filter(path -> path != null).distinct().toList();
    }

    List<String> precisePositivePaths(Catalog catalog, List<Integer> factIndexes) {
        return positivePaths(catalog, factIndexes).stream()
                .filter(path -> isPreciseRule(path)
                        || DesignerRepositoryPathSyntax.safeRootDirectory(path)).toList();
    }

    MutationFactSemantics mutationSemantics(Fact fact) {
        if (fact == null || (fact.kind() != FactKind.DELIVERABLE && fact.kind() != FactKind.SCOPE)) {
            return MutationFactSemantics.IRRELEVANT;
        }
        String semantics = declaredMutationText(fact.detail());
        if (DesignerMutationPolarity.negativeOrExample(semantics)) return MutationFactSemantics.NEGATIVE;
        if (containsAny(semantics, DELETE_TERMS)) return MutationFactSemantics.DELETE;
        if (containsAny(semantics, MOVE_TERMS)) return MutationFactSemantics.MOVE;
        return containsAny(semantics, POSITIVE_TERMS)
                ? MutationFactSemantics.WRITE : MutationFactSemantics.IRRELEVANT;
    }

    boolean routeSymbol(Fact fact) {
        if (fact == null || fact.title() == null || !API_ROUTE.matcher(fact.title().strip()).matches()) {
            return false;
        }
        return containsAny(declaredMutationText(fact.detail()), ROUTE_TYPES);
    }

    private String factPath(Fact fact) {
        if (fact == null || fact.title() == null) return null;
        if (isStandaloneRule(fact.title())) return fact.title();
        return DesignerRepositoryPathSyntax.directorySource(declaredMutationText(fact.detail()))
                && DesignerRepositoryPathSyntax.safeRootDirectory(fact.title()) ? fact.title().strip() : null;
    }

    private static String declaredMutationText(String detail) {
        if (detail == null) return "";
        int chineseColon = detail.indexOf('：');
        int asciiColon = detail.indexOf(':');
        int boundary = chineseColon < 0 ? asciiColon
                : asciiColon < 0 ? chineseColon : Math.min(chineseColon, asciiColon);
        return boundary < 0 ? detail : detail.substring(0, boundary);
    }

    private boolean isStandaloneRule(String candidate) {
        if (candidate == null || candidate.isBlank()) return false;
        String value = candidate.trim().replace('\\', '/').replaceFirst("^\\./+", "");
        if (value.length() > 512 || value.startsWith("/") || value.startsWith("~/")
                || value.equals("..") || value.startsWith("../") || value.contains("/../")) return false;
        if (value.chars().anyMatch(Character::isWhitespace)
                || value.matches(".*[：；，。！？（）【】《》<>|\"'`].*")) return false;
        if (HAN_ONLY_SEGMENTS.matcher(value).matches()) return false;
        if (!(value.contains("/") || value.contains("*") || value.contains("?")
                || value.matches(".*\\.[A-Za-z0-9]{1,16}$") || commonRootFile(value))) return false;
        for (String segment : value.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) return false;
            if (!segment.isEmpty() && !segment.matches("[\\p{L}\\p{N}._@+*?\\[\\]{}$-]+")) return false;
        }
        return true;
    }

    private boolean isPreciseRule(String candidate) {
        if (!isStandaloneRule(candidate)) return false;
        String value = candidate.trim().replace('\\', '/').replaceFirst("^\\./+", "");
        int wildcard = firstWildcard(value);
        if (wildcard < 0) return true;
        int slashBeforeWildcard = value.lastIndexOf('/', wildcard);
        if (slashBeforeWildcard < 0) return false;
        String lastSegment = value.substring(value.lastIndexOf('/') + 1);
        String literal = lastSegment.replaceAll("[\\*?\\[\\]{}!,]", "");
        return !literal.isBlank();
    }

    private static int firstWildcard(String value) {
        int result = -1;
        for (char marker : new char[]{'*', '?', '[', '{'}) {
            int index = value.indexOf(marker);
            if (index >= 0 && (result < 0 || index < result)) result = index;
        }
        return result;
    }

    private static boolean commonRootFile(String value) {
        return DesignerRepositoryPathSyntax.commonRootFile(value);
    }

    private static boolean containsAny(String value, List<String> terms) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return terms.stream().map(term -> term.toLowerCase(Locale.ROOT)).anyMatch(normalized::contains);
    }

    enum MutationFactSemantics { WRITE, NEGATIVE, DELETE, MOVE, IRRELEVANT }
}
