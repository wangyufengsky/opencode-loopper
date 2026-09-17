package io.opencode.loopper.service.knowledge;

import java.util.*;
import java.util.regex.Pattern;

/** Literal and identifier matching only. An expanded term is a lead, never semantic proof. */
public final class KnowledgeSearchQuery {
    private final String query;
    private final String mode;
    private final List<Term> terms;
    private record Term(String text, Pattern literal, Pattern phrase, Pattern field, boolean expanded) { }
    public record Hit(int index, int score, String matchType, String matchedTerm) { }

    public KnowledgeSearchQuery(String query, String mode, List<String> expandedTerms) {
        if (query == null || query.isBlank() || query.length() > 200) throw KnowledgeSources.bad("请输入 1–200 字符的检索词");
        this.query = query.strip(); this.mode = mode == null || mode.isBlank() ? "AUTO" : mode.toUpperCase(Locale.ROOT);
        if (!Set.of("AUTO", "EXACT", "PHRASE", "FIELD").contains(this.mode)) throw KnowledgeSources.bad("检索方式只能是 AUTO、EXACT、PHRASE 或 FIELD");
        if (expandedTerms != null && (expandedTerms.size() > 8 || expandedTerms.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 100)))
            throw KnowledgeSources.bad("扩展词最多 8 个，每个为 1–100 字符；扩展词只用于寻找相关线索");
        var all = new LinkedHashSet<String>(); all.add(this.query); if (expandedTerms != null) all.addAll(expandedTerms.stream().map(String::strip).toList());
        this.terms = all.stream().map(s -> term(s, !s.equals(this.query))).toList();
    }
    public String query() { return query; }
    public String mode() { return mode; }
    public List<String> expandedTerms() { return terms.stream().filter(Term::expanded).map(Term::text).toList(); }
    private static Term term(String text, boolean expanded) {
        int flags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        String phrase = Arrays.stream(text.split("\\s+")).map(KnowledgeSearchQuery::phraseWord).reduce((a,b) -> a + "\\s+" + b).orElse("");
        String words = text.replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2").replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        String field = Arrays.stream(words.split("[_\\-\\s]+", -1)).map(Pattern::quote).reduce((a,b) -> a + "[_\\-\\s]*" + b).orElse("");
        return new Term(text, Pattern.compile(Pattern.quote(text), flags), Pattern.compile(phrase, flags),
                Pattern.compile("(?<![\\p{L}\\p{N}_$])" + field + "(?![\\p{L}\\p{N}_$])", flags), expanded);
    }
    private static String phraseWord(String text) {
        int[] points = text.codePoints().toArray(); var pattern = new StringBuilder();
        for (int i = 0; i < points.length; i++) {
            if (i > 0 && Character.UnicodeScript.of(points[i-1]) == Character.UnicodeScript.HAN && Character.UnicodeScript.of(points[i]) == Character.UnicodeScript.HAN) pattern.append("\\s*");
            pattern.append(Pattern.quote(new String(Character.toChars(points[i]))));
        }
        return pattern.toString();
    }
    public Hit find(String text) {
        if (text == null || text.isEmpty()) return null;
        Hit best = null;
        for (var term : terms) {
            Hit hit = null;
            if (!mode.equals("FIELD")) hit = match(term.literal(), text, term, 100, "EXACT");
            if (hit == null && Set.of("AUTO", "PHRASE").contains(mode)) hit = match(term.phrase(), text, term, 95, "PHRASE");
            if (Set.of("AUTO", "FIELD").contains(mode)) {
                var field = match(term.field(), text, term, 110, "FIELD");
                if (field != null && (hit == null || field.score() > hit.score())) hit = field;
            }
            if (hit != null && (best == null || hit.score() > best.score())) best = hit;
        }
        return best;
    }
    private static Hit match(Pattern pattern, String text, Term term, int score, String type) {
        var matcher = pattern.matcher(text);
        return matcher.find() ? new Hit(matcher.start(), term.expanded() ? 50 : score, term.expanded() ? "EXPANDED" : type, term.text()) : null;
    }
    public Hit locate(String text, String name) {
        Hit content = find(text), named = find(name);
        if (content != null) return content;
        return named == null ? null : new Hit(0, named.matchType().equals("EXPANDED") ? 40 : 80, named.matchType().equals("EXPANDED") ? "EXPANDED" : "NAME", named.matchedTerm());
    }
}
