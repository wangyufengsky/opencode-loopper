package io.opencode.loopper.service.workflow;

import io.opencode.loopper.runtime.GitEvidenceProcess;
import io.opencode.loopper.runtime.ImmutableContentStore;
import io.opencode.loopper.runtime.GitCommitIntent;
import io.opencode.loopper.runtime.GitFixedCommits;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.workflow.WorkflowCodeSnapshot;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Writes exact bytes to a server-owned bare object store; no project index, filters, or checkout. */
@Component
public final class GitDirectoryTrees {
    private final GitEvidenceProcess git;
    private final GitFixedCommits commits;
    public GitDirectoryTrees(GitEvidenceProcess git,GitFixedCommits commits) { this.git = git;this.commits=commits; }

    /** The owner must durably reserve this server-owned path before initialization. Safe after a partial init. */
    public void initialize(Path repository) {
        outsideTransaction(); safe(repository);
        requireNoLinks(repository);
        for (String part : List.of("config", "HEAD", "objects", "refs", "hooks", "objects/info/alternates")) safe(repository.resolve(part));
        try { Files.createDirectories(repository); }
        catch (IOException failure) { throw invalid(); }
        var args = List.of("init", "--quiet", "--bare", "--template=", "--object-format=sha1", "--initial-branch=source", repository.toString());
        var response = git.run(repository, Duration.ofSeconds(30), args, environment()); response.requireSuccess(args);
        requireRepository(repository);
    }

    /** A parentless baseline commit with server-owned, stable metadata; retries cannot create a new identity. */
    public String commit(Path repository, String tree, String owner, String createdAt) {
        outsideTransaction(); requireRepository(repository);
        if (tree == null || !tree.matches("[0-9a-f]{40}") || owner == null || !owner.matches("[a-zA-Z0-9-]{1,80}")) throw invalid();
        try{return commits.create(repository,new GitCommitIntent(tree,null,"Workflow directory baseline "+owner+"\n","Loopper","workflow@loopper.invalid",createdAt));}
        catch(ConflictException failure){throw invalid();}
    }

    /** All paths/metadata are checked before any bytes are requested. Repeating the same manifest gives the same tree. */
    public String store(Path repository, List<WorkflowCodeSnapshot.File> files, Function<WorkflowCodeSnapshot.File, byte[]> read) {
        outsideTransaction(); validate(files); requireRepository(repository);
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        Map<String, SortedMap<String, String>> directories = new HashMap<>(); directories.put("", new TreeMap<>());
        for (var file : files) {
            byte[] bytes = read.apply(file);
            requireBytes(file, bytes);
            String blob = input(repository, List.of("hash-object", "-w", "--no-filters", "--stdin"), bytes, deadline);
            if (!blob.equals(file.blob())) throw invalid();
            requireBytes(file,verifyObject(repository,"blob",blob,deadline));
            String parent = parent(file.path()); ensureDirectory(directories, parent);
            directories.get(parent).put(name(file.path()), file.mode() + " blob " + blob);
        }
        String root = null;
        // Descendants must exist before their parent tree. mktree applies Git's byte-order sorting itself.
        for (String directory : directories.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            var body = new StringBuilder();
            directories.get(directory).forEach((name, value) -> body.append(value).append('\t').append(name).append('\0'));
            String tree = input(repository, List.of("mktree", "-z"), body.toString().getBytes(StandardCharsets.UTF_8), deadline);
            if (!tree.matches("[0-9a-f]{40}")) throw invalid();
            verifyObject(repository,"tree",tree,deadline);
            if (directory.isEmpty()) root = tree;
            else directories.get(parent(directory)).put(name(directory), "040000 tree " + tree);
        }
        if (root == null) throw invalid();
        return root;
    }

    /** Enumerates only names; the project is never staged. Previously frozen paths are added by the caller. */
    public List<String> paths(Path repository, Path project, Path managedDirectory) {
        outsideTransaction(); requireRepository(repository); safe(project);
        var args = new ArrayList<>(List.of("--git-dir=" + repository, "--work-tree=" + project,
                "-c", "core.excludesFile=" + nullDevice(), "-c", "core.fsmonitor=false", "-c", "core.untrackedCache=false",
                "ls-files", "--others", "--exclude-standard", "-z", "--", "."));
        if (managedDirectory.startsWith(project)) {
            String relative = project.relativize(managedDirectory).toString().replace('\\', '/');
            if (relative.isEmpty()) throw invalid();
            args.add(":(exclude,literal)" + relative);
        }
        var result = git.input(project, Duration.ofSeconds(30), args, environment(), new byte[0]); result.requireSuccess(args);
        String output;
        try { output = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(result.output())).toString(); }
        catch (java.nio.charset.CharacterCodingException failure) { throw invalid(); }
        if (!output.isEmpty() && !output.endsWith("\0")) throw invalid();
        return Arrays.stream(output.split("\0", -1)).filter(path -> !path.isEmpty()).toList();
    }

    static void validate(List<WorkflowCodeSnapshot.File> files) {
        if (files == null || files.size() > GitCodeSnapshots.MAX_FILES) throw invalid();
        var paths = new HashSet<String>(); long total = 0;
        for (var file : files) {
            if (file == null) throw invalid(); GitCodeSnapshots.requirePath(file.path());
            if (!paths.add(file.path()) || file.mode() == null || !Set.of("100644", "100755").contains(file.mode())
                    || file.blob() == null || !file.blob().matches("[0-9a-f]{40}")
                    || file.sha256() == null || !file.sha256().matches("[0-9a-f]{64}")
                    || file.sizeBytes() < 0 || file.sizeBytes() > GitCodeSnapshots.MAX_FILE_BYTES) throw invalid();
            total += file.sizeBytes(); if (total > GitCodeSnapshots.MAX_TOTAL_BYTES) throw invalid();
        }
        for (String path : paths) for (String ancestor = parent(path); !ancestor.isEmpty(); ancestor = parent(ancestor))
            if (paths.contains(ancestor)) throw invalid();
    }

    static void requireBytes(WorkflowCodeSnapshot.File file, byte[] bytes) {
        if (bytes == null || bytes.length != file.sizeBytes() || !ImmutableContentStore.hash(bytes).equals(file.sha256())
                || !GitCodeSnapshots.blobId(bytes, 40).equals(file.blob())) throw invalid();
    }
    static void safe(Path path) {
        if (!path.isAbsolute() || !path.equals(path.normalize())) throw invalid();
        for (Path part = path; part != null; part = part.getParent()) if (Files.isSymbolicLink(part)) throw invalid();
    }
    static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Directory snapshot I/O in transaction");
    }
    private void requireRepository(Path repository) {
        requireNoLinks(repository);
        safe(repository); safe(repository.resolve("objects")); safe(repository.resolve("refs")); safe(repository.resolve("config"));
        safe(repository.resolve("HEAD")); safe(repository.resolve("objects/info/alternates"));
        if (Files.exists(repository.resolve("objects/info/alternates"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(repository.resolve("index"), LinkOption.NOFOLLOW_LINKS)) throw invalid();
        String bare = input(repository, List.of("rev-parse", "--is-bare-repository"), new byte[0], System.nanoTime() + Duration.ofSeconds(30).toNanos());
        String format = input(repository, List.of("rev-parse", "--show-object-format"), new byte[0], System.nanoTime() + Duration.ofSeconds(30).toNanos());
        if (!bare.equals("true") || !format.equals("sha1")) throw invalid();
    }
    private static void requireNoLinks(Path repository) {
        safe(repository); if (!Files.exists(repository, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(repository)) {
            var iterator = paths.iterator(); int count = 0;
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (++count > 100_000 || Files.isSymbolicLink(path)) throw invalid();
            }
        } catch (IOException failure) { throw invalid(); }
    }
    private String input(Path repository, List<String> operation, byte[] bytes, long deadline) {
        safe(repository);
        var args = new ArrayList<String>(); args.add("--git-dir=" + repository); args.addAll(operation);
        long remaining = deadline - System.nanoTime(); if (remaining <= 0) throw invalid();
        var response = git.input(repository, Duration.ofNanos(Math.min(remaining, Duration.ofSeconds(30).toNanos())), args, environment(), bytes);
        response.requireSuccess(args); return new String(response.output(), StandardCharsets.UTF_8).strip();
    }
    private byte[] verifyObject(Path repository,String type,String id,long deadline) {
        long remaining=deadline-System.nanoTime();if(remaining<=0)throw invalid();
        var args=List.of("--git-dir="+repository,"cat-file",type,id);
        var response=git.input(repository,Duration.ofNanos(Math.min(remaining,Duration.ofSeconds(30).toNanos())),args,environment(),new byte[0]);
        response.requireSuccess(args);byte[] bytes=response.output();
        try {
            var hash=java.security.MessageDigest.getInstance("SHA-1");
            hash.update((type+" "+bytes.length+"\0").getBytes(StandardCharsets.US_ASCII));
            if(!HexFormat.of().formatHex(hash.digest(bytes)).equals(id))throw invalid();
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        return bytes;
    }
    private static void ensureDirectory(Map<String, SortedMap<String, String>> directories, String path) {
        for (String current = path; !current.isEmpty(); current = parent(current)) directories.computeIfAbsent(current, ignored -> new TreeMap<>());
    }
    private static String parent(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? "" : path.substring(0, slash); }
    private static String name(String path) { return path.substring(path.lastIndexOf('/') + 1); }
    private static Map<String, String> environment() { return Map.of("GIT_CONFIG_GLOBAL", nullDevice(), "GIT_CONFIG_SYSTEM", nullDevice()); }
    public void reference(Path repository,String ref,String commit) {
        outsideTransaction();requireRepository(repository);
        if(ref==null||!ref.startsWith("refs/")||!ref.matches("[a-zA-Z0-9/_-]{1,256}")||commit==null||!commit.matches("[0-9a-f]{40}"))throw invalid();
        try{commits.reference(repository,ref,commit);}catch(ConflictException failure){throw invalid();}
    }
    public void select(Path repository,String branch) {
        outsideTransaction();requireRepository(repository);
        if(branch==null||!branch.matches("[a-zA-Z0-9/_-]{1,200}"))throw invalid();
        input(repository,List.of("symbolic-ref","HEAD","refs/heads/"+branch),new byte[0],System.nanoTime()+Duration.ofSeconds(30).toNanos());
    }
    public void requireHead(Path repository,String branch,String commit) {
        outsideTransaction();requireRepository(repository);long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
        if(!input(repository,List.of("symbolic-ref","HEAD"),new byte[0],deadline).equals("refs/heads/"+branch)
                || !input(repository,List.of("rev-parse","HEAD"),new byte[0],deadline).equals(commit))throw invalid();
    }
    private static String nullDevice() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "NUL" : "/dev/null"; }
    static ConflictException invalid() { return new ConflictException("WORKFLOW_DIRECTORY_SNAPSHOT_INVALID", "目录快照缺失、损坏或与冻结记录不一致，现有文件已保留"); }
}
