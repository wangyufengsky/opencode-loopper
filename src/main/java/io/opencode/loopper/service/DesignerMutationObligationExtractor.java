package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.*;

import io.opencode.loopper.verification.VerifierPathPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Freezes explicit, positively scoped repository mutation paths with exact source evidence. */
final class DesignerMutationObligationExtractor {
    private static final int MAX_OBLIGATIONS = 128;
    private static final int MAX_EXCERPT_LENGTH = 4_000;
    private static final Pattern PATH_TOKEN = Pattern.compile(
            "(?:[A-Za-z]:(?:[\\\\/])?|/|~/|\\.\\./|\\./|\\\\+)?"
                    + "[\\p{L}\\p{N}._@+*?\\[\\]{}$-]+"
                    + "(?:[\\\\/][\\p{L}\\p{N}._@+*?\\[\\]{}$-]+)+"
                    + "|[\\p{L}\\p{N}._@+*?\\[\\]{}$-]+\\.[A-Za-z0-9]{1,16}"
                    + "|(?<![\\p{L}\\p{N}._-])(?:"
                    + DesignerRepositoryPathSyntax.COMMON_ROOT_FILE_PATTERN
                    + "|\\.[A-Za-z0-9][A-Za-z0-9._-]*)"
                    + "(?![\\p{L}\\p{N}._-])");
    private static final Pattern EXTERNAL_PATH = Pattern.compile(
            "(?<![\\p{L}\\p{N}._@+*?\\[\\]{}$-])(?:[A-Za-z]:(?:[\\\\/])?|~[\\\\/]|\\.\\.[\\\\/]|/|\\\\+)"
                    + "[^\\s`\"'，,；;]+");
    private static final Pattern URI = Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s`\"'，,；;]+");
    private static final Pattern FILE_URI = Pattern.compile("(?i)\\bfile:(?://+)?[^\\s`\"'，,；;]+");
    private static final Pattern API_ROUTE_SYMBOL = Pattern.compile(
            "(?i)/(?:api|v\\d+)(?:/[a-z0-9._~{}:$-]+)+(?=\\s*(?:接口|端点|路由|route\\b|endpoint\\b))");
    private static final Pattern HTTP_METHOD_ROUTE_SYMBOL = Pattern.compile(
            "(?i)\\b(?:GET|POST|PUT|PATCH|DELETE|OPTIONS|HEAD)\\s+"
                    + "/(?:api|v\\d+)(?:/[a-z0-9._~{}:$-]+)+");
    private static final Pattern CLAUSE_BOUNDARY = Pattern.compile("[，,；;]|但(?:是)?|不过|(?i:\\bbut\\b)");
    private static final Pattern MARKDOWN_LIST_ITEM = Pattern.compile("^(?:[-*+]\\s+|\\d+[.)]\\s+).+");
    private static final Pattern ROOT_DIRECTORY_REFERENCE = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_@+$\\-/])([a-z][a-z0-9_@+$-]*)(?:`)?\\s*"
                    + "(?:目录|文件夹|directory|folder)(?=$|[\\s，,；;。])");
    private static final Set<String> FILE_EXTENSIONS = Set.of(
            "java", "kt", "kts", "groovy", "xml", "yml", "yaml", "json", "properties", "toml",
            "md", "txt", "js", "jsx", "ts", "tsx", "vue", "py", "rb", "go", "rs", "c", "h",
            "cc", "cpp", "hpp", "cs", "php", "sh", "ps1", "bat", "sql", "gradle", "lock", "csv",
            "env", "conf", "ini", "cfg", "proto", "graphql", "gql", "html", "css", "scss", "less",
            "svelte", "swift", "scala", "clj", "ex", "exs", "erl", "hrl", "dart");
    private final DesignerAcceptancePathPolicy pathPolicy = new DesignerAcceptancePathPolicy();
    private final DesignerRequirementMutationActionPolicy actionPolicy = new DesignerRequirementMutationActionPolicy();

    Catalog extract(Catalog base, String requirementText, List<String> scopeIn, List<String> scopeOut,
                    List<String> deliverables) {
        LinkedHashMap<String, Draft> drafts = new LinkedHashMap<>();
        List<String> issues = new ArrayList<>();
        List<String> negativePaths = new ArrayList<>();
        extractRequirement(requirementText, drafts, issues, negativePaths);
        extractDesignFacts(base, drafts, issues, negativePaths);
        extractFrozenPaths(deliverables, MutationSourceKind.DESIGN_DELIVERABLE, "DELIVERABLE", drafts);
        extractFrozenPaths(scopeIn, MutationSourceKind.DESIGN_SCOPE, "SCOPE_IN", drafts);
        List<String> excluded = scopeOut == null ? List.of() : scopeOut.stream()
                .flatMap(candidate -> repositoryRules(candidate,
                        DesignerRepositoryPathSyntax.safeRootDirectory(candidate)).stream())
                .distinct().toList();
        VerifierPathPolicy.RuleRelations relations = VerifierPathPolicy.boundedRuleRelations();
        if (drafts.values().stream().anyMatch(draft -> negativePaths.stream()
                .anyMatch(rule -> overlaps(draft, rule, relations)))) {
            addIssue(issues, "AMBIGUOUS_MUTATION_PATH_SCOPE");
        }
        if (drafts.values().stream().anyMatch(draft -> excluded.stream()
                .anyMatch(rule -> overlaps(draft, rule, relations)))) {
            addIssue(issues, "MUTATION_PATH_SCOPE_CONFLICT");
        }
        if (drafts.size() > MAX_OBLIGATIONS) {
            throw new BadRequestException("MUTATION_OBLIGATION_LIMIT_EXCEEDED",
                    "显式修改路径超过单个工作包允许的 128 项上限");
        }
        List<MutationObligation> obligations = new ArrayList<>();
        for (Draft draft : drafts.values()) {
            if (obligations.size() == MAX_OBLIGATIONS) break;
            int index = obligations.size();
            String identity = draft.operation().name() + "\n" + draft.pathKind().name()
                    + "\n" + draft.pathRule() + "\n" + draft.sourceSha256();
            obligations.add(new MutationObligation(index, "MO-%03d-%s".formatted(index + 1,
                    sha256(identity).substring(0, 12)), draft.pathRule(), draft.pathKind(), draft.operation(), draft.sourceKind(),
                    draft.sourceRef(), draft.sourceExcerpt(), draft.sourceSha256(), List.of(), List.of()));
        }
        return new Catalog(base.contractVersion(), base.workPackageId(), base.designRevision(),
                base.designSha256(), base.controlledFormat(), base.facts(), base.stageHints(),
                List.copyOf(obligations), List.copyOf(issues), base.issues());
    }

    private void extractRequirement(String requirementText, Map<String, Draft> drafts, List<String> issues,
                                    List<String> negativePaths) {
        String source = requirementText == null ? "" : requirementText.replace("\r\n", "\n");
        MutationOperation contextOperation = null;
        boolean negativeContext = false;
        boolean exampleContext = false;
        String[] lines = source.split("\n", -1);
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String excerpt = lines[lineIndex].strip();
            if (excerpt.isEmpty()) {
                contextOperation = null;
                negativeContext = false;
                exampleContext = false;
                continue;
            }
            boolean listItem = MARKDOWN_LIST_ITEM.matcher(excerpt).matches();
            boolean foundPath = false;
            boolean declaredContext = false;
            for (String rawClause : CLAUSE_BOUNDARY.split(excerpt, -1)) {
                String clause = rawClause.strip();
                if (clause.isEmpty()) continue;
                List<String> tokens = pathTokens(clause);
                boolean negative = DesignerMutationPolarity.negative(clause);
                boolean example = DesignerMutationPolarity.example(clause);
                MutationOperation explicitOperation = actionPolicy.operation(clause, tokens);
                if (tokens.isEmpty()) {
                    if (!negative && !example && explicitOperation != null && externalMention(clause)) {
                        throw externalPath();
                    }
                    if (example) {
                        contextOperation = null;
                        negativeContext = false;
                        exampleContext = true;
                        declaredContext = true;
                    } else if (negative) {
                        contextOperation = null;
                        negativeContext = true;
                        exampleContext = false;
                        declaredContext = true;
                    } else if (explicitOperation != null) {
                        contextOperation = explicitOperation;
                        negativeContext = false;
                        exampleContext = false;
                        declaredContext = true;
                    }
                    continue;
                }
                foundPath = true;
                if (ambiguousMixedScope(clause, tokens)
                        || actionPolicy.hasMultipleOperations(clause, tokens)) {
                    addIssue(issues, "AMBIGUOUS_MUTATION_PATH_SCOPE");
                    continue;
                }
                if (example || (listItem && exampleContext)) continue;
                if (negative || (listItem && negativeContext)) {
                    negativePaths.addAll(projectRules(tokens));
                    continue;
                }
                MutationOperation active = explicitOperation != null
                        ? explicitOperation : listItem ? contextOperation : null;
                if (active == null) {
                    if (tokens.stream().anyMatch(DesignerMutationObligationExtractor::external)
                            || externalMention(clause)) throw externalPath();
                    List<String> unclassifiedPaths = tokens.stream()
                            .filter(token -> DesignerRepositoryPathSyntax.strongUnclassifiedPath(token, clause))
                            .toList();
                    if (!projectRules(unclassifiedPaths).isEmpty()) {
                        addIssue(issues, "AMBIGUOUS_MUTATION_PATH_SCOPE");
                    }
                    continue;
                }
                if (externalMention(clause)) throw externalPath();
                List<String> local = projectRules(tokens);
                if (local.isEmpty()) continue;
                addRequirementPaths(drafts, local, active,
                        "REQUIREMENT:L%03d".formatted(lineIndex + 1), clause);
            }
            if ((foundPath && !listItem) || (!foundPath && !declaredContext)) {
                contextOperation = null;
                negativeContext = false;
                exampleContext = false;
            }
        }
    }

    private List<String> projectRules(List<String> tokens) {
        return tokens.stream().filter(token -> plausiblePath(token)
                        || DesignerRepositoryPathSyntax.safeRootDirectory(token))
                .filter(token -> !external(token)).map(this::repositoryRules)
                .filter(paths -> !paths.isEmpty()).map(List::getFirst).distinct().toList();
    }

    private static void addRequirementPaths(Map<String, Draft> drafts, List<String> paths,
                                            MutationOperation operation, String ref, String excerpt) {
        if (paths.isEmpty()) return;
        if (operation == MutationOperation.MOVE_SOURCE) {
            add(drafts, paths.getFirst(), DesignerRepositoryPathSyntax.mutationPathKind(
                            paths.getFirst(), excerpt, MutationSourceKind.REQUIREMENT),
                    MutationOperation.MOVE_SOURCE,
                    MutationSourceKind.REQUIREMENT, ref, excerpt);
            paths.stream().skip(1).forEach(path -> add(drafts, path,
                    DesignerRepositoryPathSyntax.mutationPathKind(
                            path, excerpt, MutationSourceKind.REQUIREMENT), MutationOperation.MOVE_DESTINATION,
                    MutationSourceKind.REQUIREMENT, ref, excerpt));
            return;
        }
        paths.forEach(path -> add(drafts, path, DesignerRepositoryPathSyntax.mutationPathKind(
                        path, excerpt, MutationSourceKind.REQUIREMENT), operation,
                MutationSourceKind.REQUIREMENT, ref, excerpt));
    }

    private void extractDesignFacts(Catalog base, Map<String, Draft> drafts, List<String> issues,
                                    List<String> negativePaths) {
        for (Fact fact : base.facts()) {
            if (fact.kind() != FactKind.DELIVERABLE && fact.kind() != FactKind.SCOPE) continue;
            if (pathPolicy.routeSymbol(fact)) continue;
            DesignerAcceptancePathPolicy.MutationFactSemantics semantics = pathPolicy.mutationSemantics(fact);
            boolean explicitDirectory = DesignerRepositoryPathSyntax.directorySource(fact.detail());
            List<String> factRules = plausiblePath(fact.title()) || explicitDirectory
                    ? repositoryRules(fact.title(), explicitDirectory) : List.of();
            boolean repositoryRule = !factRules.isEmpty();
            boolean pathBearing = repositoryRule || external(fact.title())
                    || externalMention(fact.title());
            if (semantics == DesignerAcceptancePathPolicy.MutationFactSemantics.IRRELEVANT) {
                if (pathBearing) addIssue(issues, "AMBIGUOUS_MUTATION_PATH_SCOPE");
                continue;
            }
            if (semantics == DesignerAcceptancePathPolicy.MutationFactSemantics.NEGATIVE) {
                if (!external(fact.title()) && !externalMention(fact.title())) {
                    negativePaths.addAll(factRules);
                }
                continue;
            }
            if (external(fact.title()) || externalMention(fact.title())) throw externalPath();
            if (!repositoryRule) continue;
            List<String> rules = factRules;
            if (rules.isEmpty()) {
                addIssue(issues, "AMBIGUOUS_MUTATION_PATH_SCOPE");
                continue;
            }
            MutationSourceKind sourceKind = fact.kind() == FactKind.SCOPE
                    ? MutationSourceKind.DESIGN_SCOPE : MutationSourceKind.DESIGN_DELIVERABLE;
            MutationOperation operation = switch (semantics) {
                case DELETE -> MutationOperation.DELETE_REQUEST;
                case MOVE -> MutationOperation.MOVE_SOURCE;
                default -> MutationOperation.WRITE;
            };
            String path = normalized(rules.getFirst());
            MutationPathKind kind = fact.kind() == FactKind.SCOPE
                    ? MutationPathKind.PATH_RULE
                    : DesignerRepositoryPathSyntax.mutationPathKind(path, fact.detail(), sourceKind);
            add(drafts, path, kind, operation, sourceKind,
                    fact.sourceRef(), fact.sourceExcerpt(), fact.sourceSha256());
        }
    }

    private void extractFrozenPaths(List<String> candidates, MutationSourceKind sourceKind, String refPrefix,
                                    Map<String, Draft> drafts) {
        if (candidates == null) return;
        for (int index = 0; index < candidates.size(); index++) {
            String candidate = candidates.get(index);
            if (external(candidate) || externalMention(candidate)) {
                throw externalPath();
            }
            boolean frozenRootDirectory = sourceKind == MutationSourceKind.DESIGN_SCOPE
                    ? DesignerRepositoryPathSyntax.safeRootDirectory(candidate)
                    : DesignerRepositoryPathSyntax.knownFrozenRootDirectory(candidate);
            if (!plausiblePath(candidate) && !frozenRootDirectory) continue;
            List<String> paths = repositoryRules(candidate, frozenRootDirectory);
            if (paths.isEmpty()) continue;
            String excerpt = candidate == null ? "" : candidate.strip();
            String path = normalized(paths.getFirst());
            MutationPathKind kind = DesignerRepositoryPathSyntax.mutationPathKind(path, candidate, sourceKind);
            add(drafts, path, kind, MutationOperation.WRITE, sourceKind,
                    refPrefix + ":" + index, excerpt);
        }
    }

    private static List<String> pathTokens(String line) {
        List<String> result = new ArrayList<>();
        String withoutRoutes = withoutRouteSymbols(line);
        Matcher matcher = PATH_TOKEN.matcher(URI.matcher(withoutRoutes).replaceAll(" "));
        while (matcher.find()) {
            String value = matcher.group();
            if (!result.contains(value)) result.add(value);
        }
        Matcher directory = ROOT_DIRECTORY_REFERENCE.matcher(withoutRoutes);
        while (directory.find()) {
            String value = directory.group(1);
            if (!result.contains(value)) result.add(value);
        }
        return List.copyOf(result);
    }

    private List<String> repositoryRules(String candidate) {
        return repositoryRules(candidate, DesignerRepositoryPathSyntax.safeRootDirectory(candidate));
    }

    private List<String> repositoryRules(String candidate, boolean allowRootDirectory) {
        List<String> paths = pathPolicy.paths(List.of(candidate));
        if (!paths.isEmpty()) return paths.stream().map(DesignerMutationObligationExtractor::normalized).toList();
        return allowRootDirectory && DesignerRepositoryPathSyntax.safeRootDirectory(candidate)
                ? List.of(normalized(candidate)) : List.of();
    }

    private static void add(Map<String, Draft> drafts, String path, MutationPathKind pathKind,
                            MutationOperation operation,
                            MutationSourceKind sourceKind, String sourceRef, String sourceExcerpt) {
        add(drafts, path, pathKind, operation, sourceKind, sourceRef, sourceExcerpt, null);
    }

    private static void add(Map<String, Draft> drafts, String path, MutationPathKind pathKind,
                            MutationOperation operation,
                            MutationSourceKind sourceKind, String sourceRef, String sourceExcerpt,
                            String sourceSha256) {
        String raw = sourceExcerpt == null ? "" : sourceExcerpt.strip();
        String evidence = raw.isBlank() ? path : raw;
        String excerpt = bounded(evidence);
        String key = operation.name() + "\n" + pathKind.name() + "\n" + path;
        Draft incoming = new Draft(path, pathKind, operation, sourceKind,
                sourceRef == null || sourceRef.isBlank() ? "UNKNOWN" : sourceRef,
                excerpt, sourceSha256 == null || sourceSha256.isBlank() ? sha256(evidence) : sourceSha256);
        drafts.putIfAbsent(key, incoming);
    }

    private static boolean external(String candidate) {
        if (candidate == null) return false;
        String value = candidate.strip().replace('\\', '/');
        return value.toLowerCase(Locale.ROOT).startsWith("file:")
                || value.startsWith("/") || value.startsWith("~/") || value.equals("..")
                || value.startsWith("../") || value.contains("/../") || value.matches("^[A-Za-z]:.*")
                || value.startsWith("//");
    }

    private static boolean externalMention(String value) {
        if (value == null) return false;
        if (FILE_URI.matcher(value).find()) return true;
        String withoutRoutes = withoutRouteSymbols(value);
        return EXTERNAL_PATH.matcher(URI.matcher(withoutRoutes).replaceAll(" ")).find();
    }

    private static String withoutRouteSymbols(String value) {
        String withoutMethods = HTTP_METHOD_ROUTE_SYMBOL.matcher(value == null ? "" : value).replaceAll(" ");
        return API_ROUTE_SYMBOL.matcher(withoutMethods).replaceAll(" ");
    }

    private static BadRequestException externalPath() {
        return new BadRequestException("PROJECT_ROOT_EXTERNAL_PATH",
                "需求包含登记项目根外路径，当前单任务设计不能授权");
    }

    private static boolean containsGlob(String value) {
        return value.indexOf('*') >= 0 || value.indexOf('?') >= 0
                || value.indexOf('[') >= 0 || value.indexOf('{') >= 0;
    }

    private static boolean overlaps(Draft draft, String rule, VerifierPathPolicy.RuleRelations relations) {
        try {
            return draft.pathKind() == MutationPathKind.EXACT_PATH
                    ? relations.ruleMatchesExactPath(draft.pathRule(), rule)
                    : relations.rulesMayOverlap(draft.pathRule(), rule);
        } catch (RuntimeException invalidOrExhausted) {
            return true;
        }
    }

    private boolean ambiguousMixedScope(String clause, List<String> tokens) {
        boolean negative = DesignerMutationPolarity.negative(clause);
        boolean example = DesignerMutationPolarity.example(clause);
        if (example || !negative) return false;
        String remaining = DesignerMutationPolarity.removeNegativeAndExampleMarkers(clause);
        return actionPolicy.hasWriteAction(remaining);
    }

    private static boolean plausiblePath(String token) {
        if (token == null || token.isBlank()) return false;
        String value = normalized(token);
        if (value.contains("/") || containsGlob(value)) return true;
        if (value.matches("\\.[A-Za-z0-9][A-Za-z0-9._-]*")
                || DesignerRepositoryPathSyntax.commonRootFile(value)) return true;
        int dot = value.lastIndexOf('.');
        if (dot < 0 || dot == value.length() - 1) return false;
        return FILE_EXTENSIONS.contains(value.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String normalized(String value) {
        return value.strip().replace('\\', '/').replaceFirst("^\\./+", "");
    }

    private static void addIssue(List<String> issues, String issue) {
        if (!issues.contains(issue)) issues.add(issue);
    }

    private static String bounded(String value) {
        return value.length() <= MAX_EXCERPT_LENGTH ? value : value.substring(0, MAX_EXCERPT_LENGTH);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Draft(String pathRule, MutationPathKind pathKind, MutationOperation operation,
                         MutationSourceKind sourceKind,
                         String sourceRef, String sourceExcerpt, String sourceSha256) { }
}
