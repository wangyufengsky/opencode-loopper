package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.service.assist.AssistDocumentParser.Document;
import java.util.*;

/** Continuous search coordinates over immutable parser sections; original evidence stays bounded. */
final class KnowledgeDocumentText {
    private final Document document;
    private final String text;
    private final int[] starts;
    private final boolean markdown;
    KnowledgeDocumentText(Document document) {
        this.document = document; markdown = Set.of("md", "markdown").contains(document.format());
        starts = new int[document.sections().size()]; var joined = new StringBuilder();
        for (int i = 0; i < starts.length; i++) {
            // Different pages/slides/sheets are not a continuous sentence. Markdown retains exact bytes-as-text.
            if (i > 0 && !continuous(i)) joined.append('\0');
            starts[i] = joined.length(); joined.append(document.sections().get(i).markdown());
        }
        text = joined.toString();
    }
    private boolean continuous(int section) { return markdown || document.sections().get(section - 1).title().equals(document.sections().get(section).title()); }
    record Match(int section, int textOffset, String snippet, KnowledgeSearchQuery.Hit hit) { }
    List<Match> search(KnowledgeSearchQuery query, String name, int limit) {
        var hits = query.sections(text, starts); var found = new ArrayList<Match>();
        for (int i = 0; i < hits.length && found.size() < limit; i++) {
            var hit = hits[i]; if (hit == null) hit = query.locate("", name);
            if (hit == null) continue;
            int at = hits[i] == null ? starts[i] : hit.index();
            int offset = Math.max(0, at - starts[i] - 60);
            if (offset > 0 && Character.isLowSurrogate(text.charAt(starts[i] + offset))) offset--;
            int end = Math.min(text.length(), at + 240); int boundary = text.indexOf('\0', at); if (boundary >= 0) end = Math.min(end, boundary);
            found.add(new Match(i, offset, text.substring(starts[i] + offset, end), hit));
        }
        return found;
    }
    void read(Map<String,Object> body, int section, int offset) {
        var part = document.sections().get(section);
        if (offset < 0 || offset > part.markdown().length() || offset > 12000) throw KnowledgeSources.bad("文档文本位置无效，请重新检索");
        int start = starts[section] + offset;
        if (start < text.length() && Character.isLowSurrogate(text.charAt(start))) throw KnowledgeSources.bad("文档文本位置不能截断字符，请重新检索");
        int end = Math.min(text.length(), start + 12000), boundary = text.indexOf('\0', start);
        if (boundary >= 0) end = Math.min(end, boundary);
        if (end < text.length() && end > start && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        String value = text.substring(start, end);
        int first = 1 + (int)text.substring(markdown ? 0 : starts[section], start).chars().filter(c -> c == '\n').count();
        int next = section;
        while (next < starts.length && starts[next] + document.sections().get(next).markdown().length() <= end) next++;
        body.put("text", value); body.put("startLine", first); body.put("endLine", first + value.split("\n", -1).length - 1);
        body.put("textOffset", offset); body.put("nextSection", next < starts.length ? next : -1);
        body.put("nextTextOffset", next < starts.length ? Math.max(0, end - starts[next]) : 0);
    }
}
