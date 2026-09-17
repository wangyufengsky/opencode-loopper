package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.persistence.KnowledgeV2Mapper;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.assist.AssistFailure;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeGitTest {
    @TempDir Path root;
    GitEvidenceProcess process = new GitEvidenceProcess(new SafeProcessRunner());
    KnowledgeV2Mapper mapper = mock(KnowledgeV2Mapper.class);
    KnowledgeGit git = new KnowledgeGit(process, mapper, new ObjectMapper());
    Map<String,KnowledgeV2Mapper.Snapshot> snapshots = new HashMap<>();
    @BeforeEach void setup() throws Exception {
        root = root.toRealPath(); run("init", "-b", "main"); run("config", "user.name", "张三"); run("config", "user.email", "zhang@example.test");
        doAnswer(call -> { KnowledgeV2Mapper.Snapshot snapshot = call.getArgument(0); snapshots.put(snapshot.id(), snapshot); return 1; }).when(mapper).insertSnapshot(any());
        when(mapper.snapshot(anyString(), anyString(), anyString())).thenAnswer(call -> { var s = snapshots.get(call.getArgument(0)); return s != null && s.owner().equals(call.getArgument(1)) && s.querySha().equals(call.getArgument(2)) ? s : null; });
    }
    String run(String... args) { return process.read(root, args); }
    String commit(String message) {
        run("add", "--all"); var result = process.run(root, Duration.ofSeconds(5), List.of("commit", "-m", message), Map.of("GIT_AUTHOR_DATE", "2026-09-16T10:00:00+08:00", "GIT_COMMITTER_DATE", "2026-09-17T10:00:00+08:00"));
        assertThat(result.exitCode()).isZero(); return run("rev-parse", "HEAD").strip();
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> items(Map<String,Object> result) { return (List<Map<String,Object>>) result.get("items"); }
    @Test void filtersAuthorDatesAndSubtreeAndReadsImmutableEvidence() throws Exception {
        Files.createDirectories(root.resolve("app")); Files.createDirectories(root.resolve("sibling"));
        Files.writeString(root.resolve("app/Service.java"), "first\nsecond\nthird\n"); Files.writeString(root.resolve("sibling/private.txt"), "sibling secret");
        String sha = commit("完成付款审批"); var source = git.source(root.resolve("app").toString()); assertThat(source).isNotNull();
        var result = git.call("chat", source, "search_knowledge_git_commits", Map.of("author", "张三", "since", "2026-09-16T00:00:00+08:00", "until", "2026-09-17T00:00:00+08:00"));
        assertThat(items(result)).hasSize(1); assertThat(items(result).getFirst().get("sha")).isEqualTo(sha);
        assertThat(items(git.call("chat", source, "search_knowledge_git_commits", Map.of("timeField", "committer", "since", "2026-09-16T00:00:00+08:00", "until", "2026-09-17T00:00:00+08:00")))).isEmpty();
        var file = git.call("chat", source, "read_knowledge_git_file", Map.of("commit", sha, "path", "Service.java", "startLine", 2, "endLine", 2));
        assertThat(file.get("text")).isEqualTo("second\n"); assertThat(file.get("startLine")).isEqualTo(2);
        var diff = git.call("chat", source, "read_knowledge_git_commit", Map.of("commit", sha)); assertThat(diff.toString()).contains("app/Service.java").doesNotContain("sibling/private", "sibling secret");
        Files.writeString(root.resolve("app/Service.java"), "new local text");
        assertThat(git.call("chat", source, "read_knowledge_git_file", Map.of("commit", sha, "path", "Service.java")).get("text")).asString().contains("first").doesNotContain("new local");
        assertThat(git.call("chat", source, "blame_knowledge_git_lines", Map.of("commit", sha, "path", "Service.java", "startLine", 1, "endLine", 2)).get("authors")).asList().hasSize(2);
    }
    @Test void refusesSensitiveTraversalSymlinksAndUnknownRevisions() throws Exception {
        Files.writeString(root.resolve("Visible.java"), "visible\n"); Files.writeString(root.resolve(".env"), "private-token");
        Files.createSymbolicLink(root.resolve("linked"), Path.of("Visible.java")); String sha = commit("initial"); var source = git.source(root.toString());
        for (String path : List.of("../Visible.java", ".env", "/etc/passwd", "linked", ":(glob)**", "Visible.java/../.env")) {
            assertThatThrownBy(() -> git.call("chat", source, "read_knowledge_git_file", Map.of("commit", sha, "path", path))).isInstanceOf(AssistFailure.class);
        }
        assertThatThrownBy(() -> git.call("chat", source, "read_knowledge_git_commit", Map.of("commit", "--help"))).isInstanceOf(AssistFailure.class);
        assertThat(git.call("chat", source, "read_knowledge_git_commit", Map.of("commit", sha)).toString()).doesNotContain("private-token");
    }
    @Test void cursorFreezesQueryAcrossNewCommitsAndRejectsOtherOwnersAndFilters() throws Exception {
        for (int i = 0; i < 53; i++) { Files.writeString(root.resolve("file.txt"), "version " + i); commit("工作 " + i); }
        var source = git.source(root.toString()); var page = git.call("chat", source, "search_knowledge_git_commits", Map.of());
        assertThat(items(page)).hasSize(50); String cursor = (String) page.get("nextCursor"); assertThat(cursor).isNotBlank();
        Files.writeString(root.resolve("file.txt"), "new"); commit("new after query");
        var next = git.call("chat", source, "search_knowledge_git_commits", Map.of("cursor", cursor)); assertThat(items(next)).hasSize(3);
        assertThat(items(next).stream().map(c -> c.get("sha"))).doesNotContainAnyElementsOf(items(page).stream().map(c -> c.get("sha")).toList());
        assertThatThrownBy(() -> git.call("other", source, "search_knowledge_git_commits", Map.of("cursor", cursor))).isInstanceOf(AssistFailure.class);
        assertThatThrownBy(() -> git.call("chat", source, "search_knowledge_git_commits", Map.of("cursor", cursor, "author", "other"))).isInstanceOf(AssistFailure.class);
    }
    @Test void mergeCommitExposesParentDiffsWithoutLeakingSiblingOrSensitivePaths() throws Exception {
        Files.createDirectories(root.resolve("app")); Files.createDirectories(root.resolve("sibling"));
        Files.writeString(root.resolve("app/service.txt"), "base\n"); commit("base");
        run("checkout", "-b", "feature"); Files.writeString(root.resolve("app/service.txt"), "feature implementation\n");
        Files.writeString(root.resolve("sibling/private.txt"), "sibling-content"); Files.writeString(root.resolve("app/.env"), "sensitive-value"); commit("feature");
        run("checkout", "main"); Files.writeString(root.resolve("app/other.txt"), "main work\n"); commit("main work");
        run("merge", "--no-ff", "feature", "-m", "merge feature"); String sha = run("rev-parse", "HEAD").strip();
        var source = git.source(root.resolve("app").toString());
        var evidence = git.call("chat", source, "read_knowledge_git_commit", Map.of("commit", sha));
        assertThat(evidence.get("text")).asString().contains("feature implementation", "main work").doesNotContain("sibling-content", "sensitive-value", "sibling/private.txt", "app/.env");
    }

}
