package io.opencode.loopper.verification;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic glob matching for Git paths. Both rules and candidates are
 * normalized to '/', and matching uses dynamic programming rather than a
 * backtracking regular expression.
 */
final class SlashGlobMatcher {
    private static final int MAX_GLOB_LENGTH = 512;
    private static final int MAX_CANDIDATE_LENGTH = 32_768;
    private static final int MAX_ALTERNATIVES = 32;
    private static final int MAX_TOTAL_TOKENS = 2_048;
    private static final long DEFAULT_WORK_BUDGET = 10_000_000;

    private SlashGlobMatcher() {}

    static boolean matches(String glob, String candidate) {
        return matches(glob, candidate, new WorkBudget(DEFAULT_WORK_BUDGET));
    }

    static boolean matches(String glob, String candidate, WorkBudget budget) {
        if (glob == null || candidate == null) throw new IllegalArgumentException("Glob and candidate are required");
        if (budget == null) throw new IllegalArgumentException("Glob work budget is required");
        String normalizedGlob = glob.replace('\\', '/');
        String normalizedCandidate = candidate.replace('\\', '/');
        if (normalizedGlob.length() > MAX_GLOB_LENGTH) throw new IllegalArgumentException("Glob is too long");
        if (normalizedCandidate.length() > MAX_CANDIDATE_LENGTH) throw new IllegalArgumentException("Candidate path is too long");
        budget.consume(normalizedGlob.length() + normalizedCandidate.length() + 1L);

        List<List<Token>> alternatives = new ArrayList<>();
        int tokenCount = 0;
        long tokenWork = 0;
        for (String expanded : expandGroups(normalizedGlob)) {
            List<Token> tokens = tokenize(expanded);
            tokenCount += tokens.size();
            if (tokenCount > MAX_TOTAL_TOKENS) throw new IllegalArgumentException("Glob is too complex");
            tokenWork += tokens.stream().mapToLong(Token::workUnits).sum();
            alternatives.add(tokens);
        }
        budget.consume(tokenWork * (normalizedCandidate.length() + 1L));
        return alternatives.stream().anyMatch(tokens -> matches(tokens, normalizedCandidate));
    }

    private static List<String> expandGroups(String glob) {
        List<String> patterns = List.of(glob);
        while (true) {
            List<String> expanded = new ArrayList<>();
            boolean foundGroup = false;
            for (String pattern : patterns) {
                Group group = firstGroup(pattern);
                if (group == null) {
                    expanded.add(pattern);
                    continue;
                }
                foundGroup = true;
                String prefix = pattern.substring(0, group.start());
                String suffix = pattern.substring(group.end() + 1);
                for (String alternative : splitAlternatives(group.body())) {
                    expanded.add(prefix + alternative + suffix);
                    if (expanded.size() > MAX_ALTERNATIVES) throw new IllegalArgumentException("Glob has too many alternatives");
                }
            }
            if (!foundGroup) return patterns;
            patterns = List.copyOf(expanded);
        }
    }

    private static Group firstGroup(String pattern) {
        boolean inClass = false;
        int groupStart = -1;
        for (int index = 0; index < pattern.length(); index++) {
            char current = pattern.charAt(index);
            if (current == '[' && !inClass) {
                inClass = true;
            } else if (current == ']' && inClass) {
                inClass = false;
            } else if (!inClass && current == '{') {
                if (groupStart >= 0) throw new IllegalArgumentException("Glob groups cannot be nested");
                groupStart = index;
            } else if (!inClass && current == '}' && groupStart >= 0) {
                return new Group(groupStart, index, pattern.substring(groupStart + 1, index));
            }
        }
        if (groupStart >= 0) throw new IllegalArgumentException("Glob group is not closed");
        return null;
    }

    private static List<String> splitAlternatives(String body) {
        List<String> alternatives = new ArrayList<>();
        boolean inClass = false;
        int start = 0;
        for (int index = 0; index < body.length(); index++) {
            char current = body.charAt(index);
            if (current == '[' && !inClass) inClass = true;
            else if (current == ']' && inClass) inClass = false;
            else if (current == ',' && !inClass) {
                alternatives.add(body.substring(start, index));
                start = index + 1;
            }
        }
        alternatives.add(body.substring(start));
        return alternatives;
    }

    private static List<Token> tokenize(String glob) {
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < glob.length()) {
            char current = glob.charAt(index++);
            if (current == '*') {
                if (index < glob.length() && glob.charAt(index) == '*') {
                    index++;
                    if (index < glob.length() && glob.charAt(index) == '/') {
                        index++;
                        tokens.add(Token.of(Kind.GLOBSTAR_DIRECTORY));
                    } else {
                        tokens.add(Token.of(Kind.GLOBSTAR));
                    }
                } else {
                    tokens.add(Token.of(Kind.STAR));
                }
            } else if (current == '?') {
                tokens.add(Token.of(Kind.ANY));
            } else if (current == '[') {
                ClassResult result = characterClass(glob, index);
                tokens.add(result.token());
                index = result.nextIndex();
            } else {
                tokens.add(Token.literal(current));
            }
        }
        return List.copyOf(tokens);
    }

    private static ClassResult characterClass(String glob, int index) {
        boolean negated = index < glob.length() && glob.charAt(index) == '!';
        if (negated) index++;
        StringBuilder content = new StringBuilder();
        boolean closed = false;
        while (index < glob.length()) {
            char current = glob.charAt(index++);
            if (current == ']') {
                closed = true;
                break;
            }
            if (current == '/') throw new IllegalArgumentException("Glob character class cannot contain '/'");
            content.append(current);
        }
        if (!closed || content.isEmpty()) throw new IllegalArgumentException("Glob character class is not valid");

        List<Range> ranges = new ArrayList<>();
        for (int cursor = 0; cursor < content.length();) {
            char start = content.charAt(cursor);
            if (cursor + 2 < content.length() && content.charAt(cursor + 1) == '-') {
                char end = content.charAt(cursor + 2);
                if (end < start) throw new IllegalArgumentException("Glob character range is not valid");
                ranges.add(new Range(start, end));
                cursor += 3;
            } else {
                ranges.add(new Range(start, start));
                cursor++;
            }
        }
        return new ClassResult(Token.characterClass(List.copyOf(ranges), negated), index);
    }

    private static boolean matches(List<Token> tokens, String candidate) {
        int length = candidate.length();
        boolean[] previous = new boolean[length + 1];
        previous[0] = true;
        for (Token token : tokens) {
            boolean[] next = new boolean[length + 1];
            switch (token.kind()) {
                case LITERAL, ANY, CHARACTER_CLASS -> {
                    for (int index = 0; index < length; index++) {
                        if (previous[index] && token.accepts(candidate.charAt(index))) next[index + 1] = true;
                    }
                }
                case STAR -> {
                    for (int index = 0; index <= length; index++) {
                        if (previous[index]) next[index] = true;
                        if (index < length && next[index] && candidate.charAt(index) != '/') next[index + 1] = true;
                    }
                }
                case GLOBSTAR -> {
                    for (int index = 0; index <= length; index++) {
                        if (previous[index]) next[index] = true;
                        if (index < length && next[index]) next[index + 1] = true;
                    }
                }
                case GLOBSTAR_DIRECTORY -> {
                    boolean[] active = previous.clone();
                    System.arraycopy(previous, 0, next, 0, previous.length);
                    for (int index = 0; index < length; index++) {
                        if (!active[index]) continue;
                        active[index + 1] = true;
                        if (candidate.charAt(index) == '/') next[index + 1] = true;
                    }
                }
            }
            previous = next;
        }
        return previous[length];
    }

    private enum Kind { LITERAL, ANY, STAR, GLOBSTAR, GLOBSTAR_DIRECTORY, CHARACTER_CLASS }

    private record Token(Kind kind, char literal, List<Range> ranges, boolean negated) {
        static Token of(Kind kind) { return new Token(kind, '\0', List.of(), false); }
        static Token literal(char value) { return new Token(Kind.LITERAL, value, List.of(), false); }
        static Token characterClass(List<Range> ranges, boolean negated) {
            return new Token(Kind.CHARACTER_CLASS, '\0', ranges, negated);
        }
        long workUnits() { return kind == Kind.CHARACTER_CLASS ? Math.max(1, ranges.size()) : 1; }
        boolean accepts(char value) {
            if (kind == Kind.LITERAL) return value == literal;
            if (value == '/') return false;
            if (kind == Kind.ANY) return true;
            boolean contained = false;
            for (Range range : ranges) {
                if (range.contains(value)) {
                    contained = true;
                    break;
                }
            }
            return negated != contained;
        }
    }

    private record Range(char start, char end) {
        boolean contains(char value) { return value >= start && value <= end; }
    }
    private record ClassResult(Token token, int nextIndex) {}
    private record Group(int start, int end, String body) {}

    static final class WorkBudget {
        private long remaining;
        WorkBudget(long units) {
            if (units < 1) throw new IllegalArgumentException("Glob work budget must be positive");
            remaining = units;
        }
        void consume(long units) {
            if (units < 0 || units > remaining) throw new WorkLimitExceeded();
            remaining -= units;
        }
    }

    static final class WorkLimitExceeded extends IllegalArgumentException {
        WorkLimitExceeded() { super("Path policy matching budget exceeded"); }
    }
}
