package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.persistence.KnowledgeMapper;
import io.opencode.loopper.service.PageCursor;
import java.util.*;

/** Historical evidence stays in its conversation. Reading never replaces it with a current file. */
final class KnowledgeEvidence {
    private KnowledgeEvidence() { }
    static Map<String,Object> list(KnowledgeMapper mapper, String conversation, Map<String,Object> args) {
        String cursor = Objects.toString(args.get("cursor"), "");
        var page = cursor.isBlank() ? null : PageCursor.decode(cursor);
        if (page != null && mapper.citation(conversation, page.id()).filter(c -> c.createdAt().equals(page.value())).isEmpty()) throw KnowledgeSources.bad("证据游标不属于当前会话");
        var rows = mapper.evidencePage(conversation, page == null ? "" : page.value(), page == null ? "" : page.id());
        var result = new LinkedHashMap<String,Object>();
        result.put("items", rows.stream().limit(50).map(c -> Map.of("reference", "knowledge:" + c.id(), "turnId", c.turnId(), "kind", c.kind(),
                "sourceId", c.sourceId(), "name", c.name(), "location", c.location(), "sha256", c.sha256(), "collectedAt", c.createdAt())).toList());
        result.put("nextCursor", rows.size() > 50 ? new PageCursor(rows.get(49).createdAt(), rows.get(49).id()).encode() : null); return result;
    }
    static Map<String,Object> read(KnowledgeMapper mapper, String conversation, Map<String,Object> args) {
        String reference = Objects.toString(args.get("reference"), "");
        if (!reference.matches("knowledge:[a-f0-9-]{36}")) throw KnowledgeSources.bad("请使用本会话实际保存的 knowledge: 引用");
        var saved = mapper.citation(conversation, reference.substring(10)).orElseThrow(() -> KnowledgeSources.bad("引用不存在或属于其他会话"));
        Object value = args.getOrDefault("offset", 0);
        if (!(value instanceof Number n) || n.doubleValue() != n.intValue()) throw KnowledgeSources.bad("offset 必须是整数");
        int offset = n.intValue(); String body = saved.bodyJson();
        if (offset < 0 || offset > body.length() || offset < body.length() && Character.isLowSurrogate(body.charAt(offset))) throw KnowledgeSources.bad("引用读取位置无效");
        int end = Math.min(body.length(), offset + 12000);
        if (end < body.length() && Character.isHighSurrogate(body.charAt(end - 1))) end--;
        return Map.of("reference", reference, "content", body.substring(offset, end), "nextOffset", end < body.length() ? end : -1,
                "sha256", saved.sha256(), "collectedAt", saved.createdAt(), "historical", true);
    }
}
