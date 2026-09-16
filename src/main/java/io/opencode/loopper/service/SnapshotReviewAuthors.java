package io.opencode.loopper.service;

import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.runtime.GitBlameLines;
import io.opencode.loopper.runtime.GitEvidenceProcess;
import io.opencode.loopper.template.SnapshotReview.*;
import io.opencode.loopper.template.TemplateReportCompiler;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Optional, deterministic report enrichment. Never creates model work or mutates frozen claims. */
@Service
public class SnapshotReviewAuthors {
    private final TemplateGitSnapshotService repositories;
    private final GitEvidenceProcess git;
    private final DocumentCodeContentCache cache;
    public SnapshotReviewAuthors(TemplateGitSnapshotService repositories, GitEvidenceProcess git, DocumentCodeContentCache cache) {
        this.repositories = repositories; this.git = git; this.cache = cache;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String render(String taskId, Snapshot snapshot, List<Reference> references) {
        if (references.isEmpty()) return "";
        StringBuilder out = new StringBuilder("代码最后修改记录（Git 作者信息；不等于问题引入者或责任认定）：\n\n");
        for (var ref : new LinkedHashSet<>(references)) {
            out.append("- ").append(text(ref.path())).append("：").append(ref.startLine()).append("–").append(ref.endLine())
                    .append(" 行；证据版本 ").append(text(ref.version())).append("\n");
            if ((long) ref.endLine() - ref.startLine() >= 2000) {
                out.append("  作者追溯不可用：单段超过 2,000 行读取边界，未截断追溯。\n"); continue;
            }
            try {
                validate(snapshot, ref);
                String key = TemplateGitEvidenceCollector.hash(ref.path() + "\n" + ref.blob() + "\n" + ref.startLine() + ":" + ref.endLine());
                out.append(cache.read("snapshot-authors:" + taskId, ref.version(), key, () -> load(taskId, ref)));
            } catch (TaskFailure failure) {
                // A missing optional annotation is recoverable; an unconfirmed process stop is not.
                if (failure.code().equals("TEMPLATE_GIT_STOP_UNCONFIRMED") || failure.code().equals("TEMPLATE_GIT_INTERRUPTED")) throw failure;
                out.append("  作者追溯不可用：Git 历史读取失败或超时；保留原始问题证据。\n");
            } catch (IllegalArgumentException | IllegalStateException | java.time.DateTimeException failure) {
                out.append("  作者追溯不可用：冻结文件身份、历史或完整行记录无法确认；保留原始问题证据。\n");
            }
        }
        return out.append("\n问题引入者：未确定；本报告未逐提交验证缺陷引入过程。\n\n").toString();
    }

    private static void validate(Snapshot snapshot, Reference ref) {
        if ((!Objects.equals(snapshot.targetSha(), ref.version()) && !Objects.equals(snapshot.baselineSha(), ref.version()))
                || !oid(ref.version()) || !oid(ref.blob()) || !safePath(ref.path()) || ref.startLine() < 1
                || ref.endLine() < ref.startLine() || (long) ref.endLine() - ref.startLine() >= 2000)
            throw new IllegalArgumentException("Invalid reference");
        boolean allowed = snapshot.files().stream().anyMatch(f -> f.version().equals(ref.version()) && f.path().equals(ref.path())
                && f.blob().equals(ref.blob()) && f.limitation() == null && (f.mode().equals("100644") || f.mode().equals("100755")));
        if (!allowed || DocumentCodeSnapshotService.protectedPath(ref.path())) throw new IllegalArgumentException("Unregistered file");
    }

    private String load(String taskId, Reference ref) {
        Path workspace = repositories.workspace(taskId), repository = workspace.resolve("repository.git");
        try {
            if (Files.isSymbolicLink(workspace) || Files.isSymbolicLink(repository)
                    || !repository.toRealPath().startsWith(workspace.getParent().toRealPath())
                    || Files.exists(repository.resolve("shallow"))) throw new IOException("Missing complete frozen history");
            Path prefixFile = workspace.resolve("project-prefix.txt");
            if (Files.isSymbolicLink(prefixFile) || Files.exists(prefixFile) && Files.size(prefixFile) > 4096) throw new IOException("Invalid scope");
            String prefix = Files.exists(prefixFile) ? Files.readString(prefixFile) : "";
            if (!prefix.isEmpty() && (!prefix.endsWith("/") || !safePath(prefix.substring(0, prefix.length() - 1))))
                throw new IOException("Invalid scope");
            String path = prefix + ref.path();
            String actual = run(repository, List.of("rev-parse", "--verify", ref.version() + ":" + path)).strip();
            if (!actual.equals(ref.blob())) throw new IllegalArgumentException("Frozen blob mismatch");
            String output = run(repository, List.of("blame", "--line-porcelain", "--root", "--no-textconv", "--encoding=UTF-8", "--ignore-revs-file=",
                    "-L", ref.startLine() + "," + ref.endLine(), ref.version(), "--", path));
            return formatRange(repository, ref, output);
        } catch (IOException failure) { throw new IllegalStateException("Frozen repository unavailable", failure); }
    }

    private String formatRange(Path repository, Reference ref, String output) {
        try { return format(GitBlameLines.parse(output, ref.startLine(), ref.endLine())); }
        catch (IllegalArgumentException incomplete) {
            // Historical reads used split("\n", -1), granting an extra empty item after a final LF.
            // Confirm that exact case from the immutable blob before shortening any Git range.
            String blob = run(repository, List.of("cat-file", "blob", ref.blob()));
            long lines = blob.chars().filter(c -> c == '\n').count();
            if (!blob.endsWith("\n") || ref.endLine() != lines + 1 || ref.startLine() > lines) throw incomplete;
            return format(GitBlameLines.parse(output, ref.startLine(), (int) lines))
                    + "  文件末尾第 " + ref.endLine() + " 项是历史证据按换行拆分产生的空项，不对应独立 Git 代码行；以上列出全部实际代码行的作者。\n";
        }
    }

    private String run(Path repository, List<String> arguments) {
        var result = git.run(repository, Duration.ofSeconds(10), arguments);
        result.requireSuccess(arguments); return result.output();
    }
    private static String format(List<GitBlameLines.Line> lines) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size();) {
            var first = lines.get(i); int end = i;
            while (end + 1 < lines.size() && lines.get(end + 1).commit().equals(first.commit())) end++;
            out.append("  - 行 ").append(first.number()).append("–").append(lines.get(end).number()).append("：")
                    .append(text(first.author())).append(" ").append(text(first.email())).append("；作者时间 ").append(first.authoredAt())
                    .append("；提交 ").append(first.commit()).append("；说明：").append(text(first.summary())).append("\n");
            i = end + 1;
        }
        return out.toString();
    }
    private static boolean oid(String value) { return value != null && value.matches("[0-9a-f]{40}|[0-9a-f]{64}"); }
    private static boolean safePath(String value) {
        return value != null && !value.isBlank() && !value.startsWith("/") && !value.contains("\\")
                && value.chars().noneMatch(c -> c < 32) && Arrays.stream(value.split("/", -1)).noneMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."));
    }
    private static String text(String value) { return TemplateReportCompiler.text(value); }
}
