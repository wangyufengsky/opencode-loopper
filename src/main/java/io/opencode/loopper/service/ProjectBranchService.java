package io.opencode.loopper.service;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.runtime.GitEvidenceProcess;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Discovers explicit branch sources without fetching into or switching the registered checkout. */
@Service
public class ProjectBranchService {
    private final ProjectService projects;
    private final GitEvidenceProcess git;

    public ProjectBranchService(ProjectService projects, GitEvidenceProcess git) {
        this.projects = projects;
        this.git = git;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Page list(String projectId, String query, String cursor, int limit) {
        if (limit < 1 || limit > 100) throw new BadRequestException("PAGE_LIMIT_INVALID", "每页分支数应在 1–100 之间");
        Discovery discovery = discover(Path.of(projects.get(projectId).rootPath()));
        String after = cursor(cursor);
        String search = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Branch> eligible = discovery.branches().stream()
                .filter(branch -> branch.label().toLowerCase(Locale.ROOT).contains(search))
                .filter(branch -> after == null || branch.id().compareTo(after) > 0)
                .sorted(Comparator.comparing(Branch::id)).limit(limit + 1L).toList();
        List<Branch> items = eligible.stream().limit(limit).toList();
        String next = eligible.size() > limit ? Base64.getUrlEncoder().withoutPadding()
                .encodeToString(items.getLast().id().getBytes(StandardCharsets.UTF_8)) : null;
        return new Page(new CursorPage<>(items, next), discovery.defaultBranchId(), discovery.remoteAvailable(),
                discovery.branches().stream().filter(branch -> branch.id().equals(discovery.defaultBranchId())).findFirst().orElse(null), discovery.remoteProblems());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Branch require(String projectId, String branchId) {
        Path root = Path.of(projects.get(projectId).rootPath());
        if (branchId != null && branchId.startsWith("local:refs/heads/")) {
            git.requireSupported(root);
            String ref = branchId.substring("local:".length());
            boolean found = git.read(root, "for-each-ref", "--format=%(refname)", "refs/heads/")
                    .lines().anyMatch(ref::equals);
            if (found) return new Branch(branchId, ref.substring(11) + "（本地）", ref, null);
            throw new BadRequestException("TEMPLATE_BRANCH_UNAVAILABLE", "本地分支已不存在，请重新选择");
        }
        return discover(root).branches().stream().filter(branch -> branch.id().equals(branchId)).findFirst()
                .orElseThrow(() -> new BadRequestException("TEMPLATE_BRANCH_UNAVAILABLE", "分支已不存在或暂不可访问，请重新选择"));
    }

    Discovery discover(Path root) {
        git.requireSupported(root);
        git.read(root, "rev-parse", "--git-dir");
        List<Branch> branches = new ArrayList<>();
        for (String ref : git.read(root, "for-each-ref", "--format=%(refname)", "refs/heads/").lines().toList()) {
            if (ref.startsWith("refs/heads/")) branches.add(new Branch("local:" + ref, ref.substring(11) + "（本地）", ref, null));
        }
        List<String> remotes = git.read(root, "remote").lines().filter(value -> !value.isBlank()).toList();
        List<String> defaults = new ArrayList<>();
        boolean available = true;
        List<String> problems = new ArrayList<>();
        for (String remote : remotes) {
            try {
                var result = git.remote(root, root, Duration.ofSeconds(30), List.of("ls-remote", "--symref", "--", remote, "HEAD", "refs/heads/*"), remote);
                if (result.exitCode() != 0) { available = false; problems.add(result.diagnostic().message()); continue; }
                addRemote(remote, result.output(), branches, defaults);
            } catch (io.opencode.loopper.domain.TaskFailure failure) {
                if (!"TEMPLATE_GIT_TIMEOUT".equals(failure.code()) && !failure.code().startsWith("GIT_CREDENTIAL_")) throw failure;
                available = false;
                problems.add("TEMPLATE_GIT_TIMEOUT".equals(failure.code())
                        ? "远程分支查询超时，请检查连接；可明确选择本地分支继续" : failure.getMessage());
            }
        }
        String preferred = defaults.stream().filter(value -> value.startsWith("remote:origin:")).findFirst().orElse(null);
        if (preferred == null && defaults.size() == 1) preferred = defaults.getFirst();
        if (preferred == null && available && defaults.isEmpty()) {
            List<Branch> localMain = branches.stream().filter(branch -> branch.remote() == null
                    && List.of("refs/heads/main", "refs/heads/master").contains(branch.ref())).toList();
            if (localMain.size() == 1) preferred = localMain.getFirst().id();
        }
        return new Discovery(List.copyOf(branches), preferred, available, problems.stream().distinct().toList());
    }

    private static void addRemote(String remote, String output, List<Branch> branches, List<String> defaults) {
        String head = null;
        for (String line : output.lines().toList()) {
            String[] fields = line.split("\\t", 2);
            if (fields.length != 2) continue;
            if (fields[0].startsWith("ref: refs/heads/") && fields[1].equals("HEAD")) head = fields[0].substring(5);
            if (fields[0].matches("[0-9a-f]{40,64}") && fields[1].startsWith("refs/heads/")) {
                branches.add(new Branch("remote:" + remote + ":" + fields[1],
                        remote + "/" + fields[1].substring(11) + "（远程）", fields[1], remote));
            }
        }
        String selected = "remote:" + remote + ":" + head;
        if (branches.stream().anyMatch(branch -> branch.id().equals(selected))) defaults.add(selected);
    }

    private static String cursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            if (cursor.length() > 2048) throw new IllegalArgumentException();
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) { throw new BadRequestException("PAGE_CURSOR_INVALID", "分页位置无效，请重新加载分支"); }
    }

    public record Branch(String id, String label, String ref, String remote) { }
    public record Page(CursorPage<Branch> page, String defaultBranchId, boolean remoteAvailable, Branch defaultBranch, List<String> remoteProblems) { }
    record Discovery(List<Branch> branches, String defaultBranchId, boolean remoteAvailable, List<String> remoteProblems) { }
}
