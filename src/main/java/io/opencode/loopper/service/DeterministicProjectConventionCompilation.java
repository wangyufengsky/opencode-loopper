package io.opencode.loopper.service;

import static io.opencode.loopper.service.ProjectConventionCompilation.ProblemClass.MECHANICAL;
import static io.opencode.loopper.service.ProjectConventionCompilation.ProblemClass.SECURITY;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Closed candidate validation, Legacy adaptation, rendering, merge and hashing core. */
@Component
public final class DeterministicProjectConventionCompilation implements ProjectConventionCompilation {
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`\\r\\n]+)`");
    private static final Set<String> SAFE_EXECUTABLES = Set.of(
            "mvn", "./mvnw", "gradle", "./gradlew", "npm", "pnpm", "yarn",
            "python", "python3", "pytest", "go", "cargo");
    private static final Set<List<String>> SAFE_ARGV = Set.of(
            List.of("mvn", "package"), List.of("mvn", "test"),
            List.of("./mvnw", "package"), List.of("./mvnw", "test"),
            List.of("gradle", "build"), List.of("gradle", "test"),
            List.of("./gradlew", "build"), List.of("./gradlew", "test"),
            List.of("npm", "ci"), List.of("npm", "test"),
            List.of("pnpm", "test"), List.of("yarn", "test"),
            List.of("python3", "-m", "pytest"), List.of("python3", "-m", "unittest"),
            List.of("python", "-m", "pytest"), List.of("python", "-m", "unittest"),
            List.of("pytest"), List.of("go", "build", "./..."), List.of("go", "test", "./..."),
            List.of("cargo", "build"), List.of("cargo", "test"));
    private static final List<String> HEADINGS =
            List.of("## 技术栈与模块", "## 构建与测试", "## 目录与边界");
    private final ProjectConventionCandidateCodec codec;
    private final ProjectConventionDocumentStore documents;

    public DeterministicProjectConventionCompilation(ObjectMapper json, ProjectConventionDocumentStore documents) {
        this.codec = new ProjectConventionCandidateCodec(json);
        this.documents = documents;
    }

    @Override
    public Result compileCandidate(Input input, String candidateJson) {
        ProjectConventionCandidateCodec.Decoded decoded = codec.decode(candidateJson);
        return decoded.valid() ? compile(input, decoded.candidate()) : rejected(decoded.problems());
    }

    @Override
    public Result compileLegacy(Input input, String markdown) {
        List<Problem> catalogProblems = validateCatalog(input.evidenceCatalog());
        if (!catalogProblems.isEmpty()) return rejected(catalogProblems);
        if (markdown == null || markdown.isBlank() || HEADINGS.stream().anyMatch(heading -> !markdown.contains(heading))) {
            return rejected(List.of(problem("PROJECT_CONTEXT_SECTION_MISSING", "/legacy",
                    "Legacy 项目上下文必须包含三个固定章节", HEADINGS)));
        }
        Map<String, CommandEvidence> commandsByText = new LinkedHashMap<>();
        Set<String> ambiguousCommands = new LinkedHashSet<>();
        input.evidenceCatalog().commands().forEach(command -> {
            String text = String.join(" ", command.argv());
            if (commandsByText.putIfAbsent(text, command) != null) ambiguousCommands.add(text);
        });
        Map<String, PathEvidence> pathsByText = new LinkedHashMap<>();
        input.evidenceCatalog().paths().forEach(path -> pathsByText.put(path.path(), path));
        LinkedHashSet<String> commandIds = new LinkedHashSet<>();
        LinkedHashSet<String> pathIds = new LinkedHashSet<>();
        Problems problems = new Problems();
        Matcher code = INLINE_CODE.matcher(markdown);
        int references = 0;
        while (code.find()) {
            if (++references > 128) {
                problems.add(problem("PROJECT_CONVENTION_LEGACY_SIZE_INVALID", "/legacy",
                        "Legacy 项目上下文最多包含 128 个冻结证据引用", List.of()));
                break;
            }
            String value = code.group(1).strip();
            CommandEvidence command = commandsByText.get(value);
            PathEvidence path = pathsByText.get(value);
            if (ambiguousCommands.contains(value)) {
                problems.add(problem("PROJECT_CONVENTION_COMMAND_AMBIGUOUS", "/legacy",
                        "Legacy 命令在多个冻结组件中含义不唯一，必须失败关闭", List.of()));
            } else if (command != null) {
                commandIds.add(command.id());
            } else if (path != null) {
                pathIds.add(path.id());
            } else if (unsafeReference(value)) {
                problems.add(securityProblem(pathLike(value) ? "PROJECT_CONVENTION_PATH_UNSAFE"
                                : "PROJECT_CONVENTION_COMMAND_UNSAFE", "/legacy",
                        "Legacy 项目上下文包含不安全的命令或路径引用"));
            } else if (commandLike(value)) {
                problems.add(problem("PROJECT_CONVENTION_COMMAND_UNVERIFIED", "/legacy",
                        "Legacy 命令必须精确匹配冻结命令证据",
                        commandsByText.keySet().stream().limit(32).toList()));
            } else {
                problems.add(problem("PROJECT_CONVENTION_PATH_UNVERIFIED", "/legacy",
                        "Legacy 路径必须精确匹配冻结路径证据",
                        pathsByText.keySet().stream().limit(32).toList()));
            }
        }
        validateLegacyTechnologies(markdown, input.evidenceCatalog(), problems);
        if (!problems.empty()) return rejected(problems.values());
        Candidate adapted = new Candidate(CONTRACT_VERSION,
                input.evidenceCatalog().components().stream().map(ComponentEvidence::key).toList(),
                List.copyOf(commandIds), List.copyOf(pathIds));
        return compile(input, adapted);
    }

    private Result compile(Input input, Candidate source) {
        EvidenceCatalog catalog = input.evidenceCatalog();
        List<Problem> catalogProblems = validateCatalog(catalog);
        if (!catalogProblems.isEmpty()) return rejected(catalogProblems);
        if (bytes(input.sourceContent()) > 256 * 1024) {
            return rejected(List.of(securityProblem("PROJECT_CONVENTION_SOURCE_SIZE_INVALID", "/sourceContent",
                    "AGENTS.md 冻结源内容超过 256 KiB 编译边界")));
        }
        Problems problems = new Problems();
        if (!CONTRACT_VERSION.equals(source.contractVersion())) {
            problems.add(problem("PROJECT_CONVENTION_CONTRACT_VERSION_INVALID", "/contractVersion",
                    "项目公约候选合同版本不受支持", List.of(CONTRACT_VERSION)));
        }
        List<String> componentKeys = select(source.componentKeys(), catalog.components().stream()
                .map(ComponentEvidence::key).toList(), "/componentKeys", "PROJECT_CONVENTION_COMPONENT_UNVERIFIED", problems);
        List<String> commandIds = select(source.commandIds(), catalog.commands().stream()
                .map(CommandEvidence::id).toList(), "/commandIds", "PROJECT_CONVENTION_COMMAND_UNVERIFIED", problems);
        List<String> pathIds = select(source.pathIds(), catalog.paths().stream()
                .map(PathEvidence::id).toList(), "/pathIds", "PROJECT_CONVENTION_PATH_UNVERIFIED", problems);
        if (!problems.empty()) return rejected(problems.values());

        Candidate candidate = new Candidate(CONTRACT_VERSION, componentKeys, commandIds, pathIds);
        String canonicalCandidate = codec.canonical(candidate);
        String context = render(candidate, catalog);
        String proposed;
        try {
            proposed = documents.merge(input.sourceContent(), context);
        } catch (BadRequestException invalidSource) {
            return rejected(List.of(securityProblem("PROJECT_CONVENTION_SOURCE_INVALID", "/sourceContent",
                    "AGENTS.md 冻结源内容无法安全合并")));
        }
        String sourceHash = sha256(input.sourceContent());
        String contentHash = sha256(proposed);
        String resultHash = sha256(canonicalCandidate + "\n" + context + "\n" + proposed + "\n"
                + sourceHash + "\n" + catalog.stackFingerprint());
        return new Result(candidate, canonicalCandidate, context, proposed, contentHash, sourceHash,
                resultHash, List.of());
    }

    private List<Problem> validateCatalog(EvidenceCatalog catalog) {
        if (catalog == null || catalog.stackFingerprint() == null
                || !catalog.stackFingerprint().matches("[0-9a-f]{64}")) {
            return List.of(securityProblem("PROJECT_CONVENTION_EVIDENCE_INVALID", "/evidence",
                    "冻结项目公约证据目录身份无效"));
        }
        if (catalog.components().size() > 64 || catalog.commands().size() > 64
                || catalog.paths().size() > 128) {
            return List.of(securityProblem("PROJECT_CONVENTION_EVIDENCE_SIZE_INVALID", "/evidence",
                    "冻结项目公约证据目录超过组件 64、命令 64 或路径 128 的边界"));
        }
        Map<String, String> components = new LinkedHashMap<>();
        Set<String> componentRoots = new LinkedHashSet<>();
        for (ComponentEvidence component : catalog.components()) {
            if (component == null || blank(component.key()) || blank(component.relativeRoot())
                    || bytes(component.key()) > 256 || bytes(component.relativeRoot()) > 1_024
                    || component.technologies().size() > 32 || component.buildTools().size() > 32
                    || component.testFrameworks().size() > 32
                    || invalidFacts(component.technologies()) || invalidFacts(component.buildTools())
                    || invalidFacts(component.testFrameworks())
                    || components.putIfAbsent(component.key(), component.relativeRoot()) != null) {
                return List.of(securityProblem("PROJECT_CONVENTION_EVIDENCE_INVALID", "/evidence/components",
                        "冻结组件证据不完整或重复"));
            }
            if (!safePath(component.relativeRoot(), PathKind.COMPONENT_ROOT)) {
                return List.of(securityProblem("PROJECT_CONVENTION_EVIDENCE_PATH_UNSAFE",
                        "/evidence/components", "冻结组件根目录必须是规范、受管且无穿越的仓库相对路径"));
            }
            if (!componentRoots.add(component.relativeRoot())) {
                return List.of(securityProblem("PROJECT_CONVENTION_EVIDENCE_PATH_UNSAFE",
                        "/evidence/components", "冻结组件根目录不得重复归属"));
            }
        }
        Set<String> commands = new LinkedHashSet<>();
        for (CommandEvidence command : catalog.commands()) {
            if (command == null || blank(command.id()) || !components.containsKey(command.componentKey())
                    || bytes(command.id()) > 256 || command.argv().isEmpty() || command.argv().size() > 16
                    || !commands.add(command.id())) {
                return List.of(securityProblem("PROJECT_CONVENTION_EVIDENCE_INVALID", "/evidence/commands",
                        "冻结命令证据不完整或重复"));
            }
            if (!safeArgv(command.argv())) {
                return List.of(securityProblem("PROJECT_CONVENTION_EVIDENCE_COMMAND_UNSAFE",
                        "/evidence/commands", "冻结命令证据必须使用闭集可执行入口和安全直接 argv"));
            }
        }
        Set<String> paths = new LinkedHashSet<>();
        Set<String> pathValues = new LinkedHashSet<>();
        for (PathEvidence path : catalog.paths()) {
            if (path == null || blank(path.id()) || !components.containsKey(path.componentKey())
                    || bytes(path.id()) > 512 || blank(path.path()) || bytes(path.path()) > 1_024
                    || path.kind() == null
                    || !paths.add(path.id())) {
                return List.of(securityProblem("PROJECT_CONVENTION_EVIDENCE_INVALID", "/evidence/paths",
                        "冻结路径证据不完整或重复"));
            }
            if (!safePath(path.path(), path.kind())) {
                return List.of(securityProblem("PROJECT_CONVENTION_EVIDENCE_PATH_UNSAFE",
                        "/evidence/paths", "冻结路径证据必须是规范、受管且无穿越的仓库相对路径"));
            }
            if (!pathValues.add(path.path())) {
                return List.of(securityProblem("PROJECT_CONVENTION_EVIDENCE_PATH_UNSAFE",
                        "/evidence/paths", "冻结路径证据不得重复归属"));
            }
            String componentRoot = components.get(path.componentKey());
            if (path.kind() == PathKind.COMPONENT_ROOT && !path.path().equals(componentRoot)
                    || path.kind() == PathKind.MANIFEST && !withinComponent(componentRoot, path.path())) {
                return List.of(securityProblem("PROJECT_CONVENTION_EVIDENCE_PATH_UNSAFE",
                        "/evidence/paths", "冻结路径证据必须属于声明的组件根目录"));
            }
        }
        return List.of();
    }

    private static boolean safeArgv(List<String> argv) {
        if (argv == null || argv.isEmpty() || !SAFE_ARGV.contains(argv)) return false;
        int total = 0;
        for (String value : argv) {
            if (value == null || value.isBlank() || bytes(value) > 256
                    || value.chars().anyMatch(Character::isISOControl)
                    || value.contains(";") || value.contains("&&") || value.contains("||")
                    || value.contains("|") || value.contains(">") || value.contains("<")
                    || value.contains("$(") || value.contains("`")) return false;
            total += bytes(value);
            if (total > 2_048) return false;
        }
        return true;
    }

    private static boolean safePath(String value, PathKind kind) {
        if (value == null || value.isBlank() || value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0
                || value.startsWith("/") || value.matches("^[A-Za-z]:.*") || value.startsWith("./")
                || value.contains("//") || value.endsWith("/")) return false;
        if (".".equals(value)) return kind == PathKind.COMPONENT_ROOT;
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                    || ".git".equals(segment) || ".env".equals(segment)
                    || segment.startsWith(".env.") && !".env.example".equals(segment)
                    || segment.chars().anyMatch(Character::isISOControl)) return false;
        }
        return true;
    }

    private static boolean withinComponent(String componentRoot, String path) {
        return ".".equals(componentRoot) || path.startsWith(componentRoot + "/");
    }

    private static boolean unsafeReference(String value) {
        if (value == null || value.isBlank() || bytes(value) > 1_024
                || value.startsWith("/") || value.matches("^[A-Za-z]:.*") || value.indexOf('\\') >= 0
                || value.contains("../") || value.equals("..") || value.contains("/.git")
                || value.startsWith(".git") || value.contains("/.env") || value.startsWith(".env")
                || value.contains(";") || value.contains("&&") || value.contains("||")
                || value.contains("|") || value.contains(">") || value.contains("<")
                || value.contains("$(") || value.indexOf('\0') >= 0) return true;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("rm ") || lower.startsWith("sudo ") || lower.startsWith("sh ")
                || lower.startsWith("bash ") || lower.startsWith("cmd ") || lower.startsWith("powershell ");
    }

    private static boolean commandLike(String value) {
        return value != null && (value.indexOf(' ') >= 0 || value.indexOf('\t') >= 0
                || SAFE_EXECUTABLES.stream().anyMatch(executable -> value.startsWith(executable)));
    }

    private static boolean pathLike(String value) {
        return value != null && (value.contains("/") || value.contains("\\") || value.startsWith("."));
    }

    private static void validateLegacyTechnologies(String markdown, EvidenceCatalog catalog, Problems problems) {
        Set<String> allowed = new LinkedHashSet<>();
        catalog.components().forEach(component -> {
            component.technologies().forEach(value -> allowed.add(value.toLowerCase(java.util.Locale.ROOT)));
            component.buildTools().forEach(value -> allowed.add(value.toLowerCase(java.util.Locale.ROOT)));
            component.testFrameworks().forEach(value -> allowed.add(value.toLowerCase(java.util.Locale.ROOT)));
        });
        Map<String, Pattern> signals = Map.of(
                "java", Pattern.compile("(?i)(?<![a-z0-9])(java|jdk|spring|maven|gradle|junit|testng)(?![a-z0-9])"),
                "node", Pattern.compile("(?i)(?<![a-z0-9])(node(?:\\.js)?|javascript|typescript|npm|pnpm|yarn|vue|react|vite|vitest)(?![a-z0-9])"),
                "python", Pattern.compile("(?i)(?<![a-z0-9])(python(?:3)?|pytest|unittest|django|flask|fastapi)(?![a-z0-9])"),
                "go", Pattern.compile("(?i)(?<![a-z0-9])(go|golang)(?![a-z0-9])"),
                "rust", Pattern.compile("(?i)(?<![a-z0-9])(rust|cargo)(?![a-z0-9])"),
                "unsupported", Pattern.compile("(?i)(?<![a-z0-9])(ruby|rails|php|laravel|dotnet|c#|csharp|swift)(?![a-z0-9])"));
        for (Map.Entry<String, Pattern> signal : signals.entrySet()) {
            String technology = signal.getKey();
            if (signal.getValue().matcher(markdown).find() && ("unsupported".equals(technology)
                    || !technologyAllowed(technology, allowed))) {
                problems.add(problem("PROJECT_CONTEXT_TECHNOLOGY_UNVERIFIED", "/legacy",
                        "Legacy 项目上下文包含冻结证据未证明的技术", allowed.stream().sorted().toList()));
            }
        }
    }

    private static boolean technologyAllowed(String technology, Set<String> allowed) {
        if (allowed.contains(technology)) return true;
        return switch (technology) {
            case "java" -> allowed.stream().anyMatch(Set.of("maven", "gradle", "junit", "testng")::contains);
            case "node" -> allowed.stream().anyMatch(Set.of("npm", "pnpm", "yarn", "vitest")::contains);
            case "python" -> allowed.stream().anyMatch(Set.of("pytest", "unittest")::contains);
            case "go" -> allowed.contains("go-test");
            case "rust" -> allowed.contains("cargo-test");
            default -> false;
        };
    }

    private static boolean invalidFacts(List<String> values) {
        return values == null || values.stream().anyMatch(value -> blank(value) || bytes(value) > 256
                || value.chars().anyMatch(Character::isISOControl));
    }

    private List<String> select(List<String> requested, List<String> allowed, String pointer, String code,
                                Problems problems) {
        if (requested == null) {
            problems.add(problem(code, pointer, "候选引用集合不能为空", allowed));
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (int index = 0; index < requested.size(); index++) {
            String value = requested.get(index);
            if (blank(value) || !allowed.contains(value) || !unique.add(value)) {
                problems.add(problem(code, pointer + "/" + index,
                        "候选只能引用冻结证据目录中的唯一值；当前元素缺失、未知或重复", allowed));
            }
        }
        return allowed.stream().filter(unique::contains).toList();
    }

    private String render(Candidate candidate, EvidenceCatalog catalog) {
        Map<String, ComponentEvidence> components = index(catalog.components(), ComponentEvidence::key);
        Map<String, CommandEvidence> commands = index(catalog.commands(), CommandEvidence::id);
        Map<String, PathEvidence> paths = index(catalog.paths(), PathEvidence::id);
        StringBuilder out = new StringBuilder("## 技术栈与模块\n");
        for (String key : candidate.componentKeys()) {
            ComponentEvidence component = components.get(key);
            out.append("- 组件 ").append(code(component.relativeRoot())).append("：技术 ")
                    .append(component.technologies().isEmpty() ? "未识别" : component.technologies().stream()
                            .map(DeterministicProjectConventionCompilation::displayTechnology)
                            .collect(java.util.stream.Collectors.joining("、")))
                    .append("；构建 ").append(component.buildTools().isEmpty() ? "未识别" : String.join("、", component.buildTools()))
                    .append("；测试 ").append(component.testFrameworks().isEmpty() ? "未识别" : String.join("、", component.testFrameworks()))
                    .append("。\n");
        }
        if (candidate.componentKeys().isEmpty()) out.append("- 未识别到可验证的软件技术栈。\n");
        out.append("\n## 构建与测试\n");
        for (String id : candidate.commandIds()) {
            CommandEvidence command = commands.get(id);
            out.append("- 在 ").append(code(components.get(command.componentKey()).relativeRoot()))
                    .append(" 执行 ").append(code(String.join(" ", command.argv()))).append("。\n");
        }
        if (candidate.commandIds().isEmpty()) out.append("- 未识别到冻结证据支持的构建或测试命令。\n");
        out.append("\n## 目录与边界\n");
        for (String id : candidate.pathIds()) {
            PathEvidence path = paths.get(id);
            out.append("- ").append(path.kind() == PathKind.MANIFEST ? "构建清单 " : "组件目录 ")
                    .append(code(path.path())).append(" 已由冻结仓库证据确认。\n");
        }
        if (candidate.pathIds().isEmpty()) out.append("- 未选择额外的冻结仓库路径事实。\n");
        return out.toString();
    }

    private static <T> Map<String, T> index(List<T> values, java.util.function.Function<T, String> key) {
        LinkedHashMap<String, T> result = new LinkedHashMap<>();
        values.forEach(value -> result.put(key.apply(value), value));
        return result;
    }

    private Result rejected(List<Problem> problems) {
        return new Result(null, null, null, null, null, null, null, List.copyOf(problems));
    }

    private static Problem problem(String code, String pointer, String detail, List<String> allowed) {
        return new Problem(code, pointer, detail, allowed, MECHANICAL);
    }

    private static Problem securityProblem(String code, String pointer, String detail) {
        return new Problem(code, pointer, detail, List.of(), SECURITY);
    }

    private static String code(String value) { return "`" + value.replace("`", "``") + "`"; }
    private static String displayTechnology(String value) {
        return switch (value) {
            case "java" -> "Java";
            case "node" -> "Node.js";
            case "python" -> "Python";
            case "go" -> "Go";
            case "rust" -> "Rust";
            default -> value;
        };
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static int bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class Problems {
        private final List<Problem> values = new ArrayList<>();
        void add(Problem problem) { if (values.size() < 16) values.add(problem); }
        boolean empty() { return values.isEmpty(); }
        List<Problem> values() { return List.copyOf(values); }
    }
}
