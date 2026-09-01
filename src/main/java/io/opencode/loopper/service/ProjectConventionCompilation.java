package io.opencode.loopper.service;

import java.util.List;
import java.util.Objects;

/** Single deterministic authority shared by Legacy and MCP PROJECT_CONVENTION_V1 entries. */
public interface ProjectConventionCompilation {
    String CONTRACT_VERSION = "PROJECT_CONVENTION_V1";

    Result compileCandidate(Input input, String candidateJson);

    Result compileLegacy(Input input, String markdown);

    record Input(String sourceContent, EvidenceCatalog evidenceCatalog) {
        public Input {
            sourceContent = sourceContent == null ? "" : sourceContent;
            Objects.requireNonNull(evidenceCatalog, "evidenceCatalog");
        }
    }

    /** Immutable server-owned repository facts. Candidate values may only reference these IDs. */
    record EvidenceCatalog(String stackFingerprint, List<ComponentEvidence> components,
                           List<CommandEvidence> commands, List<PathEvidence> paths) {
        public EvidenceCatalog {
            components = copy(components);
            commands = copy(commands);
            paths = copy(paths);
        }
    }

    record ComponentEvidence(String key, String relativeRoot, List<String> technologies,
                             List<String> buildTools, List<String> testFrameworks) {
        public ComponentEvidence {
            technologies = copy(technologies);
            buildTools = copy(buildTools);
            testFrameworks = copy(testFrameworks);
        }
    }

    record CommandEvidence(String id, String componentKey, List<String> argv) {
        public CommandEvidence { argv = copy(argv); }
    }

    enum PathKind { COMPONENT_ROOT, MANIFEST }

    record PathEvidence(String id, String componentKey, String path, PathKind kind) { }

    /** Complete model-owned replacement. Commands and paths are server-owned evidence references. */
    record Candidate(String contractVersion, List<String> componentKeys,
                     List<String> commandIds, List<String> pathIds) {
        public Candidate {
            componentKeys = copy(componentKeys);
            commandIds = copy(commandIds);
            pathIds = copy(pathIds);
        }
    }

    enum ProblemClass { MECHANICAL, SECURITY }

    record Problem(String code, String pointer, String staticDetail, List<String> allowedValues,
                   ProblemClass problemClass) {
        public Problem {
            allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
            Objects.requireNonNull(problemClass, "problemClass");
        }

        MachineCandidateSubmission.Problem submissionProblem() {
            return new MachineCandidateSubmission.Problem(code, pointer, staticDetail, allowedValues);
        }
    }

    record Result(Candidate candidate, String canonicalCandidateJson, String projectContextMarkdown,
                  String proposedContent, String contentSha256, String sourceSha256,
                  String canonicalResultSha256, List<Problem> problems) {
        public Result { problems = problems == null ? List.of() : List.copyOf(problems); }
        public boolean accepted() { return problems.isEmpty(); }
        public boolean retryable() {
            return !accepted() && problems.stream()
                    .allMatch(problem -> problem.problemClass() == ProblemClass.MECHANICAL);
        }
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
