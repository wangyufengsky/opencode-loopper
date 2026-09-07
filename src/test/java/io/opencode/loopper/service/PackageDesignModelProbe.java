package io.opencode.loopper.service;

import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.TestPolicy;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.runtime.InternalMcpContractCatalog;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Isolated real-model qualification adapter; invokes the production compiler, never a writable workflow. */
public final class PackageDesignModelProbe {
    public static void main(String[] args) throws Exception {
        ObjectMapper json = new ObjectMapper();
        if (args.length == 1 && args[0].equals("schema")) {
            System.out.println(json.writeValueAsString(InternalMcpContractCatalog.inputSchema(MachineCandidateKind.PACKAGE_DESIGN_V1)));
            return;
        }
        var workPackage = new DesignWorkPackageRow("package-row", "designer", "requirement", "decomposition",
                "WP-1", 0, "事件分发", "实现事件分发", "[]", "[]", "[]", "[]", "[]", "[]",
                "DESIGNING", null, null, null, 1, 0, 0, null, null, null, null, null, 0, null, null,
                "now", "now", 0);
        var input = new PackageDesignCompilation.Input(workPackage, Files.readString(Path.of(args[0])),
                new WorkPackageRoleService.View("software-java", "2026-08-dynamic-v7",
                        ExecutionStrategy.OPEN_CODE_IMPLEMENTATION, TestPolicy.REQUIRED, List.of("java")),
                List.of("src/test/java/example/EventBusTest.java"), List.of(), List.of("EventBusTest"), 6, true);
        var compiler = new DeterministicPackageDesignCompilation(json);
        try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
            for (String line; (line = reader.readLine()) != null;) {
                try {
                    var result = compiler.compileCandidate(input, line);
                    var problems = CandidateDiagnosticEnricher.enrich(json, line,
                            result.problems().stream().map(PackageDesignCompilation.Problem::submissionProblem).toList());
                    var bounded = CandidateDiagnosticEnricher.bound(problems);
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("outcome", result.outcome().name());
                    response.put("retryable", result.retryable());
                    response.put("diagnosticVersion", "CANDIDATE_DIAGNOSTIC_V2");
                    response.put("diagnosticsComplete", bounded.complete());
                    response.put("problems", bounded.problems());
                    response.put("action", result.accepted() ? "STOP_ACCEPTED"
                            : result.retryable() ? "FIX_AND_RESUBMIT" : "STOP_AND_WAIT_FOR_INPUT");
                    System.out.println(json.writeValueAsString(response));
                } catch (RuntimeException failure) {
                    System.out.println(json.writeValueAsString(Map.of("outcome", "INTERNAL_ERROR",
                            "retryable", false, "exception", failure.getClass().getSimpleName())));
                }
            }
        }
    }
}
