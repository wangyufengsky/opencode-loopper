package io.opencode.loopper.service;

import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.runtime.GitEvidenceProcess;
import io.opencode.loopper.template.TemplateDateRange;
import io.opencode.loopper.template.TemplateGitEvidence;
import io.opencode.loopper.template.TemplateGitEvidence.Change;
import io.opencode.loopper.template.TemplateGitEvidence.Commit;
import io.opencode.loopper.template.TemplateGitEvidence.Contributor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Collects exact commit-time evidence; no source checkout, hooks, builds or repository instructions run. */
@Service
public class TemplateGitEvidenceCollector {
    private static final int MAX_COMMITS = 100_000;
    private static final long MAX_PATCH_CHARACTERS = 64_000_000;
    private static final Pattern AUTHOR = Pattern.compile("^(.+?)\\s*<([^<>\\r\\n]+)>$");
    private final GitEvidenceProcess git;

    public TemplateGitEvidenceCollector(GitEvidenceProcess git) { this.git = git; }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TemplateGitEvidence collect(TemplateGitSnapshotService.Snapshot snapshot, String branchId, TemplateDateRange dates) {
        Path repository = snapshot.repository();
        String head = snapshot.head();
        var context = new Context(repository, head, new HashMap<>(), snapshot.projectPrefix());
        List<Commit> commits = new ArrayList<>();
        long characters = 0;
        // Git 2.30 has no since-as-filter. Walk every reachable commit, then filter exact timestamps in Java.
        for (int skip = 0; ; skip += 100) {
            List<String> entries = git.read(repository, "log", "--format=%H %ct", "--topo-order",
                    "--max-count=100", "--skip=" + skip, head, "--")
                    .lines().filter(value -> !value.isBlank()).toList();
            for (String entry : entries) {
                String[] fields = entry.split(" ", 2);
                if (!dates.contains(Instant.ofEpochSecond(Long.parseLong(fields[1])))) continue;
                String sha = fields[0];
                Commit commit = commit(context, sha, dates);
                if (commit == null || !context.prefix().isEmpty() && commit.changes().isEmpty()) continue;
                commits.add(commit);
                characters += commit.changes().stream().mapToLong(change -> change.patch().length()).sum();
                if (characters > MAX_PATCH_CHARACTERS || commits.size() > MAX_COMMITS) {
                    throw new TaskFailure("TEMPLATE_EVIDENCE_LIMIT", "统计范围超过完整证据容量，请缩小日期范围后重试；本次未生成完整报告");
                }
            }
            if (entries.size() < 100) break;
        }
        commits.sort(java.util.Comparator.comparing(Commit::committedAt).thenComparing(Commit::sha));
        commits = deduplicate(commits);
        String mailmap = blob(repository, head, ".mailmap");
        return new TemplateGitEvidence(TemplateGitEvidence.VERSION, branchId, head,
                dates.startDate().toString(), dates.endDate().toString(), TemplateDateRange.ZONE.getId(), mailmap, commits);
    }

    private Commit commit(Context context, String sha, TemplateDateRange dates) {
        String[] metadata = git.read(context.repository(), "show", "-s", "--format=%H%x00%P%x00%an%x00%ae%x00%ct%x00%cn%x00%ce%x00%at%x00%B", sha, "--")
                .split("\u0000", 9);
        if (metadata.length != 9 || !sha.equals(metadata[0])) throw invalid("提交元数据无效");
        Instant time = Instant.ofEpochSecond(Long.parseLong(metadata[4]));
        if (!dates.contains(time)) return null;
        List<String> parents = metadata[1].isBlank() ? List.of() : List.of(metadata[1].split(" "));
        List<Contributor> contributors = contributors(context, sha, metadata[2], metadata[3]);
        // Octopus merges have no unique automatic two-parent remerge base. They remain explicitly uncovered,
        // rather than assigning all incoming work to the merger or claiming a successful full review.
        if (parents.size() > 2) throw new TaskFailure("TEMPLATE_OCTOPUS_MERGE", "范围内包含多父合并，当前版本无法完整重建其独有变更，请调整范围");
        List<Change> changes = changes(context, sha, parents);
        String disposition = parents.size() == 2 ? "MERGE_RESOLUTION" : changes.isEmpty() ? "EMPTY" : "ANALYZE";
        var author = identity(context, metadata[2], metadata[3], Instant.ofEpochSecond(Long.parseLong(metadata[7])).toString());
        var committer = identity(context, metadata[5], metadata[6], time.toString());
        List<TemplateGitEvidence.CommitIdentity> coauthors = new ArrayList<>();
        String trailers = git.read(context.repository(), "show", "-s", "--format=%(trailers:key=Co-authored-by,valueonly)", sha, "--");
        for (String trailer : trailers.lines().filter(value -> !value.isBlank()).toList()) {
            var match = AUTHOR.matcher(trailer.strip());
            if (match.matches()) coauthors.add(identity(context, match.group(1), match.group(2), null));
        }
        return new Commit(sha, parents, time.toString(), metadata[8].strip(), contributors, disposition, changes, author, committer, coauthors);
    }

    private TemplateGitEvidence.CommitIdentity identity(Context context, String name, String email, String time) {
        var mapped = contributor(context, name + " <" + email + ">");
        return new TemplateGitEvidence.CommitIdentity(name, email, mapped.name(), mapped.email(), time);
    }

    private List<Contributor> contributors(Context context, String sha, String name, String email) {
        Map<String, Contributor> result = new LinkedHashMap<>();
        Contributor primary = contributor(context, name + " <" + email + ">");
        result.put(primary.identity(), primary);
        String trailers = git.read(context.repository(), "show", "-s", "--format=%(trailers:key=Co-authored-by,valueonly)", sha, "--");
        for (String trailer : trailers.lines().filter(value -> !value.isBlank()).toList()) {
            if (!AUTHOR.matcher(trailer.strip()).matches()) continue;
            Contributor coauthor = contributor(context, trailer.strip());
            result.putIfAbsent(coauthor.identity(), coauthor);
        }
        return List.copyOf(result.values());
    }

    private Contributor contributor(Context context, String value) {
        return context.identities().computeIfAbsent(value, input -> {
            String canonical = git.read(context.repository(), "-c", "mailmap.blob=" + context.head() + ":.mailmap",
                    "check-mailmap", "--", input).strip();
            var parsed = AUTHOR.matcher(canonical);
            if (!parsed.matches()) throw invalid("贡献者身份无法解析");
            String name = parsed.group(1).strip(), email = parsed.group(2).strip().toLowerCase(Locale.ROOT);
            // Only explicit bot identities are excluded; no inference from activity, name similarity or volume.
            boolean robot = name.toLowerCase(Locale.ROOT).endsWith("[bot]") || email.contains("[bot]@");
            return new Contributor(hash(email), name, email, robot);
        });
    }

    private List<Change> changes(Context context, String sha, List<String> parents) {
        String before = parents.size() == 2 ? new TemplateGitMergeBaseline(git).reconstruct(context.repository(), parents)
                : parents.isEmpty() ? null : parents.getFirst();
        // Only the task-owned bare index is updated; source index/worktree never participates.
        git.read(context.repository(), "read-tree", "--reset", "-i", "--no-recurse-submodules", sha);
        List<String> command = showArguments(sha, parents.size() == 2 ? before : null);
        command.addAll(1, List.of("--numstat", "-z"));
        String stats = git.read(context.repository(), command.toArray(String[]::new));
        List<Change> result = new ArrayList<>();
        for (String item : stats.split("\u0000")) {
            if (item.isBlank()) continue;
            String[] fields = item.split("\\t", 3);
            if (fields.length != 3) throw invalid("文件变更计数无效");
            String path = fields[2];
            if (!path.startsWith(context.prefix())) continue;
            boolean binary = fields[0].equals("-") || fields[1].equals("-");
            long additions = binary ? 0 : Long.parseLong(fields[0]);
            long deletions = binary ? 0 : Long.parseLong(fields[1]);
            List<String> patchCommand = showArguments(sha, parents.size() == 2 ? before : null);
            if (!context.prefix().isEmpty()) patchCommand.add(1, "--relative=" + context.prefix());
            patchCommand.add(path);
            patchCommand.addFirst("--literal-pathspecs");
            String patch = sensitive(path) ? "" : git.read(context.repository(), patchCommand.toArray(String[]::new));
            String reason = sensitive(path) ? "SENSITIVE_CONTENT_WITHHELD" : excluded(context.repository(), sha, path, binary, patch);
            result.add(new Change(hash(sha + "\n" + path), path.substring(context.prefix().length()),
                    before == null ? null : blob(context.repository(), before, path),
                    blob(context.repository(), sha, path), additions, deletions, binary,
                    reason == null ? additions + deletions : 0, reason, patch));
        }
        return List.copyOf(result);
    }

    private static List<String> showArguments(String sha, String mergeBaseline) {
        List<String> result = new ArrayList<>(List.of(mergeBaseline == null ? "show" : "diff", "--format=", "--full-index", "--no-ext-diff", "--no-textconv", "--no-renames"));
        if (mergeBaseline != null) result.add(mergeBaseline);
        result.addAll(List.of(sha, "--"));
        return result;
    }

    private String excluded(Path repository, String sha, String path, boolean binary, String patch) {
        if (binary) return "BINARY_NO_LINE_METRIC";
        String[] attributes = git.read(repository, "check-attr", "--cached", "-z", "linguist-generated",
                "linguist-vendored", "--", path).split("\u0000");
        for (int i = 0; i + 2 < attributes.length; i += 3) {
            if (Set.of("set", "true").contains(attributes[i + 2])) return "DECLARED_" + attributes[i + 1].toUpperCase(Locale.ROOT).replace('-', '_');
        }
        if (Arrays.stream(path.split("/")).anyMatch(part -> Set.of("node_modules", ".gradle", ".mvn-cache", "__pycache__").contains(part))) {
            return "VENDORED_OR_CACHE";
        }
        // Whitespace is intentionally significant (Python/YAML etc.). Mechanical exclusions require evidence.
        if (patch.lines().anyMatch(line -> line.matches("(?i)^\\+\\s*(?://|#|/\\*|\\*|<!--)\\s*(?:@generated(?:\\s.*)?|automatically generated.*do not edit.*|code generated .*do not edit.*)$"))) {
            return "GENERATED_MARKER";
        }
        return null;
    }

    private String blob(Path repository, String sha, String path) {
        String entry = git.read(repository, "--literal-pathspecs", "ls-tree", "-z", sha, "--", path);
        if (entry.isEmpty()) return null;
        int tab = entry.indexOf('\t');
        if (tab < 0 || !entry.substring(tab + 1).equals(path + "\u0000")) throw invalid("文件证据路径无效");
        String[] fields = entry.substring(0, tab).split(" ");
        return fields.length == 3 && fields[1].equals("blob") ? fields[2] : null;
    }

    private static String patchKey(String path, String patch) {
        String content = patch.lines().filter(line -> line.startsWith("+") && !line.startsWith("+++")
                || line.startsWith("-") && !line.startsWith("---") || line.startsWith(" "))
                .collect(java.util.stream.Collectors.joining("\n"));
        return hash(path + "\n" + content);
    }

    private static boolean sensitive(String path) {
        return Arrays.stream(path.split("/")).anyMatch(part -> part.equals(".env")
                || part.startsWith(".env.") && !part.equals(".env.example")
                || Set.of(".ssh", ".aws", ".gnupg", "id_rsa", "id_ed25519", "credentials").contains(part)
                || part.endsWith(".p12") || part.endsWith(".pfx") || part.endsWith(".key"));
    }

    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static TaskFailure invalid(String detail) { return new TaskFailure("TEMPLATE_EVIDENCE_INVALID", detail); }
    private static List<Commit> deduplicate(List<Commit> commits) {
        Set<String> seen = new java.util.HashSet<>();
        return commits.stream().map(commit -> {
            List<Change> changes = commit.changes().stream().map(change -> {
                if (change.exclusionReason() != null || seen.add(patchKey(change.path(), change.patch()))) return change;
                return new Change(change.evidenceId(), change.path(), change.beforeBlob(), change.afterBlob(),
                        change.additions(), change.deletions(), change.binary(), 0, "DUPLICATE_PATCH", change.patch());
            }).toList();
            return new Commit(commit.sha(), commit.parents(), commit.committedAt(), commit.message(),
                    commit.contributors(), commit.disposition(), changes, commit.author(), commit.committer(), commit.coauthors());
        }).toList();
    }

    private record Context(Path repository, String head, Map<String, Contributor> identities, String prefix) { }
}
