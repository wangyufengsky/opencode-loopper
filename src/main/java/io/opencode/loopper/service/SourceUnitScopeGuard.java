package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Mandatory whole-tree guard runs independently of GIT_DIFF's optional outside-addition approval. */
@Service
public final class SourceUnitScopeGuard implements io.opencode.loopper.verification.VerificationScopeGuard {
    private final LoopperMapper domain;
    private final SourceTemplateMapper runs;
    private final SourceTestProfileService profiles;
    private final SourceSnapshotStorage storage;
    private final LoopperProperties properties;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    public SourceUnitScopeGuard(LoopperMapper domain, SourceTemplateMapper runs, SourceTestProfileService profiles,
            SourceSnapshotStorage storage, LoopperProperties properties, TransactionTemplate transactions, ObjectMapper json) {
        this.domain = domain; this.runs = runs; this.profiles = profiles; this.storage = storage;
        this.properties = properties; this.transactions = transactions; this.json = json;
    }
    public void freeze(SourceTemplateRunRow run) {
        if (domain.sourceTestBaseline(run.id()).isPresent()) return;
        var parameters = json.readValue(run.parametersJson(), SourceTemplateParameters.class);
        Path root = SourcePathPolicy.root(parameters.projectRoot());
        var first = SourceTestTree.scan(root, data());
        var second = SourceTestTree.scan(root, data());
        if (!first.equals(second)) throw SourceTestTree.failure("测试写入基线采集期间文件发生变化，请停止修改后恢复");
        for (var file : runs.files(run.id())) if (file.sha256() != null) {
            var current = first.get(file.path());
            if (current == null || !current.sha256().equals(file.sha256())) throw drift();
        }
        String body = json.writeValueAsString(first);
        transactions.executeWithoutResult(ignored -> {
            var latest = runs.find(run.id()).orElseThrow();
            if (!latest.state().equals("PREPARING") || latest.version() != run.version()) throw SourceTemplateAdmission.conflict();
            domain.insertSourceTestBaseline(new SourceDevelopmentContextMapper.TestProfile(run.id(), body, DocumentModelStore.hash(body), Instant.now().toString()));
        });
    }
    public void beforeStart(TaskRow task) {
        var run = domain.sourceDevelopmentTask(task.id()).orElse(null);
        if (run == null || task.baselineCommit() != null) return;
        var parameters = json.readValue(run.parametersJson(), SourceTemplateParameters.class);
        var actual = SourceTestTree.scan(SourcePathPolicy.root(parameters.projectRoot()), data());
        if (!baseline(run.id()).equals(actual)) throw drift();
    }
    @Override public void verify(String task, Path worktree) {
        var run = domain.sourceDevelopmentTask(task).orElse(null);
        if (run == null) return;
        check(run, worktree);
    }
    public void check(SourceTemplateRunRow run, Path worktree) {
        Path root = SourcePathPolicy.root(worktree.toString());
        var before = baseline(run.id()); var after = SourceTestTree.scan(root, data());
        var profile = profiles.require(run.id());
        var names = new TreeSet<String>(before.keySet()); names.addAll(after.keySet());
        var violations = new ArrayList<String>();
        for (String path : names) {
            var old = before.get(path); var current = after.get(path);
            if (Objects.equals(old, current)) continue;
            if (current == null) { violations.add(path + "：禁止删除或重命名已有文件"); continue; }
            if (!current.kind().equals("FILE") || old != null && !old.kind().equals("FILE")) {
                violations.add(path + "：不允许创建或改变符号链接及特殊文件"); continue;
            }
            boolean fixture = profile.modules().stream().flatMap(m -> m.fixtureRoots().stream()).anyMatch(r -> SourceTestProfileService.beneath(path, r));
            boolean test = profile.modules().stream().anyMatch(m -> m.testRoots().stream().anyMatch(r -> SourceTestProfileService.beneath(path, r))
                    && testSource(path, m.framework()));
            if (!fixture && !test || SourcePathPolicy.exclusion(path, fixture) != null) {
                violations.add(path + "：不属于冻结测试源码或夹具范围"); continue;
            }
            if (test) {
                if (current.size() > SourceTreeCapture.MAX_FILE_BYTES) { violations.add(path + "：测试文件超过校验上限"); continue; }
                String value = readTest(root, path, current);
                String original = "";
                if (old != null) {
                    var frozen = runs.file(run.id(), path).orElseThrow(() -> SourceTestTree.failure("已有测试缺少冻结正文，不能批准修改"));
                    original = frozen.sha256() == null ? "" : storage.read(run.id(), frozen.sha256());
                    if (frozen.sha256() == null || !SourceExistingTests.preserved(path, original, value))
                        violations.add(path + "：已有测试正文或断言被移除或改写，请保留已有测试，用新增测试文件补齐场景");
                }
                if (disabledCount(value) > disabledCount(original)) violations.add(path + "：检测到屏蔽测试执行的语法");
            }
        }
        if (!violations.isEmpty()) throw new TaskFailure("SOURCE_TEST_WRITE_RANGE_VIOLATION",
                "单元测试模板范围检查未通过：" + String.join("；", violations.stream().limit(20).toList()));
    }
    private Map<String, SourceTestTree.File> baseline(String run) {
        var row = domain.sourceTestBaseline(run).orElseThrow(() -> SourceTestTree.failure("测试写入基线不存在"));
        if (!DocumentModelStore.hash(row.profileJson()).equals(row.sha256())) throw SourceTestTree.failure("测试基线哈希不一致");
        return json.readValue(row.profileJson(), new TypeReference<>() { });
    }
    static boolean testSource(String path, String framework) {
        if (Set.of("junit", "testng").contains(framework)) return path.matches(".*\\.(java|kt|scala)$");
        if (framework.equals("pytest")) return path.endsWith(".py");
        return SourcePathPolicy.testPath(path) && path.matches(".*\\.[jt]sx?$");
    }
    static boolean disabled(String value) {
        return disabledCount(value) > 0;
    }
    private static long disabledCount(String value) {
        return java.util.regex.Pattern.compile("@(?:[\\w$]+\\.)*(?:Disabled|Ignore)\\b|enabled\\s*=\\s*false|\\b(?:describe|it|test)\\.(?:skip|only)\\b|\\b(?:xdescribe|xit|xtest)\\s*\\(|pytest\\.mark\\.(?:skip|xfail)|unittest\\.skip").matcher(value).results().count();
    }
    private static String readTest(Path root, String path, SourceTestTree.File expected) {
        Path file = root.resolve(path);
        SourcePathPolicy.requireContained(root, file);
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes((int) SourceTreeCapture.MAX_FILE_BYTES + 1);
            SourcePathPolicy.requireContained(root, file);
            if (bytes.length != expected.size() || !HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expected.sha256()))
                throw SourceTestTree.failure("测试源码在校验期间变化，请停止修改后重试");
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException invalid) { throw SourceTestTree.failure("测试源码无法安全读取"); }
    }
    private Path data() { return properties.getDataDir().toAbsolutePath().normalize(); }
    private static TaskFailure drift() { return new TaskFailure("SOURCE_TEST_INPUT_DRIFT", "源码、测试或项目配置已偏离冻结输入，不能沿用旧设计开始写入；请核对差异后处理原任务"); }
}
