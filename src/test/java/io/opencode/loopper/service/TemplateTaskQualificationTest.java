package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.template.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/** Explicit opt-in real Provider qualification. Never runs in the regular gate or reuses an accepted analysis cache. */
@EnabledIfSystemProperty(named = "template.qualification", matches = "true")
@SpringBootTest(classes = LoopperApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"loopper.opencode.mode=auto", "loopper.scheduling.enabled=false", "loopper.startup-recovery.enabled=false"})
class TemplateTaskQualificationTest {
    private static Path directory() { return Path.of(System.getProperty("template.qualification.dir")); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("loopper.data-dir", () -> directory().resolve("runtime").toString());
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + directory().resolve("qualification.db") + "?foreign_keys=on&busy_timeout=5000&journal_mode=WAL");
        registry.add("loopper.opencode.model", () -> System.getProperty("template.qualification.model"));
        registry.add("loopper.attempt-timeout", () -> "60m");
        registry.add("loopper.max-duration", () -> "120m");
    }
    @Autowired TemplateTaskService admission;
    @Autowired TemplateTaskStateService states;
    @Autowired TemplateTaskCoordinator driver;
    @Autowired TemplateRunEvidenceService evidence;
    @Autowired TemplateTaskMapper templates;
    @Autowired LoopperMapper mapper;
    @Autowired TaskService tasks;
    @Autowired ProjectService projects;
    @Autowired GitEvidenceProcess git;
    @Autowired OpenCodeRuntimeManager runtime;
    @Autowired OpenCodeClient client;
    @Autowired ObjectMapper json;
    @Autowired UsageInsightsService usage;

    @Test void fiveIndependentRunsOfEachBuiltinTemplate() throws Exception {
        assertThat(client).isInstanceOf(HttpOpenCodeClient.class);
        var actual = runtime.startAndCheck();
        assertThat(actual.managed()).isTrue();
        assertThat(actual.status()).isEqualTo("AVAILABLE");
        var results = new ArrayList<Map<String, Object>>();
        for (var definition : TemplateTaskDefinition.values()) {
            for (int index = 1; index <= 5; index++) {
                var result = run(definition, index);
                results.add(result);
                Files.writeString(directory().resolve("qualification.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "model", System.getProperty("template.qualification.model"), "templateVersion", TemplateTaskDefinition.VERSION,
                        "scoringVersion", ContributionScore.VERSION, "cacheBypassed", true, "runs", results)));
                assertThat(result.get("state")).as(definition + " run " + index + ": " + result.get("waitingReason")).isEqualTo("COMPLETED");
                assertThat(result.get("qualityPassed")).as("Clean fixture must have no false positives; injected arithmetic defect must be found").isEqualTo(true);
            }
        }
    }

    private Map<String, Object> run(TemplateTaskDefinition definition, int index) throws Exception {
        Path source = Files.createDirectories(directory().resolve("fixtures").resolve(definition.name() + "-" + index));
        git.read(source, "init", "-b", "main");
        Files.writeString(source.resolve("calculator.py"), "def total(values):\n    return sum(values)" + (index % 2 == 0 ? " + 1" : "") + "\n\ndef average(values):\n    return sum(values) / len(values)\n");
        commit(source, "Alice", "alice@example.test", "Implement total and average for numeric lists");
        Files.writeString(source.resolve("test_calculator.py"), "from calculator import total, average\n\ndef test_total():\n    assert total([1, 2, 3]) == 6\n\ndef test_average():\n    assert average([2, 4]) == 3\n");
        Files.writeString(source.resolve("README.md"), "# Calculator " + index + "\nFunctions accept numeric lists. Empty averages are not currently supported.\n");
        commit(source, "Bob", "bob@example.test", "Add examples and source tests; tests not run in this fixture");
        String sourceStatus = git.read(source, "status", "--porcelain=v1"), head = git.read(source, "rev-parse", "HEAD");
        String project = projects.create("资格项目 " + index, source.toString(), "隔离真实模型资格验收").id();
        String today = LocalDate.now(TemplateDateRange.ZONE).toString();
        var task = admission.create(new TemplateTaskService.Request(UUID.randomUUID().toString(), definition.name(), "1", project,
                "local:refs/heads/main", today, today, StoryBindingConfiguration.disabled()), true);
        states.start(task.id(), evidence.contract(task.id()));
        Instant began = Instant.now();
        String previous = "";
        while (Duration.between(began, Instant.now()).toMinutes() < 120) {
            var current = states.task(task.id());
            String label = current.state() + "/" + templates.findRun(task.id()).orElseThrow().repairRound();
            if (!label.equals(previous)) { System.out.println("TEMPLATE_QUALIFICATION " + definition + " " + index + " " + label); previous = label; }
            if (io.opencode.loopper.domain.TaskState.valueOf(current.state()).terminal()) break;
            if (Files.exists(directory().resolve("stop-requested"))) { tasks.cancel(task.id()); break; }
            if (current.state().equals("WAITING_INPUT")) {
                driver.executeCheckpoint(task.id());
                if (states.task(task.id()).state().equals("WAITING_INPUT")) break;
                continue;
            }
            driver.executeCheckpoint(task.id());
            if (states.task(task.id()).state().equals("JUDGING")) tasks.pollJudges(task.id());
            Thread.sleep(250);
        }
        var current = states.task(task.id());
        var run = templates.findRun(task.id()).orElseThrow();
        var result = new LinkedHashMap<String, Object>();
        result.put("template", definition.name()); result.put("case", index); result.put("taskId", task.id());
        result.put("state", current.state()); result.put("repairRound", run.repairRound());
        result.put("waitingReason", TaskWaitingInputPolicy.reasonCode(current, mapper));
        result.put("elapsedSeconds", Duration.between(began, Instant.now()).toSeconds());
        result.put("sessions", mapper.listSessions(task.id()).size());
        result.put("judges", mapper.listJudgeRuns(task.id()).stream().map(row -> Map.of("role", row.role(), "state", row.state(), "verdict", row.verdict() == null ? "" : row.verdict(), "reason", row.reason() == null ? "" : row.reason())).toList());
        result.put("usage", usage.usage(task.id()));
        var analysisArtifact = mapper.listTaskArtifacts(task.id()).stream().filter(row -> row.kind().equals("TEMPLATE_ANALYSIS")).reduce((left, right) -> right).orElse(null);
        boolean qualityPassed = false;
        if (analysisArtifact != null) {
            var accepted = json.readValue(analysisArtifact.content(), TemplateAnalysis.Accepted.class);
            long findingCount = accepted.reviews().stream().mapToLong(review -> review.findings().size()).sum();
            var units = TemplateAnalysisPartitioner.units(evidence.read(run));
            var calculatorUnits = units.stream().filter(unit -> unit.path().equals("calculator.py")).map(TemplateAnalysis.Unit::id).collect(java.util.stream.Collectors.toSet());
            boolean arithmeticBug = accepted.reviews().stream().filter(review -> calculatorUnits.contains(review.unitId()))
                    .flatMap(review -> review.findings().stream()).anyMatch(finding -> finding.side() == TemplateAnalysis.Side.AFTER && finding.line() == 2);
            // The correct test is evidence of the implementation bug, never another defect or another author's fault.
            qualityPassed = index % 2 == 0 ? arithmeticBug && findingCount == 1 : findingCount == 0;
            if (definition == TemplateTaskDefinition.CONTRIBUTION_REPORT) {
                var alice = accepted.contributors().stream().filter(person -> person.identity().equals(TemplateGitEvidenceCollector.hash("alice@example.test"))).findFirst().orElseThrow();
                var bob = accepted.contributors().stream().filter(person -> person.identity().equals(TemplateGitEvidenceCollector.hash("bob@example.test"))).findFirst().orElseThrow();
                boolean attributionPassed = bob.quality().level() > 0 && (index % 2 != 0 || alice.quality().level() <= 1);
                result.put("attributionPassed", attributionPassed);
                qualityPassed = qualityPassed && attributionPassed;
            }
            result.put("findingCount", findingCount);
        }
        result.put("qualityPassed", qualityPassed);
        result.put("sourcePreserved", sourceStatus.equals(git.read(source, "status", "--porcelain=v1")) && head.equals(git.read(source, "rev-parse", "HEAD")));
        assertThat(result.get("sourcePreserved")).isEqualTo(true);
        if (!current.state().equals("COMPLETED")) tasks.cancel(task.id());
        return result;
    }
    private void commit(Path source, String name, String email, String message) {
        git.read(source, "add", ".");
        git.read(source, "-c", "user.name=" + name, "-c", "user.email=" + email, "commit", "-m", message);
    }
}
