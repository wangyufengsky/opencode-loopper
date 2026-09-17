package io.opencode.loopper.service.knowledge;

import java.util.*;

/** Shared search request and bounded source-page result for HTTP and MCP. */
public final class KnowledgeSearchContracts {
    private KnowledgeSearchContracts() { }
    public record Request(String query, String mode, List<String> terms, List<String> sourceIds, String path, Integer limit, String cursor) {
        public static Request from(Map<String,Object> args) {
            var limit = args.get("limit");
            if (limit != null && (!(limit instanceof Number n) || n.doubleValue() != n.intValue())) throw KnowledgeSources.bad("返回数量必须是 1–30 的整数");
            return new Request(string(args,"query"), string(args,"mode"), strings(args,"terms"), strings(args,"sourceIds"),
                    string(args,"path"), limit == null ? null : ((Number) limit).intValue(), string(args,"cursor"));
        }
        private static String string(Map<String,Object> args, String key) {
            if (args.get(key) != null && !(args.get(key) instanceof String)) throw KnowledgeSources.bad("参数 " + key + " 必须是文本");
            return (String) args.get(key);
        }
        private static List<String> strings(Map<String,Object> args, String key) {
            if (args.get(key) == null) return null;
            if (!(args.get(key) instanceof List<?> items) || items.size() > 100 || items.stream().anyMatch(s -> !(s instanceof String)))
                throw KnowledgeSources.bad("参数 " + key + " 必须是有界文本列表");
            return items.stream().map(String.class::cast).toList();
        }
    }
    public record Chunk(List<Map<String,Object>> matches, String nextCursor, boolean incomplete, List<String> limitations, int examined) { }
}
