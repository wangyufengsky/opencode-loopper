package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.*;

import io.opencode.loopper.domain.ImplementationKind;
import java.util.LinkedHashSet;
import java.util.List;

/** Selects executable Stage paths while preserving which local facts can prove mutation ownership. */
final class DesignerAcceptanceStagePathPlanner {
    private final DesignerAcceptancePathPolicy pathPolicy = new DesignerAcceptancePathPolicy();

    Selection select(Catalog catalog, List<Integer> materialFactIndexes, List<String> scopeIn,
                     WorkPackageRoleService.View role) {
        boolean mutationContract = CONTRACT_VERSION_V7.equals(catalog.contractVersion());
        List<String> local = mutationContract ? pathPolicy.positivePaths(catalog, materialFactIndexes)
                : pathPolicy.paths(catalog, materialFactIndexes);
        List<String> justified = mutationContract ? pathPolicy.precisePositivePaths(catalog, materialFactIndexes)
                : pathPolicy.precisePaths(catalog, materialFactIndexes);
        LinkedHashSet<String> paths = new LinkedHashSet<>(local);
        if (paths.isEmpty()) paths.addAll(pathPolicy.paths(scopeIn));
        if (paths.isEmpty()) {
            List<Integer> allFacts = catalog.facts().stream().map(Fact::index).toList();
            paths.addAll(mutationContract ? pathPolicy.positivePaths(catalog, allFacts)
                    : pathPolicy.paths(catalog, allFacts));
        }
        if (paths.isEmpty() && role.technologies().contains("java")) {
            paths.add("src/main/java/**");
            paths.add("src/test/java/**");
        } else if (paths.isEmpty() && role.technologies().contains("python")) {
            paths.add("**/*.py");
        } else if (paths.isEmpty()) {
            paths.add("src/**");
        }
        return new Selection(List.copyOf(paths), List.copyOf(justified));
    }

    List<String> deliverables(Catalog catalog, List<Integer> materialFactIndexes, List<String> frozen) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        LinkedHashSet<Integer> selected = new LinkedHashSet<>(materialFactIndexes);
        catalog.facts().stream().filter(fact -> selected.contains(fact.index()))
                .filter(fact -> fact.kind() == FactKind.DELIVERABLE)
                .map(Fact::title).filter(value -> !blank(value)).forEach(values::add);
        if (values.isEmpty() && frozen != null) frozen.stream().filter(value -> !blank(value)).forEach(values::add);
        if (values.isEmpty()) catalog.facts().stream().filter(fact -> fact.kind() == FactKind.DELIVERABLE)
                .map(Fact::title).filter(value -> !blank(value)).forEach(values::add);
        if (values.isEmpty()) values.add("实现与聚焦验收测试");
        return List.copyOf(values);
    }

    List<String> forbiddenPaths(Catalog catalog, List<String> scopeOut) {
        LinkedHashSet<String> values = new LinkedHashSet<>(List.of(".env", ".env.*"));
        values.addAll(pathPolicy.paths(scopeOut));
        if (CONTRACT_VERSION_V7.equals(catalog.contractVersion()) && scopeOut != null) {
            scopeOut.stream().filter(DesignerRepositoryPathSyntax::safeRootDirectory)
                    .map(String::strip).forEach(values::add);
        }
        return List.copyOf(values);
    }

    ImplementationKind implementationKind(WorkPackageRoleService.View role, List<String> allowedPaths) {
        if (!role.technologies().contains("java")) return ImplementationKind.NON_JAVA;
        boolean production = allowedPaths.stream().map(value -> value.replace('\\', '/')
                        .toLowerCase(java.util.Locale.ROOT))
                .anyMatch(value -> value.equals("src/main/java") || value.startsWith("src/main/java/")
                        || value.endsWith("/src/main/java") || value.contains("/src/main/java/"));
        return production ? ImplementationKind.JAVA_PRODUCTION : ImplementationKind.JAVA_TEST_ONLY;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    record Selection(List<String> paths, List<String> justifiedPaths) { }
}
