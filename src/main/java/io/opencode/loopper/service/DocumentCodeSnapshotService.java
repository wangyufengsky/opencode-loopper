package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.GitEvidenceProcess;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Fetches exactly one frozen commit into a bare repository; no checkout, log, build or project script. */
@Service
public class DocumentCodeSnapshotService {
    private final DocumentTemplateStorage storage;
    private final ProjectService projects;
    private final DocumentCodeSnapshotStore store;
    private final GitEvidenceProcess git;
    private final ObjectMapper json;
    public DocumentCodeSnapshotService(DocumentTemplateStorage storage, ProjectService projects,
            DocumentCodeSnapshotStore store, GitEvidenceProcess git, ObjectMapper json) {
        this.storage = storage; this.projects = projects; this.store = store; this.git = git; this.json = json;
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DocumentCodeSnapshotStore.Snapshot freeze(DocumentTemplateRunRow run) {
        var source = Path.of(projects.get(run.projectId()).rootPath());
        var branch = json.readValue(run.branchJson(), ProjectBranchService.Branch.class);
        var existing = run.snapshotJson() == null ? null : json.readValue(run.snapshotJson(), DocumentCodeSnapshotStore.Snapshot.class);
        if (existing != null && existing.ready()) return existing;
        var scope = io.opencode.loopper.runtime.GitProjectScope.require(git, source);
        String remote = scope.repository().toUri().toString();
        if (branch.remote() != null) remote = git.read(source, "remote", "get-url", "--", branch.remote()).strip();
        if (remote.startsWith("ext::") || remote.startsWith("-") || remote.chars().anyMatch(Character::isISOControl))
            throw failure("DOCUMENT_CODE_SOURCE_INVALID", "该来源不支持受控快照读取");
        String sha = existing == null ? resolve(source, remote, branch) : existing.sha();
        var intent = existing == null ? store.plan(run, sha) : existing;
        Path repository = repository(run.id());
        if (!Files.exists(repository.resolve("HEAD"), LinkOption.NOFOLLOW_LINKS))
            git.read(repository.getParent(), "init", "--bare", "--template=", "--", repository.toString());
        var found = git.run(repository, Duration.ofSeconds(10), List.of("cat-file", "-e", intent.sha() + "^{commit}"));
        if (found.exitCode() != 0) {
            var fetched = git.remote(repository, source, Duration.ofSeconds(60), List.of("fetch", "--depth=1", "--no-tags",
                    "--no-write-fetch-head", "--", remote, intent.sha() + ":refs/heads/frozen"), remote);
            fetched.requireSuccess(List.of("fetch"));
        }
        String actual = git.read(repository, "rev-parse", "--verify", intent.sha() + "^{commit}").strip();
        if (!intent.sha().equals(actual)) throw failure("DOCUMENT_CODE_SHA_CHANGED", "代码来源与冻结提交不一致");
        String tree = git.read(repository, "rev-parse", "--verify", scope.nested()
                ? actual + ":" + scope.prefix().substring(0, scope.prefix().length() - 1) : actual + "^{tree}").strip();
        var manifest = manifest(run.id(), git.read(repository, "ls-tree", "-r", "-z", "-l", "--full-tree", tree));
        return store.finish(run, new DocumentCodeSnapshotStore.Snapshot(actual, tree, true, manifest.size()), manifest);
    }
    public Path repository(String runId) {
        Path parent = storage.runtimeDirectory(runId), repository = parent.resolve("code.git");
        if (Files.isSymbolicLink(repository)) throw failure("DOCUMENT_CODE_PATH_INVALID", "冻结代码目录不能是符号链接");
        try {
            if (Files.exists(repository) && !repository.toRealPath().startsWith(parent)) throw new IOException("containment");
        } catch (IOException invalid) { throw failure("DOCUMENT_CODE_PATH_INVALID", "冻结代码目录不可用"); }
        return repository;
    }
    private String resolve(Path source, String remote, ProjectBranchService.Branch branch) {
        if (branch.remote() == null) return sha(git.read(source, "rev-parse", "--verify", branch.ref() + "^{commit}").strip());
        var result = git.remote(source, source, Duration.ofSeconds(60), List.of("ls-remote", "--refs", "--", remote, branch.ref()), remote);
        result.requireSuccess(List.of("ls-remote"));
        var lines = result.output().lines().toList();
        var matches = lines.stream().map(line -> line.split("\t", 2))
                .filter(fields -> fields.length == 2 && fields[1].equals(branch.ref())).toList();
        if (matches.size() != 1) throw failure("DOCUMENT_BRANCH_UNAVAILABLE", "无法唯一确定评审分支，请重新选择");
        return sha(matches.getFirst()[0]);
    }
    private static String sha(String value) {
        if (!value.matches("[0-9a-f]{40}|[0-9a-f]{64}")) throw failure("DOCUMENT_CODE_SHA_INVALID", "提交身份无效");
        return value;
    }
    static List<DocumentCodeMapper.File> manifest(String runId, String output) {
        var result = new ArrayList<DocumentCodeMapper.File>();
        for (String entry : output.split("\u0000", -1)) {
            if (entry.isEmpty()) continue;
            int tab = entry.indexOf('\t');
            if (tab < 0) throw failure("DOCUMENT_CODE_MANIFEST_INVALID", "代码目录输出不完整");
            String[] metadata = entry.substring(0, tab).strip().split(" +");
            if (metadata.length != 4) throw failure("DOCUMENT_CODE_MANIFEST_INVALID", "代码目录元数据不完整");
            String path = entry.substring(tab + 1), limitation = null;
            long size = metadata[3].equals("-") ? 0 : Long.parseLong(metadata[3]);
            if (!metadata[0].equals("100644") && !metadata[0].equals("100755")) limitation = "符号链接或子模块未展开";
            else if (protectedPath(path)) limitation = "受保护文件不提供读取";
            else if (size > 2_000_000) limitation = "文件超过单次源码读取上限";
            result.add(new DocumentCodeMapper.File(runId, path, sha(metadata[2]), metadata[0], size, limitation));
            if (result.size() > 50000) throw failure("DOCUMENT_CODE_MANIFEST_LIMIT", "冻结代码目录超过 50000 项，需缩小项目范围");
        }
        return List.copyOf(result);
    }
    static boolean protectedPath(String path) {
        if (path.startsWith("/") || path.contains("\\") || path.chars().anyMatch(Character::isISOControl)) return true;
        for (String part : path.split("/")) {
            String value = part.toLowerCase(Locale.ROOT);
            if (value.equals("..") || value.equals(".git") || value.equals(".ssh") || value.equals(".aws")
                    || value.equals(".env") || value.startsWith(".env.") && !value.equals(".env.example")
                    || value.endsWith(".pem") || value.endsWith(".key") || value.equals("id_rsa")
                    || value.equals("id_ed25519") || value.equals("credentials")) return true;
        }
        return false;
    }
    private static BadRequestException failure(String code, String message) { return new BadRequestException(code, message); }
}
