package io.opencode.loopper.service.workflow;

import io.opencode.loopper.runtime.GitEvidenceProcess;
import io.opencode.loopper.runtime.ImmutableContentStore;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.service.SourcePathPolicy;
import io.opencode.loopper.workflow.WorkflowCodeSnapshot;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.BiConsumer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Reads exact Git objects only. It never stages, checks out, follows file links or runs Git filters. */
@Component
public final class GitCodeSnapshots {
    public static final int MAX_FILE_BYTES = 4_000_000, MAX_FILES = 10_000;
    public static final long MAX_TOTAL_BYTES = 128L * 1024 * 1024;
    private final GitEvidenceProcess git;
    public GitCodeSnapshots(GitEvidenceProcess git) { this.git = git; }
    public record Captured(List<WorkflowCodeSnapshot.File> files, List<WorkflowCodeSnapshot.Change> changes) { }
    private record Entry(String path, String mode, String blob, long size) { }

    public Captured capture(Path repository, String baseline, String result, String prefix, BiConsumer<String, byte[]> write) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Git snapshot I/O in transaction");
        objectId(baseline); objectId(result);
        if (prefix == null || !prefix.isEmpty() && (!prefix.endsWith("/") || !safePath(prefix.substring(0, prefix.length()-1)))) throw invalid();
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        var before = tree(repository, baseline, deadline);
        var after = tree(repository, result, deadline);
        var paths = new TreeSet<>(before.keySet()); paths.addAll(after.keySet());
        var changes = new ArrayList<WorkflowCodeSnapshot.Change>();
        for (String path : paths) {
            Entry old = before.get(path), next = after.get(path);
            if (!Objects.equals(old, next)) {
                if (!path.startsWith(prefix)) throw new ConflictException("WORK_CODE_OUTSIDE_PROJECT", "代码快照含项目目录外变更，不能作为本节点交付");
                String name = path.substring(prefix.length()); requirePath(name);
                changes.add(new WorkflowCodeSnapshot.Change(name, old == null ? "ADD" : next == null ? "DELETE" : "MODIFY",
                        old == null ? null : old.blob(), next == null ? null : next.blob()));
            }
        }
        var selected = after.values().stream().filter(file -> file.path().startsWith(prefix)).toList();
        if (selected.size() > MAX_FILES || selected.stream().mapToLong(Entry::size).sum() > MAX_TOTAL_BYTES) throw limit();
        // Validate the entire manifest before reading or writing any file bytes.
        for (var file : selected) {
            requirePath(file.path().substring(prefix.length()));
            if (!Set.of("100644", "100755").contains(file.mode())) throw new ConflictException("WORK_CODE_SPECIAL_FILE", "代码交付暂不支持符号链接或子模块，请使用普通文件");
            if (file.size() < 0 || file.size() > MAX_FILE_BYTES) throw limit();
        }
        var files = new ArrayList<WorkflowCodeSnapshot.File>();
        for (var file : selected) {
            var args = List.of("cat-file", "blob", file.blob());
            var response = git.bytes(repository, remaining(deadline), args); response.requireSuccess(args);
            byte[] bytes = response.output();
            if (bytes.length != file.size() || !blobId(bytes, file.blob().length()).equals(file.blob())) throw invalid();
            String hash = ImmutableContentStore.hash(bytes);
            write.accept(hash, bytes);
            files.add(new WorkflowCodeSnapshot.File(file.path().substring(prefix.length()), file.mode(), file.blob(), hash, bytes.length));
        }
        return new Captured(List.copyOf(files), List.copyOf(changes));
    }
    private SortedMap<String, Entry> tree(Path repository, String id, long deadline) {
        var typeArgs = List.of("cat-file", "-t", id);
        var type = git.run(repository, remaining(deadline), typeArgs); type.requireSuccess(typeArgs);
        if (!type.output().strip().equals("tree")) throw invalid();
        var args = List.of("ls-tree", "--full-tree", "-r", "-z", "-l", id);
        var result = git.bytes(repository, remaining(deadline), args); result.requireSuccess(args);
        String output;
        try { output = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(result.output())).toString(); }
        catch (CharacterCodingException failure) { throw invalid(); }
        if (!output.isEmpty() && !output.endsWith("\0")) throw invalid();
        var entries = new TreeMap<String, Entry>();
        for (String row : output.split("\0", -1)) {
            if (row.isEmpty()) continue;
            int tab = row.indexOf('\t'); if (tab < 1) throw invalid();
            String path = row.substring(tab + 1); if (!safePath(path)) throw invalid();
            String[] fields = row.substring(0, tab).strip().split("\\s+");
            if (fields.length != 4) throw invalid();
            objectId(fields[2]);
            long size;
            try { size = fields[3].equals("-") ? -1 : Long.parseLong(fields[3]); }
            catch (NumberFormatException failure) { throw invalid(); }
            if (entries.put(path, new Entry(path, fields[0], fields[2], size)) != null || entries.size() > 50_000) throw limit();
        }
        return entries;
    }
    static void requirePath(String path) { if (path == null || !safePath(path) || SourcePathPolicy.protectedPath(path)) throw new ConflictException("WORK_CODE_PROTECTED_PATH", "代码快照包含受保护或不安全的路径，未发布交付"); }
    private static boolean safePath(String path) {
        if (path.isBlank() || path.length() > 2048 || path.startsWith("/") || path.contains("\\") || path.contains(":")
                || path.chars().anyMatch(Character::isISOControl)) return false;
        return Arrays.stream(path.split("/", -1)).noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..") || part.equalsIgnoreCase(".git"));
    }
    static String blobId(byte[] bytes, int length) {
        try {
            var digest = MessageDigest.getInstance(length == 40 ? "SHA-1" : "SHA-256");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime(); if (nanos <= 0) throw limit();
        return Duration.ofNanos(Math.min(nanos, Duration.ofSeconds(30).toNanos()));
    }
    private static void objectId(String value) { if (value == null || !value.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) throw invalid(); }
    private static ConflictException invalid() { return new ConflictException("WORK_CODE_SNAPSHOT_INVALID", "冻结代码对象不可用或与清单不符，保留原记录"); }
    private static ConflictException limit() { return new ConflictException("WORK_CODE_SNAPSHOT_LIMIT", "代码快照超过文件数量、字节或读取时限，未发布部分交付"); }
}
