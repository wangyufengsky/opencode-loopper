package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.*;

import io.opencode.loopper.domain.TestPolicy;
import io.opencode.loopper.verification.ProcessCommandPolicy;
import io.opencode.loopper.verification.TestFrameworkPolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds a closed, frozen verifier capability graph from the Role Pack and Designer facts. */
final class DesignerVerificationCapabilityRegistry {
    private static final Pattern TEST_CLASS = Pattern.compile("(?<![A-Za-z0-9_$])([A-Za-z_$][A-Za-z0-9_$]*(?:Test|Tests|Spec))(?![A-Za-z0-9_$])");
    private static final Pattern MODULE_PATH = Pattern.compile("(?<![A-Za-z0-9_.-])([A-Za-z0-9_.-]+)/src/(?:main|test)/");
    private static final Pattern PYTHON_TEST_PATH = Pattern.compile(
            "(?<![A-Za-z0-9_.-])([A-Za-z0-9_./-]*(?:test_[A-Za-z0-9_-]+|[A-Za-z0-9_-]+_test)\\.py)(?![A-Za-z0-9_.-])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NODE_TEST_PATH = Pattern.compile(
            "(?<![A-Za-z0-9_.-])([A-Za-z0-9_./-]+\\.(?:test|spec)\\.(?:[cm]?[jt]sx?))(?![A-Za-z0-9_.-])",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> EXECUTABLES = Set.of(
            "mvn", "mvnw", "gradle", "gradlew", "npm", "pytest", "py.test", "python", "python3", "py");
    private final DesignerVerificationIntentMapper intentMapper = new DesignerVerificationIntentMapper();

    CapabilityCatalog build(Catalog facts, WorkPackageRoleService.View role, String design) {
        List<Capability> result = new ArrayList<>();
        LinkedHashSet<String> issues = new LinkedHashSet<>();
        List<List<String>> commands = explicitCommands(facts, design);
        if (commands.isEmpty() && role.testPolicy() == TestPolicy.REQUIRED) {
            commands.addAll(derivedCommands(role, facts, design));
        }
        List<List<String>> focusedCommands = commands.stream().flatMap(command -> splitTargets(command).stream())
                .distinct().toList();
        List<List<String>> targetLists = focusedCommands.stream()
                .map(TestFrameworkPolicy::assess).map(TestFrameworkPolicy.Assessment::targets).toList();
        List<DesignerVerificationIntentMapper.TargetMapping> mappings = intentMapper.map(facts, targetLists, design);
        for (int index = 0; index < focusedCommands.size(); index++) {
            List<String> command = focusedCommands.get(index);
            TestFrameworkPolicy.Assessment assessment = TestFrameworkPolicy.assess(command);
            if (!assessment.recognized() || assessment.skipped() || !assessment.focused()) continue;
            DesignerVerificationIntentMapper.TargetMapping mapping = mappings.get(index);
            result.add(new Capability(result.size(), "FOCUSED_TEST",
                    assessment.framework() + " · " + String.join(", ", assessment.targets()), command,
                    mapping.coversFactIndexes(), assessment.targets(), true, mapping.mandatory(), 100));
        }
        for (Fact fact : facts.facts()) {
            if (fact.kind() != FactKind.REVIEW) continue;
            result.add(new Capability(result.size(), "JUDGE", "人工评审 · " + fact.title(), List.of(),
                    List.of(fact.index()), List.of(), false, true, 10));
        }
        List<Integer> unresolved = facts.facts().stream()
                .filter(fact -> fact.kind() == FactKind.SCENARIO)
                .filter(fact -> result.stream().noneMatch(capability -> capability.coversFactIndexes()
                        .contains(fact.index())))
                .map(Fact::index).toList();
        if (!unresolved.isEmpty()) issues.add("VERIFICATION_CAPABILITY_UNAVAILABLE:" + unresolved);
        if (role.testPolicy() == TestPolicy.REQUIRED && result.stream().noneMatch(cap -> "FOCUSED_TEST".equals(cap.kind()))) {
            issues.add("REQUIRED_FOCUSED_TEST_UNAVAILABLE");
        }
        return new CapabilityCatalog(CONTRACT_VERSION, List.copyOf(result), List.copyOf(issues));
    }

    private List<List<String>> explicitCommands(Catalog facts, String design) {
        LinkedHashMap<String, List<String>> commands = new LinkedHashMap<>();
        for (String evidence : intentMapper.positiveEvidence(facts, design)) addCommand(commands, stripMarkdown(evidence));
        return new ArrayList<>(commands.values());
    }

    private static void addCommand(Map<String, List<String>> commands, String candidate) {
        List<String> tokens = tokenize(candidate);
        if (tokens.isEmpty()) return;
        int start = executableIndex(tokens);
        if (start < 0) return;
        List<String> command = List.copyOf(tokens.subList(start, tokens.size()));
        String directError = ProcessCommandPolicy.directCommandError(command);
        if (directError != null) return;
        TestFrameworkPolicy.Assessment assessment = TestFrameworkPolicy.assess(command);
        if (!assessment.recognized() || assessment.skipped() || !assessment.focused()) return;
        commands.putIfAbsent(String.join("\u0000", command), command);
    }

    List<List<String>> derivedCommands(WorkPackageRoleService.View role, Catalog facts, String design) {
        String source = String.join("\n", intentMapper.positiveEvidence(facts, design));
        List<List<String>> commands = new ArrayList<>();
        if (role.technologies().contains("java")) {
            LinkedHashSet<String> targets = new LinkedHashSet<>();
            Matcher matcher = TEST_CLASS.matcher(source);
            while (matcher.find()) targets.add(matcher.group(1));
            Matcher moduleMatcher = MODULE_PATH.matcher(source);
            String module = moduleMatcher.find() ? moduleMatcher.group(1) : null;
            commands.addAll(targets.stream().map(target -> module == null
                    ? List.of("mvn", "-Dtest=" + target, "test")
                    : List.of("mvn", "-pl", module, "-Dtest=" + target, "test")).toList());
        }
        if (role.technologies().contains("python")) {
            LinkedHashSet<String> targets = matches(PYTHON_TEST_PATH, source);
            commands.addAll(targets.stream().map(target -> List.of("python3", "-m", "pytest", target)).toList());
        }
        if (role.technologies().contains("node")) {
            LinkedHashSet<String> targets = matches(NODE_TEST_PATH, source);
            commands.addAll(targets.stream().map(target -> List.of("npm", "test", "--", target)).toList());
        }
        return List.copyOf(commands);
    }

    private static LinkedHashSet<String> matches(Pattern pattern, String source) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }

    private static List<List<String>> splitTargets(List<String> command) {
        TestFrameworkPolicy.Assessment assessment = TestFrameworkPolicy.assess(command);
        if (!assessment.recognized() || assessment.targets().size() <= 1) return List.of(command);
        List<List<String>> result = new ArrayList<>();
        for (String target : assessment.targets()) {
            List<String> split = new ArrayList<>();
            for (String argument : command) {
                String lower = argument == null ? "" : argument.toLowerCase(Locale.ROOT).replace(" ", "");
                if (lower.startsWith("-dtest=")) split.add("-Dtest=" + target);
                else if (lower.startsWith("-dit.test=")) split.add("-Dit.test=" + target);
                else split.add(argument);
            }
            result.add(List.copyOf(split));
        }
        return List.copyOf(result);
    }

    private static int executableIndex(List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index).replace('\\', '/').toLowerCase(Locale.ROOT);
            value = value.substring(value.lastIndexOf('/') + 1).replaceAll("\\.(cmd|bat|exe)$", "");
            if (EXECUTABLES.contains(value)) return index;
        }
        return -1;
    }

    private static List<String> tokenize(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (quote != 0) {
                if (current == quote) quote = 0;
                else token.append(current);
            } else if (current == '\'' || current == '"') quote = current;
            else if (Character.isWhitespace(current)) {
                if (!token.isEmpty()) { result.add(token.toString()); token.setLength(0); }
            } else if (Set.of('|', ';', '>', '<').contains(current)) return List.of();
            else token.append(current);
        }
        if (quote != 0) return List.of();
        if (!token.isEmpty()) result.add(token.toString());
        return List.copyOf(result);
    }

    private static String stripMarkdown(String line) {
        if (line == null) return "";
        return line.replaceFirst("^\\s*[-*+]\\s+", "").replace("`", "").trim();
    }

}
