package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.service.assist.*;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import static io.opencode.loopper.service.knowledge.KnowledgeSearchContracts.*;

/** Shared HTTP/MCP search. Ephemeral cursors bind the exact owner, query and frozen source configuration. */
@Service
public class KnowledgeSearchService {
    private static final int MAX_PAGES = 64, CACHE_BYTES = 750_000;
    private final KnowledgeSearchScanner scanner;
    private final ObjectMapper json;
    private final Map<String,Search> cache = new LinkedHashMap<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(3, 3, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(12),
            Thread.ofPlatform().daemon().name("knowledge-search-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    private static final class Progress {
        final String id, name, kind;
        final KnowledgeSources.Bound file;
        final DatabaseConnectionService.Bound database;
        String cursor, state = "NOT_SEARCHED"; int examined, matched; boolean limited;
        final Set<String> limitations = new LinkedHashSet<>();
        Progress(KnowledgeSources.Bound file) { this.file = file; database = null; id = file.id(); name = file.name(); kind = file.kind(); }
        Progress(DatabaseConnectionService.Bound database) { this.database = database; file = null; id = "database:" + database.id(); name = database.name(); kind = "DATABASE"; }
    }
    private static final class Search {
        final String id = UUID.randomUUID().toString(), fingerprint;
        final long created = System.nanoTime();
        final ReentrantLock lock = new ReentrantLock();
        final List<Progress> sources = new ArrayList<>();
        final Deque<Progress> pending = new ArrayDeque<>();
        final List<Map<String,Object>> buffer = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        final List<Map<String,Object>> pages = new ArrayList<>();
        int bytes;
        Search(String fingerprint) { this.fingerprint = fingerprint; }
    }
    public KnowledgeSearchService(KnowledgeSearchScanner scanner, ObjectMapper json) { this.scanner = scanner; this.json = json; }
    @PreDestroy public void close() { workers.shutdownNow(); synchronized (cache) { cache.clear(); } }

    public Map<String,Object> search(String owner, KnowledgeSources.Selection selection, Request request) {
        var query = new KnowledgeSearchQuery(request.query(), request.mode(), request.terms());
        int limit = request.limit() == null ? 20 : request.limit(); if (limit < 1 || limit > 30) throw KnowledgeSources.bad("返回数量必须是 1–30 的整数");
        String path = Objects.toString(request.path(), ""); if (path.length() > 1000) throw KnowledgeSources.bad("检索目录过长");
        var chosen = subset(selection, request.sourceIds());
        String fingerprint = AssistFiles.sha(json.writeValueAsBytes(List.of(owner, chosen, query.query(), query.mode(), query.expandedTerms(), path, limit)));
        Search search; int page = 0;
        synchronized (cache) {
            cache.values().removeIf(s -> System.nanoTime() - s.created > TimeUnit.MINUTES.toNanos(5));
            if (request.cursor() == null || request.cursor().isBlank()) {
                if (cache.size() >= 16) {
                    var oldest = cache.values().stream().filter(s -> !s.lock.isLocked()).findFirst().orElseThrow(() -> KnowledgeSources.bad("并行检索已达到上限，请稍后重试"));
                    cache.remove(oldest.id);
                }
                search = new Search(fingerprint);
                chosen.sources().forEach(s -> search.sources.add(new Progress(s))); chosen.connections().forEach(s -> search.sources.add(new Progress(s)));
                for (var source : search.sources) {
                    if (source.kind.equals("GIT")) { source.state = "SKIPPED"; source.limitations.add("统一检索未查询 Git 历史，请在 Git 来源中查询作者、日期或提交"); }
                    else if (source.database != null && !path.isBlank()) { source.state = "SKIPPED"; source.limitations.add("本次限定了文件目录，未查询数据库结构"); }
                    else search.pending.add(source);
                }
                cache.put(search.id, search);
            } else {
                String[] parts = request.cursor().split(":", -1);
                try { if (parts.length != 2) throw new IllegalArgumentException(); page = Integer.parseInt(parts[1]); search = cache.get(parts[0]); }
                catch (IllegalArgumentException invalid) { throw KnowledgeSources.bad("检索游标无效，请重新检索"); }
                if (search == null || !search.fingerprint.equals(fingerprint)) throw KnowledgeSources.bad("检索已过期或查询、来源发生变化，请重新检索");
            }
        }
        if (!search.lock.tryLock()) throw KnowledgeSources.bad("同一检索仍在处理中，请等待当前页返回");
        try {
            if (page < 0 || page > search.pages.size()) throw KnowledgeSources.bad("检索页不存在，请使用返回的下一页游标");
            if (page < search.pages.size()) return search.pages.get(page);
            if (page >= MAX_PAGES) throw KnowledgeSources.bad("检索达到分页上限，请缩小范围重新检索");
            if (search.buffer.isEmpty()) scan(search, query, path);
            search.buffer.sort(Comparator.<Map<String,Object>>comparingInt(m -> -((Number)m.getOrDefault("score", 0)).intValue())
                    .thenComparing(m -> Objects.toString(m.get("path"), "")).thenComparing(m -> Objects.toString(m.get("sourceId"), "")));
            var matches = List.copyOf(search.buffer.subList(0, Math.min(limit, search.buffer.size()))); search.buffer.subList(0, matches.size()).clear();
            boolean more = !search.pending.isEmpty() || !search.buffer.isEmpty();
            if (more && (page + 1 >= MAX_PAGES || search.bytes > CACHE_BYTES - 80_000)) {
                for (var source : search.sources) if (source.state.equals("PARTIAL") || source.state.equals("NOT_SEARCHED")) {
                    source.state = "LIMITED"; source.limitations.add("检索达到缓存或页数边界，请缩小目录或来源后重新检索");
                }
                search.pending.clear(); search.buffer.clear(); more = false;
                // Even complete source scans can have buffered matches omitted at the response bound.
                for (var source : search.sources) { source.limited = true; source.limitations.add("本次结果达到总量边界，部分匹配未展示"); }
            }
            var result = new LinkedHashMap<String,Object>(); result.put("matches", matches); result.put("nextCursor", more ? search.id + ":" + (page + 1) : null);
            result.put("coverage", search.sources.stream().map(KnowledgeSearchService::coverage).toList());
            result.put("incomplete", more || search.sources.stream().anyMatch(s -> !s.state.equals("COMPLETE") || s.limited));
            result.put("limitations", search.sources.stream().flatMap(s -> s.limitations.stream().map(l -> s.name + "：" + l)).distinct().limit(20).toList());
            result.put("collectedAt", Instant.now().toString());
            result.put("detail", "按已扫描批次的匹配度排序；代码每文件返回一个定位片段，文档按分段匹配。扩展词只提供线索，不证明概念等价。引用前调用 read 中的工具读取原文。数据库仅查结构；无命中不能证明不存在。继续分页须保留原查询与来源，游标 5 分钟有效。");
            search.bytes += json.writeValueAsBytes(result).length; search.pages.add(Collections.unmodifiableMap(result)); return search.pages.getLast();
        } finally { search.lock.unlock(); }
    }
    public static KnowledgeSources.Selection subset(KnowledgeSources.Selection selection, List<String> ids) {
        if (ids == null) return selection;
        var available = new HashSet<String>(); selection.sources().forEach(s -> available.add(s.id())); selection.connections().forEach(s -> available.add("database:" + s.id()));
        if (ids.isEmpty() || ids.size() > 100 || new HashSet<>(ids).size() != ids.size() || !available.containsAll(ids)) throw KnowledgeSources.bad("检索来源必须属于当前授权，且为 1–100 项不重复来源");
        return new KnowledgeSources.Selection(selection.sources().stream().filter(s -> ids.contains(s.id())).toList(),
                selection.connections().stream().filter(s -> ids.contains("database:" + s.id())).toList());
    }
    private void scan(Search search, KnowledgeSearchQuery query, String path) {
        var running = new LinkedHashMap<Progress,Future<Chunk>>(); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        for (int i = 0; i < 3 && !search.pending.isEmpty(); i++) {
            var source = search.pending.removeFirst();
            try { running.put(source, workers.submit(() -> source.file != null ? scanner.files(source.file, query, path, source.cursor) : scanner.database(source.database, query, source.cursor))); }
            catch (RejectedExecutionException busy) { source.state = "FAILED"; source.limitations.add("检索工作线程繁忙，请稍后重新检索此来源"); }
        }
        for (var entry : running.entrySet()) {
            var source = entry.getKey(); var future = entry.getValue();
            try {
                var chunk = future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                source.examined += chunk.examined(); source.matched += chunk.matches().size(); source.limitations.addAll(chunk.limitations());
                source.limited |= chunk.incomplete() && chunk.nextCursor() == null || chunk.limitations().stream().anyMatch(l -> !l.equals("检索达到单次边界，请继续下一页或缩小目录"));
                if (chunk.nextCursor() != null && Objects.equals(chunk.nextCursor(), source.cursor)) { source.limited = true; source.limitations.add("来源分页未推进，请缩小范围"); source.state = "LIMITED"; }
                else { source.cursor = chunk.nextCursor(); source.state = source.cursor != null ? "PARTIAL" : source.limited ? "LIMITED" : "COMPLETE"; if (source.cursor != null) search.pending.addLast(source); }
                for (var match : chunk.matches()) {
                    String key = List.of("resourceKey", "sha256", "section", "startLine").stream().map(k -> Objects.toString(match.get(k), "")).reduce((a,b) -> a + ":" + b).orElse("");
                    if (search.seen.add(key)) search.buffer.add(match);
                }
            } catch (TimeoutException timedOut) { future.cancel(true); source.state = "TIMED_OUT"; source.limitations.add("来源检索超时，尚未获得完整结果；请缩小范围后重试"); }
            catch (InterruptedException interrupted) { future.cancel(true); Thread.currentThread().interrupt(); source.state = "TIMED_OUT"; source.limitations.add("检索已中断，未完成此来源"); }
            catch (ExecutionException failure) {
                source.state = failure.getCause() instanceof AssistFailure f && f.code().contains("TIMEOUT") ? "TIMED_OUT" : "FAILED";
                source.limitations.add(failure.getCause() instanceof AssistFailure ? AssistRedaction.text(failure.getCause().getMessage()) : "来源暂时无法检索，请检查配置后重试");
            }
        }
        workers.purge();
    }
    private static Map<String,Object> coverage(Progress s) {
        return Map.of("sourceId", s.id, "name", s.name, "kind", s.kind, "state", s.state, "examined", s.examined,
                "matched", s.matched, "limited", s.limited, "limitations", List.copyOf(s.limitations));
    }
}
