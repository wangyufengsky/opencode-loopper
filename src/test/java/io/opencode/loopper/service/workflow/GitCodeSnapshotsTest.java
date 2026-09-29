package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.ConflictException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GitCodeSnapshotsTest {
    @TempDir Path root;
    final GitEvidenceProcess git=new GitEvidenceProcess(new SafeProcessRunner());
    final GitCodeSnapshots snapshots=new GitCodeSnapshots(git);
    @BeforeEach void init() { command("init","--quiet"); }
    @Test void exactBinaryTreePreservesAddedDeletedExecutableAndUnicodeFilesWithoutReadingCurrentContents() throws Exception {
        Files.writeString(root.resolve("old.txt"),"old"); String before=tree();
        Files.delete(root.resolve("old.txt"));
        byte[] binary={0,(byte)0xff,10,13,(byte)0xc0,42};
        Files.write(root.resolve("图像.bin"),binary);
        Files.writeString(root.resolve("run.sh"),"echo frozen\n");
        command("add","-A"); command("update-index","--chmod=+x","run.sh");
        String after=command("write-tree").strip();
        Files.writeString(root.resolve("run.sh"),"echo later\n");
        Files.writeString(root.resolve("图像.bin"),"changed");
        String index=command("write-tree").strip();
        var bytes=new HashMap<String,byte[]>();
        var captured=snapshots.capture(root,before,after,"",bytes::put);
        assertThat(captured.files()).extracting(file->file.path()).containsExactly("run.sh","图像.bin");
        assertThat(captured.files().getFirst().mode()).isEqualTo("100755");
        assertThat(bytes.get(captured.files().get(1).sha256())).containsExactly(binary);
        assertThat(captured.changes()).extracting(change->change.path()+":"+change.kind())
                .containsExactly("old.txt:DELETE","run.sh:ADD","图像.bin:ADD");
        assertThat(Files.readString(root.resolve("run.sh"))).isEqualTo("echo later\n");
        assertThat(command("write-tree").strip()).isEqualTo(index);
        assertThat(snapshots.capture(root,before,after,"",bytes::put)).isEqualTo(captured);
    }
    @Test void nestedProjectIncludesOnlyItsFrozenTreeAndRejectsAnySiblingChanges() throws Exception {
        Files.createDirectory(root.resolve("module")); Files.writeString(root.resolve("module/code.txt"),"before");
        Files.writeString(root.resolve(".env"),"test-only-placeholder");
        String before=tree();
        Files.writeString(root.resolve("module/code.txt"),"after"); String after=tree();
        var copied=new HashMap<String,byte[]>();
        assertThat(snapshots.capture(root,before,after,"module/",copied::put).files()).extracting(file->file.path()).containsExactly("code.txt");
        Files.writeString(root.resolve("sibling.txt"),"outside"); String outside=tree();
        assertThatThrownBy(()->snapshots.capture(root,before,outside,"module/",copied::put))
                .isInstanceOfSatisfying(ConflictException.class,error->assertThat(error.code()).isEqualTo("WORK_CODE_OUTSIDE_PROJECT"));
    }
    @Test void deletingAllProjectFilesProducesAnEmptyTreeAndExplicitDeletion() throws Exception {
        Files.createDirectory(root.resolve("module")); Files.writeString(root.resolve("module/code.txt"),"before");
        String before=tree(); Files.delete(root.resolve("module/code.txt")); String after=tree();
        var result=snapshots.capture(root,before,after,"module/",(hash,bytes)->fail("No file should be copied"));
        assertThat(result.files()).isEmpty(); assertThat(result.changes()).hasSize(1);
        assertThat(result.changes().getFirst().kind()).isEqualTo("DELETE");
    }
    @Test void protectedFilesAndSymbolicLinksAreRejectedBeforeAnyContentIsPublished() throws Exception {
        String empty=command("mktree").strip();
        Files.writeString(root.resolve(".env"),"fixture-only"); String protectedTree=tree();
        assertThatThrownBy(()->snapshots.capture(root,empty,protectedTree,"",(hash,bytes)->fail("Protected content copied")))
                .isInstanceOfSatisfying(ConflictException.class,error->assertThat(error.code()).isEqualTo("WORK_CODE_PROTECTED_PATH"));
        Files.delete(root.resolve(".env")); Files.writeString(root.resolve("target.txt"),"safe");
        Files.createSymbolicLink(root.resolve("link.txt"),Path.of("target.txt")); String linked=tree();
        assertThatThrownBy(()->snapshots.capture(root,empty,linked,"",(hash,bytes)->fail("Partial content copied")))
                .isInstanceOfSatisfying(ConflictException.class,error->assertThat(error.code()).isEqualTo("WORK_CODE_SPECIAL_FILE"));
    }
    @Test void sizeLimitMissingObjectAndSymbolicRefFailWithoutReturningPartialManifest() throws Exception {
        String empty=command("mktree").strip();
        Files.write(root.resolve("large.bin"),new byte[GitCodeSnapshots.MAX_FILE_BYTES+1]); String large=tree();
        assertThatThrownBy(()->snapshots.capture(root,empty,large,"",(hash,bytes)->fail("Oversized content copied")))
                .isInstanceOfSatisfying(ConflictException.class,error->assertThat(error.code()).isEqualTo("WORK_CODE_SNAPSHOT_LIMIT"));
        assertThatThrownBy(()->snapshots.capture(root,empty,"HEAD","",(hash,bytes)->{})).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->snapshots.capture(root,empty,"0".repeat(40),"",(hash,bytes)->{})).isInstanceOf(RuntimeException.class);
    }
    @Test void interruptedCopyCanRepeatTheSameObjectsAndDatabaseTransactionsCannotCapture() throws Exception {
        String empty=command("mktree").strip();
        Files.writeString(root.resolve("one.txt"),"one"); Files.writeString(root.resolve("two.txt"),"two"); String after=tree();
        var first=new HashMap<String,byte[]>();
        assertThatThrownBy(()->snapshots.capture(root,empty,after,"",(hash,bytes)->{first.put(hash,bytes);throw new IllegalStateException("disk unavailable");}))
                .hasMessage("disk unavailable");
        var all=new HashMap<String,byte[]>();
        var restored=snapshots.capture(root,empty,after,"",all::put);
        assertThat(restored.files()).hasSize(2);
        first.forEach((hash,bytes)->assertThat(all.get(hash)).containsExactly(bytes));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(()->snapshots.capture(root,empty,after,"",(hash,bytes)->{})).isInstanceOf(IllegalStateException.class); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
    String tree() { command("add","-A"); return command("write-tree").strip(); }
    String command(String... args) {
        var result=git.run(root,Duration.ofSeconds(10),List.of(args)); result.requireSuccess(List.of(args)); return result.output();
    }
}
