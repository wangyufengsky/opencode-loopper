package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.service.assist.AssistDocumentParser;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeDocumentSearchTest {
    @TempDir Path root;
    KnowledgeDocumentCache cache = new KnowledgeDocumentCache(new AssistDocumentParser());
    @AfterEach void close() { cache.close(); }
    @Test void findsLiteralAndPhraseAcrossParserBoundariesWithoutChangingOriginalSections() throws Exception {
        root = root.toRealPath();
        var sources = mock(KnowledgeSources.class);
        var source = new KnowledgeSources.Bound("docs", "DIRECTORY", "资料", root.toString(), null, "READY", "", 0);
        when(sources.path(eq(source), anyString())).thenAnswer(c -> root.resolve((String)c.getArgument(1)));
        when(sources.read(eq(source), anyString(), anyInt())).thenAnswer(c -> Files.readAllBytes(root.resolve((String)c.getArgument(1))));
        var reader = new KnowledgeReader(sources, cache);
        for (String term : List.of("customerId", "付款必须审批")) {
            Files.writeString(root.resolve("boundary.md"), "x".repeat(11996) + term + "\n结束\n");
            var result = reader.search(source, "", new KnowledgeSearchQuery(term, "EXACT", List.of()), null);
            assertThat(result.get("matches")).asList().hasSize(1);
            @SuppressWarnings("unchecked") var hit = ((List<Map<String,Object>>)result.get("matches")).getFirst();
            assertThat(hit.get("snippet")).asString().contains(term);
            var read = reader.readRange(source, "boundary.md", ((Number)hit.get("section")).intValue(), 1, 0,
                    (String)hit.get("sha256"), 0, ((Number)hit.get("textOffset")).intValue());
            assertThat(read.get("text")).asString().contains(term).hasSizeLessThanOrEqualTo(12000);
            assertThat(read.get("startLine")).isEqualTo(1);
            Files.writeString(root.resolve("boundary.md"), "已变化");
            assertThatThrownBy(() -> reader.readRange(source, "boundary.md", 0, 1, 0, (String)hit.get("sha256"), 0, 0)).hasMessageContaining("变化");
        }
    }
    @Test void officePagesDoNotCreateFalseCrossPagePhrasesAndMarkdownLineCoordinatesSurviveWindows() {
        var page = new AssistDocumentParser.Document("pdf", List.of(new AssistDocumentParser.Section("0", "第 1 页", "customer"),
                new AssistDocumentParser.Section("1", "第 2 页", "Id")), List.of());
        assertThat(new KnowledgeDocumentText(page).search(new KnowledgeSearchQuery("customerId", "EXACT", List.of()), "x.pdf", 30)).isEmpty();
        var markdown = new AssistDocumentParser().parse("x.md", ("line\r\n".repeat(1999) + "customerId\r\n尾部").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var text = new KnowledgeDocumentText(markdown); var hit = text.search(new KnowledgeSearchQuery("customerId", "EXACT", List.of()), "x.md", 30).getFirst();
        var body = new HashMap<String,Object>(); text.read(body, hit.section(), hit.textOffset());
        int before = ((String)body.get("text")).indexOf("customerId");
        assertThat((int)body.get("startLine") + ((String)body.get("text")).substring(0, before).chars().filter(c -> c == '\n').count()).isEqualTo(2000);
    }
}
