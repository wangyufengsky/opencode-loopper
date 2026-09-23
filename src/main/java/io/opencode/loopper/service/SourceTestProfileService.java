package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Resolves native test conventions from the frozen manifest without installing frameworks or rewriting build files. */
@Service
public final class SourceTestProfileService {
    private final SourceTemplateMapper runs;
    private final LoopperMapper domain;
    private final SourceSnapshotStorage storage;
    private final SourceTemplateAdmission admission;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final SourceBuildOutputs buildOutputs;
    public SourceTestProfileService(SourceTemplateMapper runs, LoopperMapper domain, SourceSnapshotStorage storage,
            SourceTemplateAdmission admission, TransactionTemplate transactions, ObjectMapper json, SourceBuildOutputs buildOutputs) {
        this.runs = runs; this.domain = domain; this.storage = storage;
        this.admission = admission; this.transactions = transactions; this.json = json; this.buildOutputs = buildOutputs;
    }
    public SourceTestProfile prepare(SourceTemplateRunRow run) {
        if (domain.sourceTestProfile(run.id()).isPresent()) return require(run.id());
        var parameters = json.readValue(run.parametersJson(), SourceTemplateParameters.class);
        var profile = resolve(parameters, json.readValue(run.snapshotJson(), SourceSnapshot.class).manifestSha256(),
                runs.files(run.id()), sha -> storage.read(run.id(), sha));
        String body = json.writeValueAsString(profile);
        transactions.executeWithoutResult(ignored -> {
            var current = admission.require(run.id());
            if (!current.state().equals("PREPARING") || current.version() != run.version()) throw SourceTemplateAdmission.conflict();
            if (domain.insertSourceTestProfile(new SourceDevelopmentContextMapper.TestProfile(run.id(), body,
                    DocumentModelStore.hash(body), Instant.now().toString())) != 1) throw SourceTemplateAdmission.conflict();
        });
        return profile;
    }
    public SourceTestProfile preview(SourceTemplateParameters parameters, SourceTreeCapture.Capture captured) {
        var files = new ArrayList<SourceTemplateMapper.File>();
        for (var file : captured.manifest().files()) files.add(new SourceTemplateMapper.File("preview", files.size(), file.path(),
                file.target() ? 1 : 0, file.sizeBytes(), file.sha256(), file.exclusion()));
        return resolve(parameters, captured.manifest().sha256(), files,
                sha -> new String(captured.contents().get(sha), java.nio.charset.StandardCharsets.UTF_8));
    }
    private SourceTestProfile resolve(SourceTemplateParameters parameters, String manifestSha, List<SourceTemplateMapper.File> all,
            java.util.function.Function<String, String> reader) {
        var configs = new TreeMap<String, SourceTemplateMapper.File>();
        for (var file : all) if (file.sha256() != null && Set.of("pom.xml", "build.gradle", "build.gradle.kts",
                "package.json", "pyproject.toml", "pytest.ini", "setup.cfg").contains(Path.of(file.path()).getFileName().toString()))
            configs.put(file.path(), file);
        var groups = new LinkedHashMap<String, List<String>>();
        for (var source : all) if (source.target() == 1 && source.exclusion() == null) {
            if (!code(source.path())) throw missing(source.path(), "当前没有可识别的本地测试执行规则");
            String config = configs.keySet().stream().filter(c -> beneath(source.path(), parent(c)))
                    .max(Comparator.comparingInt(String::length)).orElseThrow(() -> missing(source.path(), "未找到所属模块的构建配置"));
            groups.computeIfAbsent(config, ignored -> new ArrayList<>()).add(source.path());
        }
        if (groups.isEmpty()) throw missing(parameters.sourcePath(), "没有适用的源码测试对象");
        var modules = new ArrayList<SourceTestProfile.Module>();
        groups.forEach((config, paths) -> modules.add(module(reader, config, paths, configs, all)));
        if (parameters.testOutputPath() != null) {
            String requested = parameters.testOutputPath();
            if (modules.size() != 1 || modules.getFirst().testRoots().stream().noneMatch(root -> beneath(requested, root)))
                throw missing(requested, "指定目录不在已识别的测试源码范围内，需要先调整项目测试配置");
            var module = modules.getFirst();
            modules.set(0, new SourceTestProfile.Module(module.root(), module.framework(), module.sourcePaths(),
                    List.of(requested), module.fixtureRoots(), module.command()));
        }
        var profile = new SourceTestProfile(manifestSha, List.copyOf(modules));
        buildOutputs.validate(SourcePathPolicy.root(parameters.projectRoot()), profile, configs.keySet());
        return profile;
    }
    public SourceTestProfile require(String run) {
        var row = domain.sourceTestProfile(run).orElseThrow(() -> missing(run, "测试配置尚未冻结"));
        if (!DocumentModelStore.hash(row.profileJson()).equals(row.sha256())) throw SourceTemplateAdmission.conflict();
        return json.readValue(row.profileJson(), SourceTestProfile.class);
    }
    private SourceTestProfile.Module module(java.util.function.Function<String, String> reader, String config, List<String> paths,
            Map<String, SourceTemplateMapper.File> configs, List<SourceTemplateMapper.File> all) {
        String root = parent(config), name = Path.of(config).getFileName().toString();
        String text = reader.apply(configs.get(config).sha256());
        var tests = all.stream().filter(f -> f.sha256() != null && beneath(f.path(), root) && SourcePathPolicy.testPath(f.path())).toList();
        var nativeText = new StringBuilder(text);
        if (name.equals("pom.xml")) configs.forEach((candidate, file) -> {
            if (!candidate.equals(config) && candidate.endsWith("pom.xml") && beneath(config, parent(candidate)))
                nativeText.append('\n').append(reader.apply(file.sha256()));
        });
        // Existing test imports provide concrete evidence when a parent supplies the dependency.
        for (var file : tests) if (file.sizeBytes() < 64000) nativeText.append('\n').append(reader.apply(file.sha256()));
        String evidence = nativeText.toString().toLowerCase(Locale.ROOT);
        List<String> testRoots, fixtureRoots = List.of(), command;
        String framework;
        if (name.equals("pom.xml") || name.startsWith("build.gradle")) {
            framework = evidence.contains("junit") ? "junit" : evidence.contains("testng") ? "testng" : null;
            if (framework == null) throw missing(config, "没有可证实的 JUnit 或 TestNG 配置或已有测试");
            String testRoot = join(root, "src/test/java");
            if (name.equals("pom.xml")) {
                var matcher = java.util.regex.Pattern.compile("<testSourceDirectory>\\s*([^<]+)\\s*</testSourceDirectory>").matcher(text);
                if (matcher.find()) {
                    String custom = matcher.group(1).strip();
                    if (custom.contains("$") || custom.startsWith("/") || custom.contains("..")) throw missing(config, "自定义测试目录无法安全解析");
                    testRoot = join(root, custom);
                }
                command = root.equals(".") ? List.of("mvn", "test") : List.of("mvn", "-f", config, "test");
                // Invoke a declared reactor so sibling dependencies are built without installing artifacts.
                for (var candidate : configs.entrySet()) {
                    if (candidate.getKey().equals(config) || !candidate.getKey().endsWith("pom.xml") || !beneath(config, parent(candidate.getKey()))) continue;
                    String reactorRoot = parent(candidate.getKey());
                    String module = reactorRoot.equals(".") ? root : root.substring(reactorRoot.length() + 1);
                    var declared = java.util.regex.Pattern.compile("<module>\\s*" + java.util.regex.Pattern.quote(module) + "\\s*</module>")
                            .matcher(reader.apply(candidate.getValue().sha256())).find();
                    if (declared) { command = List.of("mvn", "-f", candidate.getKey(), "-pl", module, "-am", "test"); break; }
                }
            } else {
                if (text.contains("sourceSets")) throw missing(config, "Gradle 自定义 sourceSets 需要明确测试目录映射");
                command = root.equals(".") ? List.of("gradle", "test") : List.of("gradle", "-p", root, "test");
            }
            testRoots = List.of(testRoot); fixtureRoots = List.of(join(root, "src/test/resources"));
        } else if (name.equals("package.json")) {
            var manifest = json.readTree(text);
            String script = manifest.path("scripts").path("test").asText("");
            framework = script.contains("vitest") ? "vitest" : script.contains("jest") ? "jest" : null;
            if (framework == null || script.contains("passWithNoTests") || script.contains("||"))
                throw missing(config, "test 脚本未提供受支持的可靠测试入口");
            var existing = tests.stream().filter(f -> f.path().endsWith(".ts") || f.path().endsWith(".js")
                    || f.path().endsWith(".tsx") || f.path().endsWith(".jsx")).map(SourceTemplateMapper.File::path).toList();
            if (existing.isEmpty()) throw missing(config, "尚无已有测试，无法确认测试文件的发现目录");
            testRoots = existing.stream().map(SourceTestProfileService::parent).distinct().toList();
            command = root.equals(".") ? List.of("npm", "test", "--", "--run")
                    : List.of("npm", "test", "--prefix", root, "--", "--run");
            if (framework.equals("jest")) command = root.equals(".") ? List.of("npm", "test", "--", "--runInBand")
                    : List.of("npm", "test", "--prefix", root, "--", "--runInBand");
        } else {
            if (!evidence.contains("pytest") && !name.equals("pytest.ini")) throw missing(config, "没有可证实的 pytest 测试配置");
            framework = "pytest"; testRoots = List.of(join(root, "tests"));
            command = List.of("python", "-m", "pytest", join(root, "tests"));
        }
        return new SourceTestProfile.Module(root, framework, List.copyOf(paths), testRoots, fixtureRoots, command);
    }
    static boolean beneath(String path, String root) { return root.equals(".") || path.equals(root) || path.startsWith(root + "/"); }
    static String parent(String path) { return Objects.toString(Path.of(path).getParent(), ".").replace('\\', '/'); }
    static String join(String root, String path) { return root.equals(".") ? path : root + "/" + path; }
    static boolean code(String path) { return path.matches(".*\\.(java|kt|scala|js|jsx|ts|tsx|vue|svelte|py)$"); }
    private static BadRequestException missing(String path, String reason) {
        return new BadRequestException("SOURCE_TEST_CONFIGURATION_REQUIRED", path + "：" + reason
                + "。模板不会修改依赖或构建文件；确认项目测试入口后重新预检。已冻结的任务只能使用原配置，修改配置后请取消原任务并重新发起。");
    }
}
