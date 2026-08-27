package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.MutationPathKind;
import static io.opencode.loopper.service.DesignerAcceptancePlanning.MutationSourceKind;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Shared syntax for repository-root files that do not carry an extension or slash. */
final class DesignerRepositoryPathSyntax {
    static final Set<String> COMMON_ROOT_FILES = Set.of(
            "Dockerfile", "Containerfile", "Makefile", "Rakefile", "Gemfile", "Procfile", "Jenkinsfile",
            "CMakeLists.txt", "BUILD", "WORKSPACE", "LICENSE", "NOTICE", "mvnw", "gradlew");
    static final String COMMON_ROOT_FILE_PATTERN =
            "Dockerfile|Containerfile|Makefile|Rakefile|Gemfile|Procfile|Jenkinsfile|CMakeLists\\.txt|"
                    + "BUILD|WORKSPACE|LICENSE|NOTICE|mvnw|gradlew";
    private static final List<String> DIRECTORY_TERMS = List.of("目录", "文件夹", "directory", "folder");
    private static final Set<String> FROZEN_ROOT_DIRECTORIES = Set.of(
            "app", "apps", "client", "config", "configs", "docs", "frontend", "lib", "libs",
            "modules", "packages", "public", "resources", "scripts", "src", "test", "tests");

    private DesignerRepositoryPathSyntax() { }

    static boolean commonRootFile(String value) {
        return COMMON_ROOT_FILES.contains(value)
                || value.matches("\\.[A-Za-z0-9][A-Za-z0-9._-]*");
    }

    static boolean safeRootDirectory(String value) {
        return value != null && value.strip().matches("[a-z][A-Za-z0-9_@+$-]*");
    }

    static boolean knownFrozenRootDirectory(String value) {
        return value != null && FROZEN_ROOT_DIRECTORIES.contains(value.strip().toLowerCase(Locale.ROOT));
    }

    static boolean directorySource(String sourceText) {
        String normalized = sourceText == null ? "" : sourceText.toLowerCase(Locale.ROOT);
        return DIRECTORY_TERMS.stream().anyMatch(normalized::contains);
    }

    static MutationPathKind mutationPathKind(String path, String sourceText, MutationSourceKind sourceKind) {
        if (containsGlob(path) || sourceKind == MutationSourceKind.DESIGN_SCOPE) return MutationPathKind.PATH_RULE;
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        boolean extensionlessNestedPath = path.contains("/") && !lastSegment.contains(".");
        boolean frozenRootDirectory = !path.contains("/") && !commonRootFile(path)
                && (sourceKind == MutationSourceKind.DESIGN_SCOPE || knownFrozenRootDirectory(path));
        return directorySource(sourceText) || extensionlessNestedPath || frozenRootDirectory
                ? MutationPathKind.PATH_RULE : MutationPathKind.EXACT_PATH;
    }

    private static boolean containsGlob(String value) {
        return value.indexOf('*') >= 0 || value.indexOf('?') >= 0
                || value.indexOf('[') >= 0 || value.indexOf('{') >= 0;
    }
}
