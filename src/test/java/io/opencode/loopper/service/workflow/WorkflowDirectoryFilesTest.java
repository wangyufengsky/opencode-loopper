package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.workflow.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorkflowDirectoryFilesTest {
    @TempDir Path temporary;
    final GitEvidenceProcess git = new GitEvidenceProcess(new SafeProcessRunner());
    final GitDirectoryTrees trees = new GitDirectoryTrees(git,new io.opencode.loopper.runtime.GitFixedCommits(git));
    final WorkflowDirectoryFiles files = new WorkflowDirectoryFiles(trees);
    Path project, data, repository;
    @BeforeEach void setup() throws Exception {
        Path root = temporary.toRealPath(); project = Files.createDirectory(root.resolve("project"));
        data = Files.createDirectory(root.resolve("data")); repository = data.resolve("reserved-owner"); trees.initialize(repository);
    }

    @Test void ordinaryDirectoryProducesExactReusableGitObjectsWithoutProjectGitOrFilters() throws Exception {
        Files.createDirectories(project.resolve("源代码/nested"));
        Files.writeString(project.resolve("源代码/nested/a.txt"), "line one\r\nline two\r\n");
        byte[] binary = {0, (byte) 0xff, (byte) 0xc0, 13, 10}; Files.write(project.resolve("image file.bin"), binary);
        Files.writeString(project.resolve(".gitattributes"), "* text eol=lf filter=poison\n");
        command(repository, "config", "filter.poison.clean", "false"); command(repository, "config", "filter.poison.required", "true");
        var inventory = discover(List.of()); var bodies = bodies(inventory);
        String tree = trees.store(repository, inventory.files(), file -> bodies.get(file.sha256()));
        Files.writeString(project.resolve("源代码/nested/a.txt"), "later working content");
        Files.delete(project.resolve("image file.bin"));
        trees.initialize(repository);
        assertThat(trees.store(repository, inventory.files(), file -> bodies.get(file.sha256()))).isEqualTo(tree);
        var captured = new GitCodeSnapshots(git).capture(repository, emptyTree(), tree, "", (hash, bytes) -> assertThat(bytes).containsExactly(bodies.get(hash)));
        assertThat(captured.files()).containsExactlyInAnyOrderElementsOf(inventory.files());
        assertThat(captured.changes()).allSatisfy(change -> assertThat(change.kind()).isEqualTo("ADD"));
        assertThat(Files.exists(project.resolve(".git"))).isFalse();
        assertThat(Files.exists(repository.resolve("index"))).isFalse();
        assertThat(Files.readString(project.resolve("源代码/nested/a.txt"))).isEqualTo("later working content");
        assertThat(bodies.get(inventory.files().stream().filter(file -> file.path().equals("image file.bin")).findFirst().orElseThrow().sha256())).containsExactly(binary);
    }

    @Test void ignoredAndProtectedFilesStayOutsideSnapshotButPreviouslyFrozenFilesRemainTracked() throws Exception {
        Files.writeString(project.resolve("tracked.txt"), "before"); var original = discover(List.of());
        Files.writeString(project.resolve(".gitignore"), "*.txt\noutput/\n");
        Files.createDirectory(project.resolve("output")); Files.writeString(project.resolve("output/generated.bin"), "ignored");
        Files.writeString(project.resolve("untracked.txt"), "ignored"); Files.writeString(project.resolve(".env"), "fixture-only");
        Files.createDirectory(project.resolve(".codex")); Files.writeString(project.resolve(".codex/config.toml"), "fixture-only");
        Files.writeString(project.resolve(".env.example"), "PUBLIC=value\n");
        assertThat(discover(List.of()).files()).extracting(WorkflowCodeSnapshot.File::path).containsExactly(".env.example", ".gitignore");
        assertThat(discover(original.files()).files()).extracting(WorkflowCodeSnapshot.File::path).containsExactly(".env.example", ".gitignore", "tracked.txt");
        Files.delete(project.resolve("tracked.txt"));
        assertThat(discover(original.files()).files()).extracting(WorkflowCodeSnapshot.File::path).doesNotContain("tracked.txt");
        assertThat(Files.readString(project.resolve(".env"))).isEqualTo("fixture-only");
    }

    @Test void managedDirectoryInsideProjectCannotEnterItsOwnInventoryEvenWhenNotIgnored() throws Exception {
        Path nestedData = Files.createDirectories(project.resolve("runtime data"));
        Path nestedRepository = nestedData.resolve("owner"); trees.initialize(nestedRepository);
        Files.writeString(nestedData.resolve("secret.txt"), "fixture-only"); Files.writeString(project.resolve("code.txt"), "code");
        var inventory = files.discover(project, nestedRepository, nestedData, List.of());
        assertThat(inventory.files()).extracting(WorkflowCodeSnapshot.File::path).containsExactly("code.txt");
        assertThatThrownBy(() -> files.discover(project, nestedRepository, project, List.of())).isInstanceOf(ConflictException.class);
    }

    @Test void frozenFileChangesAndMissingBytesCannotBeReplacedDuringResume() throws Exception {
        Files.writeString(project.resolve("one.txt"), "one"); Files.writeString(project.resolve("two.txt"), "two");
        var inventory = discover(List.of()); var saved = bodies(inventory); var called = new AtomicInteger();
        assertThatThrownBy(() -> trees.store(repository, inventory.files(), file -> {
            if (called.incrementAndGet() == 2) throw new IllegalStateException("simulated interruption");
            return saved.get(file.sha256());
        })).hasMessage("simulated interruption");
        Files.writeString(project.resolve("two.txt"), "changed");
        assertThatThrownBy(() -> files.read(inventory, inventory.files().get(1))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> trees.store(repository, inventory.files(), file -> "changed".getBytes())).isInstanceOf(ConflictException.class);
        String recovered = trees.store(repository, inventory.files(), file -> saved.get(file.sha256()));
        assertThat(new GitCodeSnapshots(git).capture(repository, emptyTree(), recovered, "", (hash, bytes) -> {}).files()).hasSize(2);
        Files.delete(project.resolve("one.txt"));
        assertThatThrownBy(() -> files.read(inventory, inventory.files().getFirst())).isInstanceOf(ConflictException.class);
        assertThat(Files.readString(project.resolve("two.txt"))).isEqualTo("changed");
    }

    @Test void executableModeAndDeletionArePreserved() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
        Files.writeString(project.resolve("run.sh"), "echo exact\n"); Files.writeString(project.resolve("remove.txt"), "old");
        var baseline = discover(List.of()); String before = trees.store(repository, baseline.files(), file -> files.read(baseline, file));
        var permissions = Files.getPosixFilePermissions(project.resolve("run.sh")); permissions.add(PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(project.resolve("run.sh"), permissions); Files.delete(project.resolve("remove.txt"));
        var after = discover(baseline.files()); String result = trees.store(repository, after.files(), file -> files.read(after, file));
        var captured = new GitCodeSnapshots(git).capture(repository, before, result, "", (hash, bytes) -> {});
        assertThat(captured.files()).singleElement().satisfies(file -> assertThat(file.mode()).isEqualTo("100755"));
        assertThat(captured.changes()).extracting(file -> file.path() + ":" + file.kind()).containsExactly("remove.txt:DELETE", "run.sh:MODIFY");
        assertThatThrownBy(() -> files.read(baseline, baseline.files().stream().filter(file -> file.path().equals("run.sh")).findFirst().orElseThrow()))
                .isInstanceOf(ConflictException.class);
    }

    @Test void symlinkFilesDirectoriesAndPrivateObjectsFailWithoutReadingOutside() throws Exception {
        Files.writeString(data.resolve("outside.txt"), "outside"); Files.createSymbolicLink(project.resolve("link.txt"), data.resolve("outside.txt"));
        assertThatThrownBy(() -> discover(List.of())).isInstanceOf(ConflictException.class); Files.delete(project.resolve("link.txt"));
        Files.createSymbolicLink(project.resolve("linkdir"), data);
        assertThatThrownBy(() -> discover(List.of())).isInstanceOf(ConflictException.class); Files.delete(project.resolve("linkdir"));
        Path alternate = repository.resolve("objects/info/alternates"); Files.writeString(alternate, data.toString());
        assertThatThrownBy(() -> trees.store(repository, List.of(), ignored -> new byte[0])).isInstanceOf(ConflictException.class);
        Files.delete(alternate); Files.createSymbolicLink(repository.resolve("objects/aa"),data);
        assertThatThrownBy(() -> trees.initialize(repository)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> trees.store(repository,List.of(),ignored -> new byte[0])).isInstanceOf(ConflictException.class);
        assertThat(Files.readString(data.resolve("outside.txt"))).isEqualTo("outside");
    }

    @Test void directoryReplacementOrNewGitIdentityRejectsFrozenSnapshot() throws Exception {
        Files.writeString(project.resolve("a.txt"), "a"); var inventory = discover(List.of());
        Path old = project.resolveSibling("original-preserved"); Files.move(project, old); Files.createDirectory(project);
        Files.writeString(project.resolve("a.txt"), "a");
        assertThatThrownBy(() -> files.read(inventory, inventory.files().getFirst())).isInstanceOf(ConflictException.class);
        command(project, "init", "--quiet");
        assertThatThrownBy(() -> discover(List.of())).isInstanceOf(ConflictException.class);
        assertThat(Files.readString(old.resolve("a.txt"))).isEqualTo("a");
    }
    @Test void existingButCorruptPrivateGitObjectsAreNotAcceptedAsSuccessfulRecovery() throws Exception {
        Files.writeString(project.resolve("code.txt"),"original");
        var inventory=discover(List.of());var bodies=bodies(inventory);
        String tree=trees.store(repository,inventory.files(),file->bodies.get(file.sha256()));
        String blob=inventory.files().getFirst().blob();Path object=repository.resolve("objects/"+blob.substring(0,2)+"/"+blob.substring(2));
        byte[] corrupt="blob 7\0changed".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(object.toFile().setWritable(true,true)).as("Allow deliberate corruption of this temporary Git fixture").isTrue();
        try(var zipped=new java.util.zip.DeflaterOutputStream(Files.newOutputStream(object))) { zipped.write(corrupt); }
        assertThatThrownBy(()->trees.store(repository,inventory.files(),file->bodies.get(file.sha256()))).isInstanceOf(RuntimeException.class);
        // Retain the damaged original for diagnosis instead of silently overwriting a content-addressed object.
        try(var zipped=new java.util.zip.InflaterInputStream(Files.newInputStream(object))) { assertThat(zipped.readAllBytes()).containsExactly(corrupt); }
        assertThat(tree).matches("[0-9a-f]{40}");assertThat(Files.readString(project.resolve("code.txt"))).isEqualTo("original");
    }

    @Test void malformedMetadataAndLimitsFailBeforeAnyBodyReadAndIoRejectsTransactions() throws Exception {
        byte[] bytes = "valid".getBytes(); var file = new WorkflowCodeSnapshot.File("a", "100644", GitCodeSnapshots.blobId(bytes, 40), ImmutableContentStore.hash(bytes), bytes.length);
        for (var list : List.of(List.of(file, file), List.of(file, new WorkflowCodeSnapshot.File("a/b", file.mode(), file.blob(), file.sha256(), file.sizeBytes())),
                List.of(new WorkflowCodeSnapshot.File(".env", file.mode(), file.blob(), file.sha256(), file.sizeBytes())),
                List.of(new WorkflowCodeSnapshot.File("../escape", file.mode(), file.blob(), file.sha256(), file.sizeBytes())),
                List.of(new WorkflowCodeSnapshot.File("a", "120000", file.blob(), file.sha256(), file.sizeBytes())),
                List.of(new WorkflowCodeSnapshot.File("a", file.mode(), file.blob(), file.sha256(), GitCodeSnapshots.MAX_FILE_BYTES + 1)))) {
            assertThatThrownBy(() -> trees.store(repository, list, ignored -> { fail("Invalid manifest read content"); return null; })).isInstanceOf(ConflictException.class);
        }
        Files.write(project.resolve("large.bin"), new byte[GitCodeSnapshots.MAX_FILE_BYTES + 1]);
        assertThatThrownBy(() -> discover(List.of())).isInstanceOf(ConflictException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> trees.initialize(repository)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> trees.store(repository, List.of(), ignored -> bytes)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> discover(List.of())).isInstanceOf(IllegalStateException.class);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }

    WorkflowDirectorySnapshot discover(List<WorkflowCodeSnapshot.File> tracked) { return files.discover(project, repository, data, tracked); }
    Map<String, byte[]> bodies(WorkflowDirectorySnapshot inventory) {
        var bodies = new HashMap<String, byte[]>(); inventory.files().forEach(file -> bodies.put(file.sha256(), files.read(inventory, file))); return bodies;
    }
    String emptyTree() { return trees.store(repository, List.of(), ignored -> { throw new AssertionError("Empty tree has no body"); }); }
    String command(Path directory, String... args) {
        var result = git.run(directory, Duration.ofSeconds(10), List.of(args)); result.requireSuccess(List.of(args)); return result.output();
    }
}
