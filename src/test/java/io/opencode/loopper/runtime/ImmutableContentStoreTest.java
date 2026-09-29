package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ImmutableContentStoreTest {
    @TempDir Path temporary;
    private final byte[] bytes = "版本一\n".getBytes(StandardCharsets.UTF_8);
    private final String sha = ImmutableContentStore.hash(bytes);

    @Test void preservesExistingSourceObjectLayoutAndExactBytesAcrossRestarts() throws Exception {
        Path root = temporary.resolve("source-templates");
        Path object = root.resolve("run-1/objects/" + sha);
        Files.createDirectories(object.getParent()); Files.write(object, bytes);
        var store = new ImmutableContentStore(root);
        assertThat(store.read("run-1", sha, -1, 100)).isEqualTo(bytes);
        var modified = Files.getLastModifiedTime(object);
        store.write("run-1", sha, bytes, 100);
        assertThat(Files.getLastModifiedTime(object)).isEqualTo(modified);
        assertThat(new ImmutableContentStore(root).read("run-1", sha, bytes.length, 100)).isEqualTo(bytes);
    }
    @Test void concurrentWritersPublishOneCompleteObjectWithoutReplacingIt() throws Exception {
        var store = new ImmutableContentStore(temporary);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); store.write("run", sha, bytes, 100); return true; });
            var second = pool.submit(() -> { start.await(); store.write("run", sha, bytes, 100); return true; });
            start.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
        }
        assertThat(store.read("run", sha, bytes.length, 100)).isEqualTo(bytes);
        try (var files = Files.list(temporary.resolve("run/objects"))) {
            assertThat(files.map(path -> path.getFileName().toString()).toList()).containsExactly(sha);
        }
    }
    @Test void modifiedObjectsAreNeverOverwrittenByRetry() throws Exception {
        var store = new ImmutableContentStore(temporary);
        store.write("run", sha, bytes, 100);
        Path object = temporary.resolve("run/objects/" + sha);
        Files.writeString(object, "tampered");
        assertThatThrownBy(() -> store.write("run", sha, bytes, 100)).isInstanceOf(ImmutableContentStore.StorageFailure.class);
        assertThat(Files.readString(object)).isEqualTo("tampered");
    }
    @Test void hashSizeAndOwnerAreAllCheckedAndMissingReadsDoNotCreateDirectories() {
        var store = new ImmutableContentStore(temporary);
        store.write("one", sha, bytes, 100);
        assertThatThrownBy(() -> store.read("two", sha, bytes.length, 100)).isInstanceOf(ImmutableContentStore.StorageFailure.class);
        assertThat(temporary.resolve("two")).doesNotExist();
        assertThatThrownBy(() -> store.read("one", sha, bytes.length - 1, 100)).isInstanceOf(ImmutableContentStore.StorageFailure.class);
        assertThatThrownBy(() -> store.read("one", sha, -1, bytes.length - 1)).isInstanceOf(ImmutableContentStore.StorageFailure.class);
        assertThatThrownBy(() -> store.write("one", "0".repeat(64), bytes, 100)).isInstanceOf(ImmutableContentStore.StorageFailure.class);
        assertThatThrownBy(() -> store.write("one", sha, bytes, bytes.length - 1)).isInstanceOf(ImmutableContentStore.StorageFailure.class);
        assertThatThrownBy(() -> store.write("../escape", sha, bytes, 100)).isInstanceOf(ImmutableContentStore.StorageFailure.class);
        assertThatThrownBy(() -> store.read("one", "../escape", -1, 100)).isInstanceOf(ImmutableContentStore.StorageFailure.class);
    }
    @Test void rejectsLinksAtBothObjectAndParentWithoutReadingOrWritingTheirTargets() throws Exception {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path root = Files.createDirectory(temporary.resolve("managed"));
        var store = new ImmutableContentStore(root);
        Files.createSymbolicLink(root.resolve("linked"), outside);
        assertThatThrownBy(() -> store.write("linked", sha, bytes, 100)).isInstanceOf(ImmutableContentStore.StorageFailure.class);
        Path objects = Files.createDirectories(root.resolve("run/objects"));
        Path secret = outside.resolve("secret"); Files.writeString(secret, "untouched");
        Files.createSymbolicLink(objects.resolve(sha), secret);
        assertThatThrownBy(() -> store.read("run", sha, -1, 100)).isInstanceOf(ImmutableContentStore.StorageFailure.class);
        assertThatThrownBy(() -> store.write("run", sha, bytes, 100)).isInstanceOf(ImmutableContentStore.StorageFailure.class);
        assertThat(Files.readString(secret)).isEqualTo("untouched");
        assertThat(outside.resolve("objects")).doesNotExist();
    }
    @Test void filesystemOperationsRejectAnActiveDatabaseTransaction() {
        var store = new ImmutableContentStore(temporary);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> store.directory("run")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> store.write("run", sha, bytes, 100)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> store.read("run", sha, -1, 100)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> store.find("run", sha, -1, 100)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> store.location("run")).isInstanceOf(IllegalStateException.class);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        assertThat(temporary.resolve("run")).doesNotExist();
    }
    @Test void reservationAndMissingLookupHaveNoSideEffectsAndCorruptionIsNotAbsence() throws Exception {
        var store = new ImmutableContentStore(temporary);
        assertThat(store.location("run")).isEqualTo(temporary.toRealPath().resolve("run"));
        assertThat(store.find("run",sha,bytes.length,100)).isEmpty();
        assertThat(temporary.resolve("run")).doesNotExist();
        assertThatThrownBy(() -> store.find("run",sha,101,100)).isInstanceOf(ImmutableContentStore.StorageFailure.class);
        store.write("run",sha,bytes,100);
        assertThat(store.find("run",sha,bytes.length,100)).hasValueSatisfying(value -> assertThat(value).containsExactly(bytes));
        Files.writeString(temporary.resolve("run/objects/"+sha),"corrupt");
        assertThatThrownBy(() -> store.find("run",sha,bytes.length,100)).isInstanceOf(ImmutableContentStore.StorageFailure.class);
    }
}
