package io.opencode.loopper.verification;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared validation and matching semantics for GIT_DIFF path policies.
 *
 * <p>An allow rule is invalid when one forbidden rule definitely covers every
 * path it can accept. This is intentionally narrower than ordinary overlap:
 * allowing {@code src/**} while forbidding {@code src/generated/**} remains a
 * valid allow-with-exclusion policy.</p>
 */
public final class VerifierPathPolicy {
    private static final int MAX_PATH_RULE_LENGTH = 512;
    private static final int MAX_GIT_PATH_LENGTH = 32_768;
    private static final long PATH_POLICY_WORK_BUDGET = 10_000_000L;

    private VerifierPathPolicy() {}

    /** Uses the same bounded matcher as runtime GIT_DIFF verification for one path/rule pair. */
    public static boolean matchesChangedPath(String path, String rule) {
        return matches(path, rule, new SlashGlobMatcher.WorkBudget(PATH_POLICY_WORK_BUDGET));
    }

    /** Applies the runtime slash and leading-dot normalization used for path-policy comparison. */
    public static String normalizePathRule(String rule) {
        return normalized(rule);
    }

    /** Creates one bounded relation evaluator for a complete compile-time path-conservation pass. */
    public static RuleRelations boundedRuleRelations() {
        return new RuleRelations(new SlashGlobMatcher.WorkBudget(PATH_POLICY_WORK_BUDGET));
    }

    public static List<String> validationErrors(String pathPrefix, List<String> allowedPaths,
                                                List<String> forbiddenPaths) {
        List<String> allowed = allowedPaths == null ? List.of() : allowedPaths;
        List<String> forbidden = forbiddenPaths == null ? List.of() : forbiddenPaths;
        List<String> errors = new ArrayList<>();
        SlashGlobMatcher.WorkBudget budget = new SlashGlobMatcher.WorkBudget(PATH_POLICY_WORK_BUDGET);
        boolean budgetExhausted = false;
        for (int index = 0; index < allowed.size(); index++) {
            try {
                validateRule(allowed.get(index), budget);
            } catch (SlashGlobMatcher.WorkLimitExceeded exhausted) {
                budgetExhausted = true;
                break;
            } catch (InvalidPattern invalid) {
                errors.add(pathPrefix + ".allowedPaths[" + index + "]: " + invalid.getMessage());
            } catch (InvalidRule invalid) {
                errors.add(pathPrefix + ".allowedPaths[" + index + "]: " + invalid.getMessage());
            }
        }
        if (!budgetExhausted) {
            for (int index = 0; index < forbidden.size(); index++) {
                try {
                    validateRule(forbidden.get(index), budget);
                } catch (SlashGlobMatcher.WorkLimitExceeded exhausted) {
                    budgetExhausted = true;
                    break;
                } catch (InvalidPattern invalid) {
                    errors.add(pathPrefix + ".forbiddenPaths[" + index + "]: " + invalid.getMessage());
                } catch (InvalidRule invalid) {
                    errors.add(pathPrefix + ".forbiddenPaths[" + index + "]: " + invalid.getMessage());
                }
            }
        }
        if (budgetExhausted) {
            errors.add(pathPrefix + ": path policy exceeds the bounded validation budget");
            return List.copyOf(errors);
        }

        for (int allowedIndex = 0; allowedIndex < allowed.size(); allowedIndex++) {
            String allowedRule = allowed.get(allowedIndex);
            for (int forbiddenIndex = 0; forbiddenIndex < forbidden.size(); forbiddenIndex++) {
                String forbiddenRule = forbidden.get(forbiddenIndex);
                if (definitelyCovers(forbiddenRule, allowedRule)) {
                    errors.add(pathPrefix + ".allowedPaths[" + allowedIndex + "]: rule \""
                            + normalized(allowedRule) + "\" is entirely shadowed by forbiddenPaths["
                            + forbiddenIndex + "] \"" + normalized(forbiddenRule)
                            + "\"; no changed path accepted by this allow rule can satisfy the path policy");
                    break;
                }
            }
        }
        return List.copyOf(errors);
    }

    static boolean matches(String path, String inputRule, SlashGlobMatcher.WorkBudget budget) {
        if (inputRule == null || inputRule.isBlank()) {
            budget.consume(1);
            return false;
        }
        String rule = normalized(inputRule);
        String candidate = normalized(path);
        if (rule.length() > MAX_PATH_RULE_LENGTH || candidate.length() > MAX_GIT_PATH_LENGTH) {
            throw new InvalidRule("path policy exceeds its safety limit");
        }
        if (!containsGlob(rule)) {
            budget.consume(rule.length() + candidate.length() + 1L);
            String prefix = rule.endsWith("/") ? rule : rule + "/";
            return candidate.equals(rule) || candidate.startsWith(prefix);
        }
        try {
            return SlashGlobMatcher.matches(rule, candidate, budget);
        } catch (SlashGlobMatcher.WorkLimitExceeded exhausted) {
            throw exhausted;
        } catch (RuntimeException invalidPattern) {
            throw new InvalidPattern("invalid path pattern \"" + rule + "\"");
        }
    }

    private static void validateRule(String inputRule, SlashGlobMatcher.WorkBudget budget) {
        if (inputRule == null || inputRule.isBlank()) {
            budget.consume(1);
            return;
        }
        String rule = normalized(inputRule);
        if (rule.length() > MAX_PATH_RULE_LENGTH) {
            throw new InvalidRule("path policy exceeds its safety limit");
        }
        if (!containsGlob(rule)) {
            budget.consume(rule.length() + 1L);
            return;
        }
        try {
            SlashGlobMatcher.matches(rule, "", budget);
        } catch (SlashGlobMatcher.WorkLimitExceeded exhausted) {
            throw exhausted;
        } catch (RuntimeException invalidPattern) {
            throw new InvalidPattern("invalid path pattern \"" + rule + "\"");
        }
    }

    private static boolean definitelyCovers(String inputForbidden, String inputAllowed) {
        if (inputForbidden == null || inputForbidden.isBlank()
                || inputAllowed == null || inputAllowed.isBlank()) {
            return false;
        }
        String forbidden = normalized(inputForbidden);
        String allowed = normalized(inputAllowed);
        if (forbidden.equals(allowed)) return true;
        if ("**".equals(forbidden) || "**/*".equals(forbidden)) return true;

        if (!containsGlob(forbidden)) {
            boolean includesRoot = !forbidden.endsWith("/");
            String root = stripTrailingSlashes(forbidden);
            return rootedWithin(allowed, root, includesRoot);
        }
        if (forbidden.endsWith("/**")) {
            String root = stripTrailingSlashes(forbidden.substring(0, forbidden.length() - 3));
            if (!root.isEmpty() && !containsGlob(root)) {
                return rootedWithin(allowed, root, false);
            }
        }
        return false;
    }

    private static String literalRoot(String rule) {
        int wildcard = rule.length();
        for (char marker : new char[]{'*', '?', '[', '{'}) {
            int index = rule.indexOf(marker);
            if (index >= 0) wildcard = Math.min(wildcard, index);
        }
        String prefix = rule.substring(0, wildcard);
        int slash = prefix.lastIndexOf('/');
        return stripTrailingSlashes(slash < 0 ? "" : prefix.substring(0, slash));
    }

    private static boolean rootedWithin(String allowed, String root, boolean includesRoot) {
        if (root.isEmpty()) return false;
        if (includesRoot && allowed.equals(root)) return true;
        return allowed.startsWith(root + "/");
    }

    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') end--;
        return value.substring(0, end);
    }

    private static String normalized(String value) {
        if (value == null) return "";
        return value.replace('\\', '/').replaceAll("^\\./+", "");
    }

    private static boolean containsGlob(String value) {
        return value.indexOf('*') >= 0 || value.indexOf('?') >= 0
                || value.indexOf('[') >= 0 || value.indexOf('{') >= 0;
    }

    /**
     * Proves rule containment and conservative overlap with one shared work budget.
     * Unsupported glob relationships are never accepted as containment and are treated as overlapping.
     */
    public static final class RuleRelations {
        private final SlashGlobMatcher.WorkBudget budget;

        private RuleRelations(SlashGlobMatcher.WorkBudget budget) {
            this.budget = budget;
        }

        public boolean allowedRuleCovers(String requiredRule, String allowedRule) {
            validateRule(requiredRule, budget);
            validateRule(allowedRule, budget);
            String required = normalized(requiredRule);
            String allowed = normalized(allowedRule);
            return definitelyCovers(allowed, required);
        }

        /** Proves that the runtime allow rule accepts one explicitly typed exact repository path. */
        public boolean allowedRuleCoversExactPath(String requiredPath, String allowedRule) {
            validateRule(requiredPath, budget);
            validateRule(allowedRule, budget);
            String required = normalized(requiredPath);
            if (containsGlob(required)) return false;
            return matches(required, allowedRule, budget);
        }

        /** Tests one explicitly typed exact repository path against a runtime path rule. */
        public boolean ruleMatchesExactPath(String exactPath, String rule) {
            validateRule(exactPath, budget);
            validateRule(rule, budget);
            String required = normalized(exactPath);
            if (containsGlob(required)) return true;
            return matches(required, rule, budget);
        }

        public boolean rulesMayOverlap(String leftRule, String rightRule) {
            validateRule(leftRule, budget);
            validateRule(rightRule, budget);
            String left = normalized(leftRule);
            String right = normalized(rightRule);
            if (definitelyCovers(left, right) || definitelyCovers(right, left)) return true;
            boolean leftGlob = containsGlob(left);
            boolean rightGlob = containsGlob(right);
            if (!leftGlob && !rightGlob) {
                return matches(left, right, budget) || matches(right, left, budget);
            }
            if (!leftGlob) return subtreeMayOverlapGlob(left, right, budget);
            if (!rightGlob) return subtreeMayOverlapGlob(right, left, budget);
            if (provenGlobDisjoint(left, right, budget)) return false;
            String leftRoot = literalRoot(left);
            String rightRoot = literalRoot(right);
            if (leftRoot.isEmpty() || rightRoot.isEmpty()) return true;
            return rootedWithin(leftRoot, rightRoot, true) || rootedWithin(rightRoot, leftRoot, true);
        }

        private static boolean subtreeMayOverlapGlob(String subtree, String glob,
                                                     SlashGlobMatcher.WorkBudget budget) {
            if (matches(subtree, glob, budget)) return true;
            String globRoot = literalRoot(glob);
            if (globRoot.isEmpty()) {
                if (glob.contains("**") || glob.indexOf('{') >= 0) return true;
                return segmentCount(glob) > segmentCount(subtree);
            }
            if (rootedWithin(globRoot, subtree, true)) return true;
            if (!rootedWithin(subtree, globRoot, true)) return false;
            if (glob.contains("**") || glob.indexOf('{') >= 0) return true;
            return segmentCount(glob) > segmentCount(subtree);
        }

        private static int segmentCount(String value) {
            if (value.isEmpty()) return 0;
            int count = 1;
            for (int index = 0; index < value.length(); index++) {
                if (value.charAt(index) == '/') count++;
            }
            return count;
        }

        private static boolean provenGlobDisjoint(String left, String right,
                                                   SlashGlobMatcher.WorkBudget budget) {
            boolean leftSimple = simpleStarGlob(left);
            boolean rightSimple = simpleStarGlob(right);
            if (leftSimple && rightSimple) {
                String[] leftSegments = left.split("/", -1);
                String[] rightSegments = right.split("/", -1);
                if (leftSegments.length != rightSegments.length) return true;
                for (int index = 0; index < leftSegments.length; index++) {
                    if (simpleSegmentsDisjoint(leftSegments[index], rightSegments[index])) return true;
                }
            }
            if (leftSimple && simpleGlobCannotReachRoot(left, right, budget)) return true;
            return rightSimple && simpleGlobCannotReachRoot(right, left, budget);
        }

        private static boolean simpleGlobCannotReachRoot(String simpleGlob, String other,
                                                          SlashGlobMatcher.WorkBudget budget) {
            String otherRoot = literalRoot(other);
            if (otherRoot.isEmpty()) return false;
            int fixedDepth = segmentCount(simpleGlob);
            int rootDepth = segmentCount(otherRoot);
            if (fixedDepth < rootDepth) return true;
            return fixedDepth == rootDepth && !matches(otherRoot, simpleGlob, budget);
        }

        private static boolean simpleStarGlob(String value) {
            return value.indexOf('*') >= 0 && !value.contains("**")
                    && value.indexOf('?') < 0 && value.indexOf('[') < 0 && value.indexOf('{') < 0;
        }

        private static boolean simpleSegmentsDisjoint(String left, String right) {
            String leftPrefix = left.substring(0, Math.max(0, left.indexOf('*')));
            String rightPrefix = right.substring(0, Math.max(0, right.indexOf('*')));
            if (!leftPrefix.startsWith(rightPrefix) && !rightPrefix.startsWith(leftPrefix)) return true;
            String leftSuffix = left.substring(left.lastIndexOf('*') + 1);
            String rightSuffix = right.substring(right.lastIndexOf('*') + 1);
            return !leftSuffix.endsWith(rightSuffix) && !rightSuffix.endsWith(leftSuffix);
        }
    }

    static final class InvalidRule extends IllegalArgumentException {
        InvalidRule(String message) { super(message); }
    }

    static final class InvalidPattern extends IllegalArgumentException {
        InvalidPattern(String message) { super(message); }
    }
}
