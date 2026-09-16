package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.template.SnapshotReview.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SnapshotReviewAuthorsTest {
    @TempDir Path directory;
    private Path source, workspace;
    private GitEvidenceProcess git;
    private SnapshotReviewAuthors authors;
    private String baseline, target, beforeBlob, afterBlob;
    private Snapshot snapshot;

    @BeforeEach void setup() throws Exception {
        source = Files.createDirectory(directory.resolve("source"));
        git = spy(new GitEvidenceProcess(new SafeProcessRunner()));
        git.read(source, "init", "-b", "main");
        git.read(source, "config", "user.name", "Alice"); git.read(source, "config", "user.email", "alice@example.test");
        Files.createDirectory(source.resolve("module"));
        Files.writeString(source.resolve("module/old name.java"), "one\ntwo\nthree\n");
        git.read(source, "add", "."); git.read(source, "commit", "-m", "initial");
        baseline = git.read(source, "rev-parse", "HEAD").strip();
        beforeBlob = git.read(source, "rev-parse", "HEAD:module/old name.java").strip();
        git.read(source, "config", "user.name", "Bob <script>"); git.read(source, "config", "user.email", "bob@example.test");
        git.read(source, "mv", "module/old name.java", "module/new name.java");
        Files.writeString(source.resolve("module/new name.java"), "one\nchanged\nthree\n");
        git.read(source, "add", "."); git.read(source, "commit", "-m", "change [link](https://invalid.test) <script>");
        target = git.read(source, "rev-parse", "HEAD").strip();
        afterBlob = git.read(source, "rev-parse", "HEAD:module/new name.java").strip();
        workspace = Files.createDirectory(directory.resolve("task"));
        git.read(directory, "clone", "--bare", source.toString(), workspace.resolve("repository.git").toString());
        Files.writeString(workspace.resolve("project-prefix.txt"), "module/");
        var repositories = mock(TemplateGitSnapshotService.class); when(repositories.workspace("task")).thenReturn(workspace);
        authors = new SnapshotReviewAuthors(repositories, git, new DocumentCodeContentCache());
        snapshot = new Snapshot(target, baseline, target, "", "", "", null, null, "", false, false,
                List.of(new File(baseline, "old name.java", beforeBlob, "100644", 14, null),
                        new File(target, "new name.java", afterBlob, "100644", 18, null)), List.of());
        clearInvocations(git);
    }
    private Reference current(int start, int end) { return new Reference(target, "new name.java", afterBlob, start, end, "quote"); }

    @Test void frozenSubdirectoryRenameShowsMultipleAuthorsAndBaselineWithoutFollowingHead() throws Exception {
        Files.writeString(source.resolve("module/new name.java"), "later\nchanged again\nother\n");
        git.read(source, "add", "."); git.read(source, "commit", "-m", "later HEAD");
        String result = authors.render("task", snapshot, List.of(current(1, 3), new Reference(baseline, "old name.java", beforeBlob, 1, 2, "one\ntwo")));
        assertThat(result).contains("Alice", "Bob script", "alice@example.test", "bob@example.test", "行 1–1", "行 2–2", "行 3–3",
                baseline, target, "作者时间", "change \\[link\\]", "&lt;script&gt;", "问题引入者：未确定").doesNotContain("later HEAD", "<script>");
    }
    @Test void repeatedRangeUsesCacheRegardlessOfQuotedTextAndDoesNotReadNoFindingCoverage() {
        var ref = current(1, 3);
        String first = authors.render("task", snapshot, List.of(ref, ref));
        verify(git, times(2)).run(any(), any(), anyList());
        clearInvocations(git);
        assertThat(authors.render("task", snapshot, List.of(new Reference(target, ref.path(), afterBlob, 1, 3, "different quote")))).isEqualTo(first);
        assertThat(authors.render("task", snapshot, List.of())).isEmpty(); verifyNoInteractions(git);
    }
    @Test void historicalTrailingEmptyItemKeepsAllRealLineAuthorsWithoutInventingAnAuthorForTheEmptyItem() {
        String result = authors.render("task", snapshot, List.of(current(1, 4)));
        assertThat(result).contains("Alice", "Bob script", "行 3–3", "文件末尾第 4 项", "以上列出全部实际代码行的作者")
                .doesNotContain("作者追溯不可用", "行 4–4");
        assertThat(authors.render("task", snapshot, List.of(current(1, 5)))).contains("作者追溯不可用");
    }
    @Test void repositoryIgnoreRevisionsConfigurationCannotChangeLastModifierAttribution() throws Exception {
        Path ignored = directory.resolve("ignored-revisions"); Files.writeString(ignored, target + "\n");
        git.read(workspace.resolve("repository.git"), "config", "blame.ignoreRevsFile", ignored.toString());
        assertThat(authors.render("task", snapshot, List.of(current(1, 3)))).contains("Alice", "Bob script").doesNotContain("作者追溯不可用");
    }
    @Test void invalidReferencesAndChangedFrozenPrefixCannotAttributeUnrelatedCode() throws Exception {
        assertThat(authors.render("task", snapshot, List.of(new Reference(target, "../outside", afterBlob, 1, 1, "x")))).contains("作者追溯不可用");
        assertThat(authors.render("task", snapshot, List.of(new Reference(target, "new name.java", beforeBlob, 1, 1, "x")))).contains("作者追溯不可用");
        verifyNoInteractions(git);
        Files.writeString(workspace.resolve("project-prefix.txt"), "../");
        assertThat(authors.render("task", snapshot, List.of(current(1, 1)))).contains("作者追溯不可用").doesNotContain("Alice");
        verifyNoInteractions(git);
    }
    @Test void missingHistoryIsExplicitAndNotCached() throws Exception {
        Files.writeString(workspace.resolve("repository.git/shallow"), baseline + "\n");
        assertThat(authors.render("task", snapshot, List.of(current(1, 1)))).contains("作者追溯不可用");
        Files.delete(workspace.resolve("repository.git/shallow"));
        assertThat(authors.render("task", snapshot, List.of(current(1, 1)))).contains("Alice").doesNotContain("作者追溯不可用");
    }
    @Test void timeoutDegradesButUnconfirmedStopPropagates() {
        doThrow(new TaskFailure("TEMPLATE_GIT_TIMEOUT", "timeout")).when(git).run(any(), any(), anyList());
        assertThat(authors.render("task", snapshot, List.of(current(1, 1)))).contains("读取失败或超时");
        doThrow(new TaskFailure("TEMPLATE_GIT_STOP_UNCONFIRMED", "stop unknown")).when(git).run(any(), any(), anyList());
        assertThatThrownBy(() -> authors.render("task", snapshot, List.of(current(1, 1)))).isInstanceOf(TaskFailure.class);
    }
}
