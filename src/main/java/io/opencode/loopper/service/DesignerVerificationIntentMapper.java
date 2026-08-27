package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves declared test evidence to scenarios from structured design relationships, not example names. */
final class DesignerVerificationIntentMapper {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]{2,}");
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]{2,}");
    private static final Pattern CLAUSE = Pattern.compile("[。；;\\n]");
    private static final Pattern NEGATIVE = Pattern.compile(
            "(?i)(不变|保持原样|保持不变|不得|禁止|不允许|不新增|不修改|不重写|不删除|不引入|无需|排除|forbid|must not|do not|unchanged)");
    private static final Set<String> GENERIC_IDENTIFIERS = Set.of(
            "test", "tests", "spec", "core", "unit", "integration", "behavior", "contract",
            "acceptance", "aggregate", "suite", "src", "main", "java", "python", "result", "context",
            "chain", "node", "module", "normal");
    private static final Set<String> GENERIC_HAN = Set.of(
            "测试", "场景", "行为", "正常", "异常", "执行", "结果", "新增", "生成", "覆盖", "校验", "参数");

    List<TargetMapping> map(Catalog catalog, List<List<String>> targetLists, String design) {
        Integer soleDeclaredTarget = soleDeclaredTarget(catalog, targetLists);
        List<Profile> profiles = new ArrayList<>();
        for (int index = 0; index < targetLists.size(); index++) {
            profiles.add(profile(index, targetLists.get(index), catalog, targetLists, design,
                    soleDeclaredTarget));
        }
        Map<Integer, LinkedHashSet<Integer>> coverage = new LinkedHashMap<>();
        profiles.forEach(profile -> coverage.put(profile.index(), new LinkedHashSet<>()));
        List<Fact> scenarios = catalog.facts().stream().filter(fact -> fact.kind() == FactKind.SCENARIO).toList();
        for (Fact scenario : scenarios) {
            List<Profile> direct = profiles.stream().filter(profile -> directlyMentions(profile, scenario)).toList();
            if (direct.size() == 1) {
                coverage.get(direct.getFirst().index()).add(scenario.index());
                continue;
            }
            if (direct.isEmpty() && soleDeclaredTarget != null) {
                coverage.get(soleDeclaredTarget).add(scenario.index());
                continue;
            }
            int best = 0;
            List<Profile> winners = new ArrayList<>();
            List<Profile> candidates = direct.isEmpty() ? profiles : direct;
            for (Profile profile : candidates) {
                int score = score(profile, scenario);
                if (score > best) {
                    best = score;
                    winners.clear();
                    winners.add(profile);
                } else if (score == best && score > 0) winners.add(profile);
            }
            if (best >= 2 && winners.size() == 1) {
                coverage.get(winners.getFirst().index()).add(scenario.index());
            }
        }
        if (profiles.size() == 1 && coverage.get(0).isEmpty() && !scenarios.isEmpty()) {
            coverage.get(0).addAll(scenarios.stream().map(Fact::index).toList());
        }
        List<TargetMapping> result = new ArrayList<>();
        for (Profile profile : profiles) {
            result.add(new TargetMapping(List.copyOf(coverage.get(profile.index())), profile.mandatory()));
        }
        return List.copyOf(result);
    }

    private static boolean directlyMentions(Profile profile, Fact fact) {
        Set<String> identifiers = identifiers(factText(fact));
        return profile.aliases().stream().anyMatch(identifiers::contains);
    }

    List<String> positiveEvidence(Catalog catalog, String design) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Fact fact : catalog.facts()) {
            if (fact.kind() == FactKind.DELIVERABLE && positive(fact.detail())) {
                result.add(fact.title() + " " + value(fact.detail()));
            } else if (fact.kind() == FactKind.POLICY) {
                positiveClauses(fact.detail()).forEach(result::add);
            }
        }
        for (StageHint hint : catalog.stageHints()) {
            positiveClauses(hint.title() + " " + hint.objective()).forEach(result::add);
        }
        positiveClauses(design).forEach(result::add);
        return List.copyOf(result);
    }

    private Profile profile(int index, List<String> targets, Catalog catalog,
                            List<List<String>> allTargets, String design, Integer soleDeclaredTarget) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        for (String target : targets) aliases.addAll(aliases(target));
        LinkedHashSet<String> contexts = new LinkedHashSet<>();
        boolean mandatory = false;
        for (Fact fact : catalog.facts()) {
            if (fact.kind() == FactKind.DELIVERABLE && positive(fact.detail())
                    && explicitlyMentions(fact.title() + " " + fact.detail(), targets)) {
                contexts.add(fact.title() + " " + fact.detail());
                mandatory = true;
            } else if (fact.kind() == FactKind.POLICY) {
                for (String clause : positiveClauses(fact.detail())) {
                    if (mentions(clause, targets)) {
                        mandatory |= required(clause);
                        if (mentionCount(clause, allTargets) == 1) contexts.add(clause);
                    }
                }
            }
        }
        for (StageHint hint : catalog.stageHints()) {
            String text = hint.title() + " " + hint.objective();
            boolean named = mentions(text, targets);
            boolean unambiguousAnaphora = soleDeclaredTarget != null && soleDeclaredTarget == index
                    && refersToFocusedTest(text);
            if (!negative(text) && (named || unambiguousAnaphora)) {
                mandatory = true;
                if (unambiguousAnaphora || mentionCount(text, allTargets) == 1) contexts.add(text);
            }
        }
        for (String clause : positiveClauses(design)) {
            if (!mentions(clause, targets)) continue;
            mandatory |= required(clause);
            if (mentionCount(clause, allTargets) == 1 && coverageContext(clause)) contexts.add(clause);
        }
        return new Profile(index, Set.copyOf(aliases), List.copyOf(contexts), mandatory);
    }

    private static Integer soleDeclaredTarget(Catalog catalog, List<List<String>> targetLists) {
        List<Integer> declared = new ArrayList<>();
        for (int index = 0; index < targetLists.size(); index++) {
            List<String> targets = targetLists.get(index);
            boolean present = catalog.facts().stream().filter(fact -> fact.kind() == FactKind.DELIVERABLE)
                    .filter(fact -> positive(fact.detail()))
                    .anyMatch(fact -> explicitlyMentions(fact.title() + " " + fact.detail(), targets));
            if (present) declared.add(index);
        }
        return declared.size() == 1 ? declared.getFirst() : null;
    }

    private static boolean refersToFocusedTest(String text) {
        String value = value(text).toLowerCase(Locale.ROOT);
        return value.contains("聚焦测试") || value.contains("同一测试类")
                || value.contains("该测试类") || value.contains("本测试类")
                || value.contains("focused test");
    }

    private static boolean coverageContext(String text) {
        String value = value(text).toLowerCase(Locale.ROOT);
        if (value.contains("测试风格") || value.contains("style reference")) return false;
        boolean regressionOnly = value.contains("回归") || value.contains("保持通过")
                || value.contains("继续通过") || value.contains("remain passing");
        return !regressionOnly || value.contains("覆盖") || value.contains("cover");
    }

    private int score(Profile profile, Fact fact) {
        Set<String> titleIdentifiers = identifiers(fact.title());
        Set<String> allIdentifiers = identifiers(factText(fact));
        Set<String> titleWords = camelWords(fact.title());
        Set<String> allWords = camelWords(factText(fact));
        int score = 0;
        for (String alias : profile.aliases()) {
            if (titleIdentifiers.contains(alias)) score += 1_000;
            else if (allIdentifiers.contains(alias)) score += 120;
        }
        String factTitle = semanticKey(fact.title());
        for (String context : profile.contexts()) {
            String contextKey = semanticKey(context);
            if (factTitle.length() >= 6 && contextKey.contains(factTitle)) score += 500;
            Set<String> contextIdentifiers = identifiers(context);
            score += intersection(contextIdentifiers, titleIdentifiers) * 80;
            score += intersection(contextIdentifiers, allIdentifiers) * 30;
            Set<String> contextWords = camelWords(context);
            score += intersection(contextWords, titleWords) * 12;
            score += intersection(contextWords, allWords) * 4;
            score += wordAffinity(contextWords, titleWords) * 10;
            score += wordAffinity(contextWords, allWords) * 6;
            score += Math.min(20, intersection(hanBigrams(context), hanBigrams(fact.title()))) * 20;
            score += Math.min(20, intersection(hanBigrams(context), hanBigrams(factText(fact)))) * 2;
        }
        return score;
    }

    private static Set<String> aliases(String target) {
        String value = target == null ? "" : target.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("(?i)\\.(?:[cm]?[jt]sx?|py)$", "");
        int selector = Math.max(value.indexOf('#'), value.indexOf("::"));
        if (selector > 0) value = value.substring(0, selector);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String stripped = value.replaceAll("(?i)(?:tests?|spec)$", "");
        result.add(identifierKey(stripped));
        String subject = stripped.replaceAll(
                "(?i)(?:core|unit|integration|behavior|contract|acceptance|aggregate|suite|normal|happy|success|it)$", "");
        result.add(identifierKey(subject));
        result.removeIf(item -> item.length() < 3 || GENERIC_IDENTIFIERS.contains(item));
        return Set.copyOf(result);
    }

    private static Set<String> identifiers(String text) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher matcher = IDENTIFIER.matcher(value(text));
        while (matcher.find()) {
            String key = identifierKey(matcher.group());
            if (key.length() >= 3 && !GENERIC_IDENTIFIERS.contains(key)) result.add(key);
        }
        return Set.copyOf(result);
    }

    private static Set<String> camelWords(String text) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher matcher = IDENTIFIER.matcher(value(text));
        while (matcher.find()) {
            String split = matcher.group().replaceAll("([a-z0-9])([A-Z])", "$1 $2");
            for (String word : split.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (word.length() >= 4 && !GENERIC_IDENTIFIERS.contains(word)) result.add(word);
            }
        }
        return Set.copyOf(result);
    }

    private static Set<String> hanBigrams(String text) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher matcher = HAN.matcher(value(text));
        while (matcher.find()) {
            String word = matcher.group();
            for (int index = 0; index + 2 <= word.length(); index++) {
                String item = word.substring(index, index + 2);
                if (!GENERIC_HAN.contains(item)) result.add(item);
            }
        }
        return Set.copyOf(result);
    }

    private static List<String> positiveClauses(String text) {
        List<String> result = new ArrayList<>();
        for (String clause : CLAUSE.split(value(text))) {
            String item = clause.trim();
            if (!item.isEmpty() && !negative(item)) result.add(item);
        }
        return List.copyOf(result);
    }

    private static boolean positive(String text) { return !negative(text); }
    private static boolean negative(String text) { return NEGATIVE.matcher(value(text)).find(); }
    private static boolean required(String text) {
        String value = value(text).toLowerCase(Locale.ROOT);
        return value.contains("必须") || value.contains("需要") || value.contains("各自")
                || value.contains("独立") || value.contains("required") || value.contains("must");
    }
    private static boolean mentions(String text, List<String> targets) {
        String key = semanticKey(text);
        return targets.stream().flatMap(target -> aliases(target).stream()).anyMatch(key::contains);
    }
    private static boolean explicitlyMentions(String text, List<String> targets) {
        Set<String> identifiers = identifiers(text);
        return targets.stream().map(DesignerVerificationIntentMapper::targetIdentifier)
                .filter(target -> !target.isEmpty()).anyMatch(identifiers::contains);
    }
    private static String targetIdentifier(String target) {
        String normalized = value(target).replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("(?i)\\.(?:[cm]?[jt]sx?|py)$", "");
        int hash = normalized.indexOf('#');
        int selector = normalized.indexOf("::");
        int end = hash < 0 ? selector : selector < 0 ? hash : Math.min(hash, selector);
        if (end > 0) normalized = normalized.substring(0, end);
        return identifierKey(normalized);
    }
    private static int mentionCount(String text, List<List<String>> targets) {
        int count = 0;
        for (List<String> target : targets) if (mentions(text, target)) count++;
        return count;
    }
    private static int intersection(Set<String> left, Set<String> right) {
        return (int) left.stream().filter(right::contains).count();
    }
    private static int wordAffinity(Set<String> left, Set<String> right) {
        int count = 0;
        for (String first : left) {
            for (String second : right) {
                if (!first.equals(second) && commonPrefix(first, second) >= 5) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }
    private static int commonPrefix(String first, String second) {
        int limit = Math.min(first.length(), second.length());
        int index = 0;
        while (index < limit && first.charAt(index) == second.charAt(index)) index++;
        return index;
    }
    private static String factText(Fact fact) {
        return String.join(" ", List.of(value(fact.title()), value(fact.condition()), value(fact.action()),
                value(fact.expected()), value(fact.invariant()), value(fact.detail())));
    }
    private static String identifierKey(String value) {
        return value(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\p{IsHan}]", "");
    }
    private static String semanticKey(String value) { return identifierKey(value); }
    private static String value(String value) { return value == null ? "" : value; }

    record TargetMapping(List<Integer> coversFactIndexes, boolean mandatory) {
        TargetMapping { coversFactIndexes = List.copyOf(coversFactIndexes); }
    }
    private record Profile(int index, Set<String> aliases, List<String> contexts, boolean mandatory) { }
}
