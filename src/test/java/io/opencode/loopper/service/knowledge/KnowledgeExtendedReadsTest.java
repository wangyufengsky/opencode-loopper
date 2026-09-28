package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.service.assist.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeExtendedReadsTest {
    @TempDir Path root;
    KnowledgeSources sources = mock(KnowledgeSources.class);
    KnowledgeDocumentCache documents = new KnowledgeDocumentCache(new AssistDocumentParser());
    KnowledgeReader reader = new KnowledgeReader(sources, documents);
    KnowledgeSources.Bound source;
    @BeforeEach void setup() throws Exception {
        root = root.toRealPath(); source = new KnowledgeSources.Bound("code", "CODE", "代码", root.toString(), null, "READY", "", 0);
        when(sources.path(eq(source), anyString())).thenAnswer(c -> KnowledgeFiles.resolve(root.toString(), c.getArgument(1)));
        when(sources.read(eq(source), anyString(), anyInt())).thenAnswer(c -> KnowledgeFiles.read(root, KnowledgeFiles.relative(c.getArgument(1)), c.getArgument(2)));
    }
    @AfterEach void close() { documents.close(); }
    @SuppressWarnings("unchecked") List<Map<String,Object>> matches(Map<String,Object> result) { return (List<Map<String,Object>>)result.get("matches"); }
    @Test void projectRelativeDotPathsWorkWithoutWeakeningTraversalOrSourceBoundaries() throws Exception {
        Files.createDirectories(root.resolve("src")); Files.writeString(root.resolve("src/A.java"), "class A {}");
        for (String path : List.of("src/A.java", "./src/A.java", "src/./A.java"))
            assertThat(reader.read(source, path, -1, 1, null).get("text")).asString().contains("class A");
        assertThat(reader.browse(source, Map.of("path", ".")).items()).extracting(KnowledgeFiles.Entry::path).containsExactly("src");
        assertThat(reader.browse(source, Map.of("path", "./src")).items()).extracting(KnowledgeFiles.Entry::path).containsExactly("src/A.java");
        for (String path : List.of("src/../src/A.java", "../outside", root.resolve("src/A.java").toString(), "./.env"))
            assertThatThrownBy(() -> reader.read(source, path, -1, 1, null)).isInstanceOf(AssistFailure.class);
        assertThatThrownBy(() -> reader.read(source, "project/src/A.java", -1, 1, null)).hasMessageContaining("sourceId");
        assertThatThrownBy(() -> reader.read(source, ".", -1, 1, null)).hasMessageContaining("目录请用 browse");
    }
    @Test void occurrencesContinueWithinTheSameLineAndFileAndBindOwnerAndVersion() throws Exception {
        Files.writeString(root.resolve("A.java"), "customerId ".repeat(65));
        var query = new KnowledgeSearchQuery("customerId", "FIELD", List.of());
        var first = reader.occurrences("turn", source, "", query, null); assertThat(matches(first)).hasSize(30);
        String cursor = (String)first.get("nextCursor");
        var second = reader.occurrences("turn", source, "", query, cursor); assertThat(matches(second)).hasSize(30);
        assertThat(matches(second).getFirst().get("matchOffset")).isEqualTo(330);
        var third = reader.occurrences("turn", source, "", query, (String)second.get("nextCursor")); assertThat(matches(third)).hasSize(5);
        assertThat(third.get("nextCursor")).isNull(); assertThat(third.get("incomplete")).isEqualTo(false);
        assertThatThrownBy(() -> reader.occurrences("other", source, "", query, cursor)).hasMessageContaining("游标");
        Files.writeString(root.resolve("A.java"), "customerId changed");
        assertThatThrownBy(() -> reader.occurrences("turn", source, "", query, cursor)).hasMessageContaining("变化");
    }
    @Test void documentOccurrencesCrossChunksAndReadInstructionsReturnTheirActualContext() throws Exception {
        Files.writeString(root.resolve("rules.md"), "x".repeat(11996) + "customerId\n" + "customerId\n".repeat(33));
        var result = reader.occurrences("turn", source, "", new KnowledgeSearchQuery("customerId", "EXACT", List.of()), null);
        assertThat(matches(result)).hasSize(30); var hit = matches(result).getFirst();
        var body = reader.readRange(source, "rules.md", (int)hit.get("section"), 1, 0, (String)hit.get("sha256"), 0, (int)hit.get("textOffset"));
        assertThat(body.get("text")).asString().contains("customerId");
        var next = reader.occurrences("turn", source, "", new KnowledgeSearchQuery("customerId", "EXACT", List.of()), (String)result.get("nextCursor"));
        assertThat(matches(next)).hasSize(4);
    }
    @Test void explicitDirectoryFiltersRespectDepthProtectedPathsAndCursorIdentity() throws Exception {
        Files.createDirectories(root.resolve("src/deep")); Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("src/deep/A.java"), "class A {}"); Files.writeString(root.resolve("src/B.md"), "docs");
        Files.writeString(root.resolve("target/Hidden.java"), "private"); Files.writeString(root.resolve(".env"), "private");
        Files.createSymbolicLink(root.resolve("linked.java"), root.resolve("src/deep/A.java"));
        var args = Map.<String,Object>of("depth", 3, "entryType", "files", "extensions", List.of("java"), "pathPattern", "**/*.java");
        assertThat(reader.browse(source, args).items()).extracting(KnowledgeFiles.Entry::path).containsExactly("src/deep/A.java");
        assertThat(reader.browse(source, Map.of("depth", 2, "entryType", "directories")).items()).extracting(KnowledgeFiles.Entry::path).containsExactly("src", "src/deep");
        for (int i = 0; i < 60; i++) Files.writeString(root.resolve("file" + i + ".java"), "");
        var first = reader.browse(source, args); assertThat(first.nextCursor()).isNotNull();
        var changed = new HashMap<>(args); changed.put("cursor", first.nextCursor()); changed.put("depth", 2);
        assertThatThrownBy(() -> reader.browse(source, changed)).hasMessageContaining("范围");
        assertThatThrownBy(() -> reader.browse(source, Map.of("depth", 13))).isInstanceOf(AssistFailure.class);
        assertThatThrownBy(() -> reader.browse(source, Map.of("depth", 2, "recursive", false))).isInstanceOf(AssistFailure.class);
    }
    @Test void filenameLookupFindsCodeBeyondUnrelatedFilesAndReturnsReadableArguments() throws Exception {
        Files.createDirectories(root.resolve("z/src")); Files.writeString(root.resolve("z/src/PaymentService.java"), "class PaymentService {}\n");
        for (int i = 0; i < 70; i++) Files.writeString(root.resolve("A" + i + ".java"), "unrelated");
        var reads = new KnowledgeReadOperations(reader, mock(KnowledgeSearchService.class), mock(KnowledgeGit.class), mock(DatabaseQueryService.class), new ObjectMapper());
        var selection = new KnowledgeSources.Selection(List.of(source), List.of());
        var located = reads.read("designer", selection, "browse_knowledge_source", Map.of("sourceId", "code", "query", "PaymentService.java", "recursive", true, "entryType", "files"));
        @SuppressWarnings("unchecked") var entries = (List<Map<String,Object>>)located.get("items"); assertThat(entries).hasSize(1);
        @SuppressWarnings("unchecked") var instruction = (Map<String,Object>)entries.getFirst().get("read");
        @SuppressWarnings("unchecked") var arguments = (Map<String,Object>)instruction.get("arguments");
        assertThat(reads.read("designer", selection, instruction.get("tool").toString(), arguments).get("text")).asString().contains("class PaymentService");
        assertThat(located).containsEntry("pathBase", "PROJECT_ROOT");
        Files.createDirectories(root.resolve("other")); Files.writeString(root.resolve("other/PaymentService.java"), "class PaymentService { int other; }\n");
        var found = reader.search(source, "", "PaymentService.java", null);
        assertThat(matches(found)).hasSize(2); assertThat(found).containsEntry("examinedFiles", 2).containsEntry("searchIntent", "FILE_NAME");
        assertThat(matches(found)).extracting(m -> m.get("path")).containsExactly("other/PaymentService.java", "z/src/PaymentService.java");
        assertThat(matches(found)).allSatisfy(m -> assertThat(m.get("snippet")).asString().contains("class PaymentService"));
    }
    @Test void batchReadsKeepSuccessfulItemsBesideDeniedAndChangedFiles() throws Exception {
        Files.writeString(root.resolve("A.java"), "class A {}"); Files.writeString(root.resolve(".env"), "secret");
        var reads = new KnowledgeReadOperations(reader, mock(KnowledgeSearchService.class), mock(KnowledgeGit.class), mock(DatabaseQueryService.class), new ObjectMapper());
        var selection = new KnowledgeSources.Selection(List.of(source), List.of());
        var result = reads.read("turn", selection, "read_knowledge_sources", Map.of("items", List.of(
                Map.of("sourceId", "code", "path", "A.java"), Map.of("sourceId", "code", "path", ".env"),
                Map.of("sourceId", "other", "path", "A.java"), Map.of("sourceId", "code", "path", "A.java", "expectedSha", "wrong"))));
        assertThat(result.get("partial")).isEqualTo(true); assertThat(result.toString()).contains("class A", "SUCCEEDED", "FAILED").doesNotContain("secret");
        assertThatThrownBy(() -> reads.read("turn", selection, "read_knowledge_sources", Map.of("items", Collections.nCopies(6, Map.of("sourceId", "code"))))).hasMessageContaining("1–5");
        var overview = reads.read("turn", selection, "inspect_knowledge_project", Map.of());
        assertThat(overview.toString()).contains("A.java", "sha256").doesNotContain(root.toString(), ".env");
    }
    @Test void unifiedOccurrenceSearchDoesNotDeduplicateMultiplePositionsOnOneLine() throws Exception {
        Files.writeString(root.resolve("A.java"), "customerId customerId");
        var scanner = new KnowledgeSearchScanner(reader, mock(DatabaseQueryService.class), new ObjectMapper());
        var search = new KnowledgeSearchService(scanner, new ObjectMapper());
        try {
            var selection = new KnowledgeSources.Selection(List.of(source), List.of());
            var request = new KnowledgeSearchContracts.Request("customerId", "FIELD", null, null, null, 1, null, "occurrences");
            var first = search.search("turn", selection, request); assertThat(matches(first)).hasSize(1);
            var next = new KnowledgeSearchContracts.Request("customerId", "FIELD", null, null, null, 1, (String)first.get("nextCursor"), "occurrences");
            assertThat(matches(search.search("turn", selection, next))).hasSize(1);
            var changed = new KnowledgeSearchContracts.Request("customerId", "FIELD", null, null, null, 1, next.cursor(), "files");
            assertThatThrownBy(() -> search.search("turn", selection, changed)).hasMessageContaining("变化");
        } finally { search.close(); }
    }
}
