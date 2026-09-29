package io.opencode.loopper.service.workflow;

import io.opencode.loopper.runtime.DirectWorkspaceLeaseCoordinator;
import io.opencode.loopper.runtime.GitProjectScope;
import io.opencode.loopper.runtime.ImmutableContentStore;
import io.opencode.loopper.service.SourcePathPolicy;
import io.opencode.loopper.workflow.WorkflowCodeSnapshot;
import io.opencode.loopper.workflow.WorkflowDirectorySnapshot;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

/** Bounded ordinary-file inventory. No source writes, Git filters, or following project child links. */
@Component
public final class WorkflowDirectoryFiles {
    private final GitDirectoryTrees trees;
    public WorkflowDirectoryFiles(GitDirectoryTrees trees) { this.trees = trees; }

    /** Persist the returned inventory before copying its bodies. A later copy must not re-discover a new baseline. */
    public WorkflowDirectorySnapshot discover(Path project, Path repository, Path managedDirectory,
                                               List<WorkflowCodeSnapshot.File> tracked) {
        return discover(project,repository,managedDirectory,tracked,Set.of());
    }
    /** Exclusions must be exact temporary paths derived from persisted application records. */
    public WorkflowDirectorySnapshot discover(Path project, Path repository, Path managedDirectory,
                                               List<WorkflowCodeSnapshot.File> tracked,Set<String> temporaryPaths) {
        GitDirectoryTrees.outsideTransaction();
        var identity = identity(project); GitDirectoryTrees.safe(managedDirectory); GitDirectoryTrees.validate(tracked);
        if (managedDirectory.equals(project) || project.startsWith(managedDirectory) || repository.startsWith(project) && !repository.startsWith(managedDirectory))
            throw GitDirectoryTrees.invalid();
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        temporaryPaths.forEach(GitCodeSnapshots::requirePath);
        var first = scan(project, repository, managedDirectory, tracked, temporaryPaths,deadline);
        var second = scan(project, repository, managedDirectory, tracked, temporaryPaths,deadline);
        if (!first.equals(second) || !identity.equals(identity(project))) throw GitDirectoryTrees.invalid();
        return new WorkflowDirectorySnapshot(1, identity.canonicalRoot(), identity.rootFingerprint(), first);
    }

    /** Reads precisely one original file; a changed/missing source is not accepted as a replacement. */
    public byte[] read(WorkflowDirectorySnapshot snapshot, WorkflowCodeSnapshot.File file) {
        GitDirectoryTrees.outsideTransaction(); requireIdentity(snapshot);
        if (!snapshot.files().contains(file)) throw GitDirectoryTrees.invalid();
        byte[] bytes = bytes(Path.of(snapshot.canonicalRoot()), file.path()); GitDirectoryTrees.requireBytes(file, bytes);
        if (!mode(Path.of(snapshot.canonicalRoot()).resolve(file.path())).equals(file.mode())) throw GitDirectoryTrees.invalid();
        requireIdentity(snapshot); return bytes;
    }

    public void requireIdentity(WorkflowDirectorySnapshot snapshot) {
        if (snapshot.version() != 1) throw GitDirectoryTrees.invalid();
        var actual = identity(Path.of(snapshot.canonicalRoot()));
        if (!actual.canonicalRoot().equals(snapshot.canonicalRoot()) || !actual.rootFingerprint().equals(snapshot.rootFingerprint()))
            throw GitDirectoryTrees.invalid();
    }

    private List<WorkflowCodeSnapshot.File> scan(Path project, Path repository, Path managedDirectory,
                                                List<WorkflowCodeSnapshot.File> tracked, Set<String> temporaryPaths,long deadline) {
        var names = new TreeSet<>(trees.paths(repository, project, managedDirectory));
        tracked.forEach(file -> { if (Files.exists(project.resolve(file.path()), LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(project.resolve(file.path()),LinkOption.NOFOLLOW_LINKS)) names.add(file.path()); });
        var files = new ArrayList<WorkflowCodeSnapshot.File>(); long total = 0;
        for (String name : names) {
            // Secret files and the application's own objects are never captured, including when Git lists them.
            if (temporaryPaths.contains(name) || SourcePathPolicy.protectedPath(name) || project.resolve(name).normalize().startsWith(managedDirectory)) continue;
            GitCodeSnapshots.requirePath(name);
            if (files.size() >= GitCodeSnapshots.MAX_FILES || System.nanoTime() >= deadline) throw GitDirectoryTrees.invalid();
            byte[] bytes = bytes(project, name); total += bytes.length;
            if (total > GitCodeSnapshots.MAX_TOTAL_BYTES) throw GitDirectoryTrees.invalid();
            files.add(new WorkflowCodeSnapshot.File(name, mode(project.resolve(name)), GitCodeSnapshots.blobId(bytes, 40), ImmutableContentStore.hash(bytes), bytes.length));
        }
        GitDirectoryTrees.validate(files); return List.copyOf(files);
    }

    private static byte[] bytes(Path project, String name) {
        GitCodeSnapshots.requirePath(name);
        Path file = project.resolve(name); GitDirectoryTrees.safe(file);
        try {
            var before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || before.size() > GitCodeSnapshots.MAX_FILE_BYTES) throw GitDirectoryTrees.invalid();
            byte[] bytes;
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(GitCodeSnapshots.MAX_FILE_BYTES + 1); }
            var after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || bytes.length > GitCodeSnapshots.MAX_FILE_BYTES || bytes.length != before.size()
                    || !Objects.equals(before.fileKey(), after.fileKey()) || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || before.size() != after.size()) throw GitDirectoryTrees.invalid();
            return bytes;
        } catch (IOException failure) { throw GitDirectoryTrees.invalid(); }
    }
    private static String mode(Path path) {
        GitDirectoryTrees.safe(path);
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return "100644";
        try { return Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).contains(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE) ? "100755" : "100644"; }
        catch (IOException | UnsupportedOperationException failure) { throw GitDirectoryTrees.invalid(); }
    }
    private static DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity(Path project) {
        GitDirectoryTrees.outsideTransaction(); GitDirectoryTrees.safe(project);
        try {
            if (!project.equals(project.toRealPath()) || !GitProjectScope.checkoutRoot(project).equals(project)
                    || Files.exists(project.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) throw GitDirectoryTrees.invalid();
            return DirectWorkspaceLeaseCoordinator.identifyDirectory(project);
        } catch (IOException failure) { throw GitDirectoryTrees.invalid(); }
    }
}
