package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.service.assist.*;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

/** Content-addressed representations only; each caller must independently authorize its source. */
@Service
public class KnowledgeDocumentCache {
    private final AssistDocumentParser parser;
    private final Map<String, AssistDocumentParser.Document> cache = new LinkedHashMap<>(16, .75f, true);
    private final ExecutorService workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4),
            Thread.ofPlatform().daemon().name("knowledge-document-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    public KnowledgeDocumentCache(AssistDocumentParser parser) { this.parser = parser; }
    public record Parsed(String sha256, AssistDocumentParser.Document document) { }
    public Parsed load(Path path, String name) { return parse(name, AssistFiles.read(path, 20 * 1024 * 1024)); }
    public Parsed parse(String name, byte[] bytes) {
        String sha = AssistFiles.sha(bytes), key = AssistDocumentParser.VERSION + ":" + KnowledgeFiles.extension(name) + ":" + sha;
        synchronized (cache) { if (cache.containsKey(key)) return new Parsed(sha, cache.get(key)); }
        Future<AssistDocumentParser.Document> pending;
        try { pending = workers.submit(() -> parser.parse(name, bytes)); }
        catch (RejectedExecutionException e) { throw new AssistFailure("KNOWLEDGE_PARSER_BUSY", "文档解析繁忙，请稍后重试"); }
        try {
            var document = pending.get(15, TimeUnit.SECONDS);
            synchronized (cache) {
                cache.put(key, document);
                while (cache.size() > 64 || cache.values().stream().flatMap(d -> d.sections().stream()).mapToLong(s -> s.markdown().length()).sum() > 4_000_000)
                    cache.remove(cache.keySet().iterator().next());
            }
            return new Parsed(sha, document);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); pending.cancel(true); throw new AssistFailure("KNOWLEDGE_PARSE_INTERRUPTED", "文档解析已中断，请重试");
        } catch (TimeoutException e) { pending.cancel(true); throw new AssistFailure("KNOWLEDGE_PARSE_TIMEOUT", "解析超过 15 秒，请拆分文件"); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof AssistFailure safe) throw safe;
            throw new AssistFailure("KNOWLEDGE_PARSE_FAILED", "文档无法解析，请检查文件格式");
        }
    }
    @PreDestroy public void close() { workers.shutdownNow(); }
}
