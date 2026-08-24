package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.*;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Extracts only standalone repository-relative path rules from Designer-owned prose. */
final class DesignerAcceptancePathPolicy {
    private static final Pattern HAN_ONLY_SEGMENTS = Pattern.compile("\\p{IsHan}+(?:/\\p{IsHan}+)+");

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

    private boolean isStandaloneRule(String candidate) {
        if (candidate == null || candidate.isBlank()) return false;
        String value = candidate.trim().replace('\\', '/').replaceFirst("^\\./+", "");
        if (value.length() > 512 || value.startsWith("/") || value.startsWith("~/")
                || value.equals("..") || value.startsWith("../") || value.contains("/../")) return false;
        if (value.chars().anyMatch(Character::isWhitespace)
                || value.matches(".*[：；，。！？（）【】《》<>|\"'`].*")) return false;
        if (HAN_ONLY_SEGMENTS.matcher(value).matches()) return false;
        if (!(value.contains("/") || value.contains("*") || value.contains("?")
                || value.matches(".*\\.[A-Za-z0-9]{1,16}$"))) return false;
        for (String segment : value.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) return false;
            if (!segment.isEmpty() && !segment.matches("[\\p{L}\\p{N}._@+*?\\[\\]{}$-]+")) return false;
        }
        return true;
    }
}
