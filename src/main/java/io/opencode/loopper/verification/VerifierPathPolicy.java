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

    static final class InvalidRule extends IllegalArgumentException {
        InvalidRule(String message) { super(message); }
    }

    static final class InvalidPattern extends IllegalArgumentException {
        InvalidPattern(String message) { super(message); }
    }
}
