package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProjectConventionCompilationTest {
    private final ProjectConventionCompilation compilation =
            new DeterministicProjectConventionCompilation(new ObjectMapper(), new ProjectConventionDocumentStore());

    @Test
    void legacyAndMcpEntriesProduceTheSameAuthoritativeConvention() {
        ProjectConventionCompilation.Input input = new ProjectConventionCompilation.Input(
                "# Human rule\n", javaEvidence());

        ProjectConventionCompilation.Result mcp = compilation.compileCandidate(input, """
                {
                  "contractVersion": "PROJECT_CONVENTION_V1",
                  "componentKeys": ["java-root"],
                  "commandIds": ["java-root:test"],
                  "pathIds": ["java-root:manifest:pom.xml"]
                }
                """);
        ProjectConventionCompilation.Result legacy = compilation.compileLegacy(input, """
                ## 技术栈与模块
                - Java 21 与 Maven。
                ## 构建与测试
                - `mvn test`
                ## 目录与边界
                - 构建清单为 `pom.xml`。
                """);

        assertThat(mcp.accepted()).isTrue();
        assertThat(legacy.accepted()).isTrue();
        assertThat(legacy.canonicalCandidateJson()).isEqualTo(mcp.canonicalCandidateJson());
        assertThat(legacy.projectContextMarkdown()).isEqualTo(mcp.projectContextMarkdown());
        assertThat(legacy.proposedContent()).isEqualTo(mcp.proposedContent())
                .startsWith("# Human rule\n\n<!-- LOOPPER:START -->")
                .contains("## 技术栈与模块", "Java", "## 构建与测试", "`mvn test`",
                        "## 目录与边界", "`pom.xml`")
                .endsWith("<!-- LOOPPER:END -->\n");
        assertThat(legacy.canonicalResultSha256()).isEqualTo(mcp.canonicalResultSha256()).hasSize(64);
    }

    @Test
    void dangerousPathInTheFrozenCatalogFailsClosedBeforeItCanBeRendered() {
        ProjectConventionCompilation.EvidenceCatalog unsafe = new ProjectConventionCompilation.EvidenceCatalog(
                "a".repeat(64), javaEvidence().components(), javaEvidence().commands(), List.of(
                new ProjectConventionCompilation.PathEvidence("unsafe", "java-root", "../secret.txt",
                        ProjectConventionCompilation.PathKind.MANIFEST)));

        ProjectConventionCompilation.Result result = compilation.compileCandidate(
                new ProjectConventionCompilation.Input("", unsafe), """
                        {"contractVersion":"PROJECT_CONVENTION_V1","componentKeys":["java-root"],
                         "commandIds":[],"pathIds":["unsafe"]}
                        """);

        assertThat(result.accepted()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("PROJECT_CONVENTION_EVIDENCE_PATH_UNSAFE");
            assertThat(problem.problemClass()).isEqualTo(ProjectConventionCompilation.ProblemClass.SECURITY);
        });
        assertThat(result.candidate()).isNull();
        assertThat(result.canonicalCandidateJson()).isNull();
        assertThat(result.projectContextMarkdown()).isNull();
        assertThat(result.proposedContent()).isNull();
        assertThat(result.canonicalResultSha256()).isNull();
    }

    @Test
    void legacyEntryRejectsEveryUnprovedBacktickCommandOrPathWithoutPartialOutput() {
        ProjectConventionCompilation.Result result = compilation.compileLegacy(
                new ProjectConventionCompilation.Input("", javaEvidence()), """
                        ## 技术栈与模块
                        - Java。
                        ## 构建与测试
                        - `make test`
                        ## 目录与边界
                        - `../private`
                        """);

        assertThat(result.accepted()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.problems()).extracting(ProjectConventionCompilation.Problem::code)
                .containsExactly("PROJECT_CONVENTION_COMMAND_UNVERIFIED",
                        "PROJECT_CONVENTION_PATH_UNSAFE");
        assertNoCompiledResult(result);
    }

    @Test
    void unknownFieldsAndUnprovedEvidenceIdsRejectTheWholeMcpCandidate() {
        ProjectConventionCompilation.Result unknown = compilation.compileCandidate(
                new ProjectConventionCompilation.Input("", javaEvidence()), """
                        {"contractVersion":"PROJECT_CONVENTION_V1","componentKeys":["java-root"],
                         "commandIds":[],"pathIds":[],"permission":"write"}
                        """);
        ProjectConventionCompilation.Result unproved = compilation.compileCandidate(
                new ProjectConventionCompilation.Input("", javaEvidence()), """
                        {"contractVersion":"PROJECT_CONVENTION_V1","componentKeys":["java-root"],
                         "commandIds":["server-does-not-know"],
                         "pathIds":["java-root:manifest:pom.xml"]}
                        """);

        assertThat(unknown.accepted()).isFalse();
        assertThat(unknown.retryable()).isFalse();
        assertThat(unknown.problems()).extracting(ProjectConventionCompilation.Problem::code)
                .containsExactly("PROJECT_CONVENTION_AUTHORITY_FIELD_FORBIDDEN");
        assertNoCompiledResult(unknown);
        assertThat(unproved.accepted()).isFalse();
        assertThat(unproved.problems()).extracting(ProjectConventionCompilation.Problem::code)
                .containsExactly("PROJECT_CONVENTION_COMMAND_UNVERIFIED");
        assertNoCompiledResult(unproved);
    }

    @Test
    void duplicateFieldsOrTrailingJsonAreNotAClosedCandidate() {
        ProjectConventionCompilation.Input input =
                new ProjectConventionCompilation.Input("", javaEvidence());
        ProjectConventionCompilation.Result duplicate = compilation.compileCandidate(input, """
                {"contractVersion":"PROJECT_CONVENTION_V1","componentKeys":["java-root"],
                 "componentKeys":[],"commandIds":[],"pathIds":[]}
                """);
        ProjectConventionCompilation.Result trailing = compilation.compileCandidate(input, """
                {"contractVersion":"PROJECT_CONVENTION_V1","componentKeys":["java-root"],
                 "commandIds":[],"pathIds":[]} {"extra":true}
                """);

        assertThat(duplicate.problems()).extracting(ProjectConventionCompilation.Problem::code)
                .containsExactly("PROJECT_CONVENTION_CANDIDATE_JSON_INVALID");
        assertThat(trailing.problems()).extracting(ProjectConventionCompilation.Problem::code)
                .containsExactly("PROJECT_CONVENTION_CANDIDATE_JSON_INVALID");
        assertNoCompiledResult(duplicate);
        assertNoCompiledResult(trailing);
    }

    @Test
    void candidateAndCatalogCollectionsAreBounded() {
        String ids = java.util.stream.IntStream.range(0, 65)
                .mapToObj(index -> "\"component-" + index + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        ProjectConventionCompilation.Result candidate = compilation.compileCandidate(
                new ProjectConventionCompilation.Input("", javaEvidence()),
                "{\"contractVersion\":\"PROJECT_CONVENTION_V1\",\"componentKeys\":[" + ids
                        + "],\"commandIds\":[],\"pathIds\":[]}");
        List<ProjectConventionCompilation.ComponentEvidence> tooManyComponents =
                java.util.stream.IntStream.range(0, 65)
                        .mapToObj(index -> new ProjectConventionCompilation.ComponentEvidence(
                                "component-" + index, "module-" + index, List.of("java"),
                                List.of("maven"), List.of("junit")))
                        .toList();
        ProjectConventionCompilation.Result catalog = compilation.compileCandidate(
                new ProjectConventionCompilation.Input("", new ProjectConventionCompilation.EvidenceCatalog(
                        "a".repeat(64), tooManyComponents, List.of(), List.of())), """
                        {"contractVersion":"PROJECT_CONVENTION_V1","componentKeys":[],
                         "commandIds":[],"pathIds":[]}
                        """);

        assertThat(candidate.problems()).extracting(ProjectConventionCompilation.Problem::code)
                .containsExactly("PROJECT_CONVENTION_CANDIDATE_SIZE_INVALID");
        assertThat(catalog.problems()).extracting(ProjectConventionCompilation.Problem::code)
                .containsExactly("PROJECT_CONVENTION_EVIDENCE_SIZE_INVALID");
        assertNoCompiledResult(candidate);
        assertNoCompiledResult(catalog);
    }

    @Test
    void unsafeArgvInTheFrozenCatalogIsANonRetryableSecurityFailure() {
        ProjectConventionCompilation.EvidenceCatalog unsafe = new ProjectConventionCompilation.EvidenceCatalog(
                "a".repeat(64), javaEvidence().components(), List.of(
                new ProjectConventionCompilation.CommandEvidence("unsafe", "java-root",
                        List.of("rm", "-rf", "/"))), javaEvidence().paths());

        ProjectConventionCompilation.Result result = compilation.compileCandidate(
                new ProjectConventionCompilation.Input("", unsafe), """
                        {"contractVersion":"PROJECT_CONVENTION_V1","componentKeys":["java-root"],
                         "commandIds":["unsafe"],"pathIds":[]}
                        """);

        assertThat(result.accepted()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("PROJECT_CONVENTION_EVIDENCE_COMMAND_UNSAFE");
            assertThat(problem.problemClass()).isEqualTo(ProjectConventionCompilation.ProblemClass.SECURITY);
            assertThat(problem.staticDetail()).doesNotContain("rm", "-rf");
        });
        assertNoCompiledResult(result);
    }

    @Test
    void safeExecutableWithUnapprovedArgumentsIsStillRejectedAsUnsafeCatalogEvidence() {
        ProjectConventionCompilation.EvidenceCatalog unsafe = new ProjectConventionCompilation.EvidenceCatalog(
                "a".repeat(64), javaEvidence().components(), List.of(
                new ProjectConventionCompilation.CommandEvidence("unsafe", "java-root",
                        List.of("mvn", "-f", "/private/pom.xml", "test"))), javaEvidence().paths());

        ProjectConventionCompilation.Result result = compilation.compileCandidate(
                new ProjectConventionCompilation.Input("", unsafe), """
                        {"contractVersion":"PROJECT_CONVENTION_V1","componentKeys":["java-root"],
                         "commandIds":["unsafe"],"pathIds":[]}
                        """);

        assertThat(result.accepted()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.problems()).extracting(ProjectConventionCompilation.Problem::code)
                .containsExactly("PROJECT_CONVENTION_EVIDENCE_COMMAND_UNSAFE");
        assertNoCompiledResult(result);
    }

    @Test
    void legacyCommandSharedByMultipleComponentsIsRejectedAsAmbiguous() {
        ProjectConventionCompilation.EvidenceCatalog ambiguous =
                new ProjectConventionCompilation.EvidenceCatalog("a".repeat(64), List.of(
                        javaEvidence().components().getFirst(),
                        new ProjectConventionCompilation.ComponentEvidence("java-api", "api",
                                List.of("java"), List.of("maven"), List.of("junit"))), List.of(
                        javaEvidence().commands().getFirst(),
                        new ProjectConventionCompilation.CommandEvidence("java-api:test", "java-api",
                                List.of("mvn", "test"))), List.of(
                        javaEvidence().paths().getLast(),
                        new ProjectConventionCompilation.PathEvidence("java-api:manifest", "java-api",
                                "api/pom.xml", ProjectConventionCompilation.PathKind.MANIFEST)));

        ProjectConventionCompilation.Result result = compilation.compileLegacy(
                new ProjectConventionCompilation.Input("", ambiguous), """
                        ## 技术栈与模块
                        - Java。
                        ## 构建与测试
                        - `mvn test`
                        ## 目录与边界
                        - `pom.xml`
                        """);

        assertThat(result.problems()).extracting(ProjectConventionCompilation.Problem::code)
                .containsExactly("PROJECT_CONVENTION_COMMAND_AMBIGUOUS");
        assertNoCompiledResult(result);
    }

    @Test
    void malformedFrozenSourceCannotProduceAPartialCandidateResult() {
        ProjectConventionCompilation.Result result = compilation.compileCandidate(
                new ProjectConventionCompilation.Input("# Human\n<!-- LOOPPER:START -->\n", javaEvidence()), """
                        {"contractVersion":"PROJECT_CONVENTION_V1","componentKeys":["java-root"],
                         "commandIds":[],"pathIds":[]}
                        """);

        assertThat(result.retryable()).isFalse();
        assertThat(result.problems()).extracting(ProjectConventionCompilation.Problem::code)
                .containsExactly("PROJECT_CONVENTION_SOURCE_INVALID");
        assertNoCompiledResult(result);
    }

    private static void assertNoCompiledResult(ProjectConventionCompilation.Result result) {
        assertThat(result.candidate()).isNull();
        assertThat(result.canonicalCandidateJson()).isNull();
        assertThat(result.projectContextMarkdown()).isNull();
        assertThat(result.proposedContent()).isNull();
        assertThat(result.canonicalResultSha256()).isNull();
    }

    private static ProjectConventionCompilation.EvidenceCatalog javaEvidence() {
        return new ProjectConventionCompilation.EvidenceCatalog("a".repeat(64), List.of(
                new ProjectConventionCompilation.ComponentEvidence("java-root", ".", List.of("java"),
                        List.of("maven"), List.of("junit"))), List.of(
                new ProjectConventionCompilation.CommandEvidence("java-root:test", "java-root",
                        List.of("mvn", "test"))), List.of(
                new ProjectConventionCompilation.PathEvidence("java-root:root", "java-root", ".",
                        ProjectConventionCompilation.PathKind.COMPONENT_ROOT),
                new ProjectConventionCompilation.PathEvidence("java-root:manifest:pom.xml", "java-root",
                        "pom.xml", ProjectConventionCompilation.PathKind.MANIFEST)));
    }
}
