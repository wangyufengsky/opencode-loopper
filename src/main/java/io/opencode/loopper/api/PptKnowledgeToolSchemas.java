package io.opencode.loopper.api;

import java.util.*;

/** Public parameter shapes for the exact PPT-owned project read tools. */
final class PptKnowledgeToolSchemas {
    private PptKnowledgeToolSchemas() { }
    static void parameters(String tool,Map<String,Object> args,List<String> required) {
        switch(tool) {
            case "ppt_search_project_knowledge" -> {
                strings(args,"query","path","cursor");required.add("query");
                args.put("mode",Map.of("type","string","enum",List.of("AUTO","FIELD","PHRASE","EXACT")));
                for(String key:List.of("terms","sourceIds"))args.put(key,Map.of("type","array","items",Map.of("type","string"),"maxItems",key.equals("terms")?8:100));
                integer(args,"limit",1,30);
            }
            case "ppt_browse_knowledge_source" -> { strings(args,"sourceId","path","query","cursor");required.add("sourceId"); }
            case "ppt_read_knowledge_source" -> {
                strings(args,"sourceId","evidenceId","path","expectedSha");
                integer(args,"textOffset",0,12000);integer(args,"section",-1,10000);integer(args,"startLine",1,Integer.MAX_VALUE);integer(args,"endLine",0,Integer.MAX_VALUE);integer(args,"offset",0,10000);
            }
            case "ppt_query_knowledge_database" -> { strings(args,"connectionId","sql");required.addAll(List.of("connectionId","sql")); }
            case "ppt_inspect_knowledge_database" -> {
                strings(args,"connectionId","schema","table");integer(args,"offset",0,10000);required.addAll(List.of("connectionId","schema"));
                args.put("kind",Map.of("type","string","enum",List.of("tables","columns","indexes","keys")));
            }
            case "ppt_read_knowledge_git" -> {
                strings(args,"sourceId","ref","path","cursor","author","query","since","until","timeField","commit");
                args.put("operation",Map.of("type","string","enum",List.of("inspect","commits","authors","commit","file","blame")));
                integer(args,"startLine",1,Integer.MAX_VALUE);integer(args,"endLine",1,Integer.MAX_VALUE);
                required.addAll(List.of("sourceId","operation"));
            }
            default -> { }
        }
    }
    static String description(String tool) {
        return switch(tool) {
            case "ppt_list_knowledge_sources" -> "List this PPT's explicitly selected project and frozen knowledge sources. No other project is authorized.";
            case "ppt_search_project_knowledge" -> "Search this PPT's project sources with query, optional mode AUTO/FIELD/PHRASE/EXACT, terms, sourceIds, path, limit (1-30), cursor. Follow returned read instructions before citing; report incomplete coverage.";
            case "ppt_browse_knowledge_source" -> "Browse a frozen project file sourceId, optional relative path/query/cursor; directories are bounded and private paths denied.";
            case "ppt_read_knowledge_source" -> "Read a saved evidenceId from this PPT's plan sourceIds to recover its original captured text, or read current sourceId with relative path. Provide evidenceId OR sourceId. For documents omit section to list sections, then read section. Optional startLine/endLine/expectedSha/offset/textOffset; preserve the textOffset returned by search to read across parser chunks. New saved reads return evidenceId usable in PPT sourceIds.";
            case "ppt_query_knowledge_database" -> "Execute readonly SQL against an authorized project connectionId (sourceId without the database: prefix). Schema whitelist, AST, row and timeout limits apply. Returns captured evidenceId.";
            case "ppt_inspect_knowledge_database" -> "Read database tables/columns/indexes/keys for project connectionId (sourceId without the database: prefix); required schema (from source detail), optional table, kind and offset select the bounded result. Returns evidenceId.";
            case "ppt_read_knowledge_git" -> "Read local Git sourceId with operation inspect/commits/authors/commit/file/blame. Optional ref/path/author/query/since/until/timeField/cursor; commit requires full reachable SHA, file/blame use startLine/endLine. No fetch, shell or writes.";
            default -> throw new IllegalArgumentException("Unknown PPT tool");
        };
    }
    private static void strings(Map<String,Object> args,String...keys) { for(String key:keys)args.put(key,Map.of("type","string")); }
    private static void integer(Map<String,Object> args,String key,int min,int max) { args.put(key,Map.of("type","integer","minimum",min,"maximum",max)); }
}
