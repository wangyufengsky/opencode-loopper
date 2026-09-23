package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.template.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h", "loopper.designer-monitor-delay=1h"})
class SourceUnitExecutionIntegrationTest {
    @Autowired Flyway flyway;
    @Autowired SourceTemplateService service;
    @Autowired SourceTemplateAdmission admission;
    @Autowired SourceTemplateMapper runs;
    @Autowired SourceUnitExecution development;
    @Autowired SourceTemplateCoordinator coordinator;
    @Autowired DesignerSessionService designers;
    @Autowired MachineCandidateSubmission submissions;
    @Autowired TaskService tasks;
    @Autowired SourceDevelopmentScope developmentScopes;
    @Autowired SourceDevelopmentReads sourceReads;
    @Autowired AssistMapper assist;
    @Autowired InternalMcpRuntimeAccess access;
    @Autowired LoopperMapper domain;
    @Autowired ProjectService projects;
    @Autowired LoopperProperties properties;
    @Autowired OpenCodeClient client;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path temporary;
    private SourceTemplateRunRow run;
    private SourceTemplateContract contract;
    private Path source;
    private String existingTest, business, pom;
    private String project;
    @BeforeEach void prepare() throws Exception {
        flyway.clean(); flyway.migrate(); ((FakeOpenCodeClient) client).reset();
        properties.getOpenCode().setModel("fake/test-model");
        source = Files.createDirectory(temporary.resolve("source")).toRealPath();
        Files.writeString(source.resolve("existing.txt"), "用户未提交内容");
        prepareMavenProject();
        Files.createDirectories(source.resolve("src/main/java/example")); Files.createDirectories(source.resolve("src/test/java/example"));
        business = "package example; public class EventBus { private final java.util.Map<String,Runnable> handlers=new java.util.HashMap<>(); public void register(String event,Runnable handler){handlers.put(event,handler);} public void publish(String event){handlers.getOrDefault(event,()->{}).run();} }";
        existingTest = "package example; class ExistingTest { @org.junit.jupiter.api.Test void knownEvent(){ var bus=new EventBus(); int[] count={0}; bus.register(\"known\",()->count[0]++); bus.publish(\"known\"); org.junit.jupiter.api.Assertions.assertEquals(1,count[0]); } }";
        Files.writeString(source.resolve("src/main/java/example/EventBus.java"), business);
        Files.writeString(source.resolve("src/test/java/example/ExistingTest.java"), existingTest);
        pom = Files.readString(source.resolve("pom.xml"));
        project = projects.create("源码单测全流程", source.toString(), "test").id();
    }
    private void startSourceRun() {
        run = service.create(new SourceTemplateRequests.Create(UUID.randomUUID().toString(), "UNIT_TEST_DEVELOPMENT", "1", project, "src/main", null, null, ""));
        contract = json.readValue(run.contractJson(), SourceTemplateContract.class);
        admission.start(run.id(), new SourceTemplateRequests.Command(UUID.randomUUID().toString(), run.version()));
        coordinator.advance(run.id()); run=admission.require(run.id());
        assertThat(run.state()).isEqualTo("DESIGNING");
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"false,false,false", "true,false,false", "false,true,false", "true,false,true"})
    void sourceTemplateRunsNativeTestsAndDualReviewWithoutAcceptingTaskResult(boolean gitProject, boolean failingTest, boolean alreadyCovered) throws Exception {
        if (alreadyCovered) writeBehaviorTest();
        if (gitProject) initializeGit();
        startSourceRun();
        var credentials = new InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(credentials); access.connected(credentials.generation());
        var fake = (FakeOpenCodeClient) client; fake.setManagedRuntime(credentials.generation(), credentials.serverName());
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_V2_READ_ONLY, true);
        development.advance(admission.require(run.id()), contract);
        development.advance(admission.require(run.id()), contract);
        var designer = designers.get(admission.require(run.id()).designerId());
        var revision = domain.findCurrentDesignRequirementRevision(designer.id()).orElseThrow();
        var pack = domain.listDesignWorkPackages(revision.id()).getFirst();
        var candidateRun = domain.findLatestCandidateSubmissionRunForWorkPackage(pack.id(), pack.designRevision() + 1L).orElseThrow();
        readOriginal(candidateRun.externalSessionId(), "PACKAGE_DESIGN_CANDIDATE_V2_READ_ONLY");
        var fixture = new PackageDesignV2CompilationTest(); var candidate = fixture.candidate();
        ((tools.jackson.databind.node.ObjectNode) candidate.path("sourceBindings").get(0))
                .set("sourceRefs", json.valueToTree(List.of(SourceRequirementContext.reference(runs.files(run.id()).stream().filter(f -> f.path().endsWith("EventBus.java")).findFirst().orElseThrow().ordinal()), DocumentRequirementContext.FINAL_REGRESSION)));
        var submitted = submissions.submit(new MachineCandidateSubmission.SubmitCommand(candidateRun.id(), "document-package-fixture",
                json.writeValueAsString(candidate), candidateRun.version(), MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                MachineCandidateSubmission.SubmissionSchema.ROLE_SPECIFIC_V2));
        assertThat(submitted.outcome()).as("%s", submitted.problems()).isEqualTo(MachineCandidateOutcome.ACCEPTED);
        fake.setSessionState(candidateRun.externalSessionId(), "COMPLETED");
        for (int i = 0; i < 12 && admission.require(run.id()).taskId() == null; i++) {
            designers.pollActiveHandoffs(); development.advance(admission.require(run.id()), contract);
        }
        var current = admission.require(run.id());
        assertThat(current.state()).as("%s designer=%s packages=%s messages=%s", current.waitingMessage(), designers.get(current.designerId()),
                domain.listDesignWorkPackages(revision.id()), domain.listDesignerMessages(current.designerId())).isEqualTo("EXECUTING");
        var task = domain.findTask(current.taskId()).orElseThrow();
        assertThat(task.state()).isEqualTo("PENDING_START"); assertThat(task.executionMode()).isNotEqualTo("TEMPLATE_REPORT");
        assertThat(domain.listStages(task.id())).isNotEmpty().allSatisfy(stage -> assertThat(stage.testPolicy()).isEqualTo("REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Integer.class)).isEqualTo(1);
        assertThat(Files.readString(source.resolve("existing.txt"))).isEqualTo("用户未提交内容");
        development.advance(current, contract);
        task = tasks.get(task.id());
        assertThat(task.state()).as("%s", tasks.errors(task.id())).isEqualTo("RUNNING");
        assertThat(Path.of(task.worktreePath()).toRealPath()).isEqualTo(source.toRealPath());
        // Controlled implementation fixture writes executable assertions; the formal verifier runs this native entry.
        Path test = source.resolve("src/test/java/example/EventBusTest.java"); Files.createDirectories(test.getParent());
        writeBehaviorTest();
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.JUDGE_CANDIDATE_READ_ONLY, true);
        {
            if (failingTest) Files.writeString(test, Files.readString(test).replace("calls[0] != 1", "calls[0] != 9"));
            tasks.verify(task.id());
            if (failingTest) {
                var stage = domain.listStages(task.id()).getFirst();
                assertThat(tasks.verifications(domain.latestAttempt(stage.id()).orElseThrow().id())).anySatisfy(v -> {
                    assertThat(v.state()).isEqualTo("FAIL"); assertThat(v.type()).isEqualTo("PROCESS");
                    assertThat(v.evidenceJson()).contains("existing dispatch must be preserved");
                });
                assertThat(domain.listJudgeRuns(task.id())).isEmpty();
                development.advance(admission.require(run.id()), contract);
                assertThat(admission.require(run.id()).state()).isNotEqualTo("COMPLETED");
                assertThat(runs.coverageCounts(run.id())).noneMatch(c -> c.status().equals("TESTED"));
                return;
            }
            assertThat(tasks.get(task.id()).state()).as("%s", tasks.verifications(domain.latestAttempt(domain.listStages(task.id()).getFirst().id()).orElseThrow().id())).isEqualTo("JUDGING");
            finishJudges(task.id());
            assertThat(tasks.get(task.id()).state()).as("%s", tasks.verifications(domain.latestAttempt(domain.listStages(task.id()).getFirst().id()).orElseThrow().id())).isEqualTo("AWAITING_DECISION");
            development.advance(admission.require(run.id()), contract);
            assertThat(admission.require(run.id()).state()).as("%s", admission.require(run.id()).waitingMessage()).isEqualTo("REPORTING");
            development.advance(admission.require(run.id()), contract);
            assertThat(admission.require(run.id()).state()).as("%s", admission.require(run.id()).waitingMessage()).isEqualTo("COMPLETED");
            assertThat(runs.coverageCounts(run.id())).containsExactly(new SourceTemplateMapper.Count("TESTED", 1));
            assertThat(Files.readString(source.resolve("src/test/java/example/ExistingTest.java"))).isEqualTo(existingTest);
            assertThat(Files.readString(source.resolve("src/main/java/example/EventBus.java"))).isEqualTo(business);
            assertThat(Files.readString(source.resolve("pom.xml"))).isEqualTo(pom);
            if (alreadyCovered) assertThat(tasks.verifications(domain.latestAttempt(domain.listStages(task.id()).getFirst().id()).orElseThrow().id()))
                    .filteredOn(v -> v.type().equals("GIT_DIFF")).allSatisfy(v -> {
                        assertThat(v.state()).isEqualTo("PASS"); assertThat(v.evidenceJson()).contains("\"changedPaths\":[]");
                    });
            assertThat(tasks.get(task.id()).state()).isEqualTo("AWAITING_DECISION");
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rollingTemplateStartsEachPackageAndFinalRegressionRejectsEarlierBreakage(boolean breakEarlier) throws Exception {
        Files.writeString(source.resolve("src/main/java/example/Counter.java"), "package example; public class Counter { public int initial() { return 0; } }");
        initializeGit(); startSourceRun();
        var refs = runs.files(run.id()).stream().filter(f -> f.target() == 1 && f.exclusion() == null)
                .map(f -> SourceRequirementContext.reference(f.ordinal())).toList();
        assertThat(refs).hasSize(2);
        var credentials = new InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(credentials); access.connected(credentials.generation());
        var fake = (FakeOpenCodeClient) client; fake.setManagedRuntime(credentials.generation(), credentials.serverName());
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_V2_READ_ONLY, true);
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_V2_INTERACTIVE_READ_ONLY, true);
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.DECOMPOSER_CANDIDATE_READ_ONLY, true);
        development.advance(admission.require(run.id()), contract); development.advance(admission.require(run.id()), contract);
        var designer = designers.get(admission.require(run.id()).designerId());
        jdbc.update("UPDATE designer_session SET state='WAITING_INPUT',version=version+1 WHERE id=?", designer.id());
        jdbc.update("UPDATE design_work_package SET state='WAITING_INPUT',last_error_code='LARGE_TASK_MODE_REQUIRED',version=version+1 WHERE designer_session_id=?", designer.id());
        development.advance(admission.require(run.id()), contract); development.advance(admission.require(run.id()), contract);
        var revision = domain.findCurrentDesignRequirementRevision(designer.id()).orElseThrow();
        var decomposition = domain.findTaskDecompositionByRevision(revision.id()).orElseThrow();
        String candidateId = jdbc.queryForObject("SELECT id FROM ai_candidate_submission_run WHERE owner_type='TASK_DECOMPOSITION' AND owner_id=? ORDER BY created_at DESC LIMIT 1", String.class, decomposition.id());
        var decomposer = submissions.find(candidateId).orElseThrow();
        String plan = """
                {"outcome":"READY","normalizedGoal":"分包交付事件安全与最终回归","globalConstraints":[],
                 "workPackages":[
                  {"title":"事件安全分发","objective":"验证未注册事件安全忽略","scopeIn":["src/test/java/example/EventBusTest.java"],"scopeOut":[],"deliverables":["EventBusTest"],"acceptanceIntent":["未注册事件正常返回"],"dependsOn":[]},
                  {"title":"整体事件回归","objective":"在最后验证既有分发和前包行为","scopeIn":["src/test/java/example/RegressionTest.java","src/test/java/example/EventBusTest.java"],"scopeOut":[],"deliverables":["RegressionTest","EventBusTest"],"acceptanceIntent":["前包和已注册事件均保持正确"],"dependsOn":[{"packageIndex":0,"rationale":"使用第一包事件行为"}]}],
                 "coverage":[{"requirementRef":"RQ-1","targetType":"WORK_PACKAGE","targetIndex":0,"rationale":"第一包事件行为"},{"requirementRef":"RQ-2","targetType":"WORK_PACKAGE","targetIndex":1,"rationale":"最后进行整体回归"}],"designGaps":[],"reason":null}
                """.replace("RQ-1", refs.get(0)).replace("RQ-2", refs.get(1));
        var submitted = submissions.submit(new MachineCandidateSubmission.SubmitCommand(candidateId, UUID.randomUUID().toString(), plan,
                decomposer.version(), MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP, MachineCandidateSubmission.SubmissionSchema.ROLE_SPECIFIC_V2));
        assertThat(submitted.outcome()).as("%s", submitted.problems()).isEqualTo(MachineCandidateOutcome.ACCEPTED);
        fake.setSessionState(decomposer.externalSessionId(), "COMPLETED");
        for (int i = 0; i < 10 && domain.listDesignWorkPackages(revision.id()).isEmpty(); i++) designers.pollActiveHandoffs();
        var packages = domain.listDesignWorkPackages(revision.id()); assertThat(packages).hasSize(2);
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.JUDGE_CANDIDATE_READ_ONLY, true);
        {
            for (int index = 0; index < 2; index++) {
                var pack = packages.get(index);
                for (int i = 0; i < 12; i++) {
                    designers.pollActiveHandoffs(); development.advance(admission.require(run.id()), contract);
                    var refreshed = domain.findDesignWorkPackage(pack.id()).orElseThrow();
                    if (domain.findLatestCandidateSubmissionRunForWorkPackage(pack.id(), refreshed.designRevision() + 1L).isPresent()) break;
                }
                pack = domain.findDesignWorkPackage(pack.id()).orElseThrow();
                var candidateRun = domain.findLatestCandidateSubmissionRunForWorkPackage(pack.id(), pack.designRevision() + 1L).orElseThrow();
                var candidate = new PackageDesignV2CompilationTest().candidate();
                String testName = index == 0 ? "EventBusTest" : "RegressionTest";
                ((tools.jackson.databind.node.ObjectNode) candidate.path("deliverables").get(0)).put("target", "src/test/java/example/" + testName + ".java").put("description", "新增 " + testName + " 验证事件行为");
                ((tools.jackson.databind.node.ObjectNode) candidate.path("sourceBindings").get(0)).set("sourceRefs", json.valueToTree(index == 0 ? List.of(refs.get(0)) : List.of(refs.get(1), DocumentRequirementContext.FINAL_REGRESSION)));
                if (index == 1) {
                    ((tools.jackson.databind.node.ObjectNode) candidate.path("scenarios").get(0))
                            .put("action", "运行 RegressionTest 并发布未注册和已注册事件")
                            .put("observableResult", "RegressionTest 断言未注册事件没有处理器调用且已注册事件保持一次调用");
                    var extra = ((tools.jackson.databind.node.ArrayNode) candidate.path("deliverables")).addObject();
                    extra.put("key", "DEL-2").put("kind", "DELIVERABLE").put("target", "src/test/java/example/EventBusTest.java")
                            .put("description", "更新 EventBusTest 共享事件行为").set("requirementRefs", json.valueToTree(List.of("REQ-1")));
                    ((tools.jackson.databind.node.ArrayNode) candidate.path("stages").get(0).path("includes")).add("DEL-2");
                    ((tools.jackson.databind.node.ArrayNode) candidate.path("sourceBindings").get(0).path("candidateRefs")).add("DEL-2");
                }
                readOriginal(candidateRun.externalSessionId(), fake.profileForSession(candidateRun.externalSessionId()).name());
                submitted = submissions.submit(new MachineCandidateSubmission.SubmitCommand(candidateRun.id(), UUID.randomUUID().toString(), json.writeValueAsString(candidate),
                        candidateRun.version(), MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP, MachineCandidateSubmission.SubmissionSchema.ROLE_SPECIFIC_V2));
                assertThat(submitted.outcome()).as("%s", submitted.problems()).isEqualTo(MachineCandidateOutcome.ACCEPTED);
                fake.setSessionState(candidateRun.externalSessionId(), "COMPLETED");
                for (int i = 0; i < 12; i++) {
                    designers.pollActiveHandoffs(); development.advance(admission.require(run.id()), contract);
                    var linked = admission.require(run.id());
                    if (linked.taskId() != null && tasks.get(linked.taskId()).state().equals("RUNNING")) break;
                }
                var current = admission.require(run.id());
                assertThat(current.state()).as("%s", current.waitingMessage()).isEqualTo("EXECUTING");
                var task = tasks.get(current.taskId()); assertThat(task.state()).as("%s", tasks.errors(task.id())).isEqualTo("RUNNING");
                assertThat(task.executionMode()).isEqualTo("ROLLING_PACKAGES");
                assertThat(Path.of(task.worktreePath()).toRealPath()).isEqualTo(source.toRealPath());
                Path test = source.resolve("src/test/java/example/" + testName + ".java"); Files.createDirectories(test.getParent());
                Files.writeString(test, index == 0 ? """
                        class EventBusTest {
                            static int dispatch(boolean registered) {
                                var bus = new example.EventBus(); int[] count = {0};
                                if (registered) bus.register("event", () -> count[0]++);
                                bus.publish("event"); return count[0];
                            }
                            @org.junit.jupiter.api.Test void unknownEventIsIgnored() { if (dispatch(false) != 0) throw new AssertionError(); }
                        }
                        """ : """
                        class RegressionTest {
                            @org.junit.jupiter.api.Test void earlierAndRegisteredBehaviorRemainCorrect() {
                                if (new example.Counter().initial() != 0 || EventBusTest.dispatch(false) != 0 || EventBusTest.dispatch(true) != 1) throw new AssertionError("cross-package regression");
                            }
                        }
                        """);
                if (index == 1) {
                    Path earlier = source.resolve("src/test/java/example/EventBusTest.java");
                    String content = Files.readString(earlier);
                    Files.writeString(earlier, breakEarlier ? content.replace("return count[0];", "return count[0] + 9;") : content + "\n// Included in final regression.\n");
                }
                tasks.verify(task.id());
                if (index == 1 && breakEarlier) {
                    var last = domain.listStages(task.id()).getLast();
                    assertThat(tasks.verifications(domain.latestAttempt(last.id()).orElseThrow().id())).anySatisfy(result -> {
                        assertThat(result.type()).isEqualTo("PROCESS"); assertThat(result.state()).isEqualTo("FAIL");
                        assertThat(result.evidenceJson()).contains("cross-package regression");
                    });
                    development.advance(admission.require(run.id()), contract);
                    assertThat(admission.require(run.id()).state()).isNotEqualTo("COMPLETED");
                    assertThat(tasks.judges(task.id())).isEmpty(); assertThat(runs.coverageCounts(run.id())).noneMatch(c -> c.status().equals("TESTED"));

                    return;
                }
                if (index == 0) {
                    assertThat(domain.listPackageFactSnapshots(task.id())).hasSize(1);
                    assertThat(tasks.judges(task.id())).isEmpty();
                } else finishJudges(task.id());
            }
            development.advance(admission.require(run.id()), contract); development.advance(admission.require(run.id()), contract);
            var finished = admission.require(run.id());
            assertThat(finished.state()).as("%s", finished.waitingMessage()).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Integer.class)).isEqualTo(1);
            assertThat(runs.coverageCounts(run.id())).containsExactly(new SourceTemplateMapper.Count("TESTED", 2));
            assertThat(Files.readString(source.resolve("src/main/java/example/EventBus.java"))).isEqualTo(business);
            assertThat(Files.readString(source.resolve("pom.xml"))).isEqualTo(pom);

        }
    }
    private void initializeGit() throws Exception {
        Files.writeString(source.resolve(".gitignore"), "target/\n");
        for (var command : List.of(List.of("git", "init", "-b", "main"), List.of("git", "add", "."),
                List.of("git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-m", "fixture"))) {
            var process = new ProcessBuilder(command).directory(source.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor()).as(output).isZero();
        }
    }
    private void readOriginal(String session, String profile) {
        if (assist.session(session) == null) assist.insertSession(new AssistMapper.Session(session, access.current().orElseThrow().generation(),
                source.toString(), profile, "[]", "[]", Instant.now().toString()));
        var body = new HashMap<String, Object>(); developmentScopes.enrich(session, body);
        var match = java.util.regex.Pattern.compile("scope=(lps_[A-Za-z0-9_.-]+)").matcher(body.get("system").toString());
        assertThat(match.find()).isTrue(); String grant = match.group(1); sourceReads.work(grant);
        for (var file : runs.files(run.id())) if (file.target()==1 && file.exclusion()==null) {
            int start=1;
            while(true) {
                var read=sourceReads.read(grant,file.path(),file.sha256(),start,200);
                if(read.endLine()>=read.totalLines()) break;
                start=read.endLine()+1;
            }
        }
    }
    private void finishJudges(String taskId) {
        var fake = (FakeOpenCodeClient) client; var completed = new HashSet<String>();
        for (int tick = 0; tick < 30 && !tasks.get(taskId).state().equals("AWAITING_DECISION"); tick++) {
            tasks.pollJudges(taskId);
            for (var judge : domain.listJudgeRuns(taskId)) {
                if (!judge.state().equals("RUNNING") || completed.contains(judge.id())) continue;
                var ids = jdbc.queryForList("SELECT id FROM ai_candidate_submission_run WHERE owner_type='JUDGE_RUN' AND owner_id=? AND state='OPEN'", String.class, judge.id());
                if (ids.isEmpty()) continue;
                var candidate = domain.findCandidateSubmissionRun(ids.getFirst()).orElseThrow();
                var frozen = domain.findJudgeCandidateSourceSnapshot(candidate.id()).orElseThrow();
                var evidenceIds = new ArrayList<String>();
                for (var item : json.readTree(frozen.canonicalEvidenceJson()).path("items")) evidenceIds.add(item.path("id").asText());
                var output = new JudgeDecisionCompilation.Candidate("JUDGE_DECISION_V1", judge.role(), "PASS", "原文、批准设计和测试证据一致", evidenceIds);
                var rejected = submissions.submit(new MachineCandidateSubmission.SubmitCommand(candidate.id(), "unread", json.writeValueAsString(output),
                        candidate.version(), MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP, MachineCandidateSubmission.SubmissionSchema.ROLE_SPECIFIC_V2));
                assertThat(rejected.outcome()).isEqualTo(MachineCandidateOutcome.REJECTED);
                assertThat(rejected.problems()).anyMatch(problem -> problem.code().equals("DOCUMENT_ORIGINAL_READ_INCOMPLETE"));
                readOriginal(judge.externalSessionId(), "JUDGE_CANDIDATE_READ_ONLY");
                var accepted = submissions.submit(new MachineCandidateSubmission.SubmitCommand(candidate.id(), "read", json.writeValueAsString(output),
                        submissions.find(candidate.id()).orElseThrow().version(), MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                        MachineCandidateSubmission.SubmissionSchema.ROLE_SPECIFIC_V2));
                assertThat(accepted.outcome()).as("%s", accepted.problems()).isEqualTo(MachineCandidateOutcome.ACCEPTED);
                fake.setSessionState(judge.externalSessionId(), "COMPLETED"); completed.add(judge.id());
            }
        }
        assertThat(completed).as("task=%s judges=%s errors=%s", tasks.get(taskId).state(), domain.listJudgeRuns(taskId), jdbc.queryForList("SELECT code,message FROM error_event WHERE task_id=?", taskId)).hasSize(2);
    }
    private void writeBehaviorTest() throws Exception {
        Path test = source.resolve("src/test/java/example/EventBusTest.java");
        Files.writeString(test, """
                package example;
                class EventBusTest {
                    @org.junit.jupiter.api.Test void preservesUnknownAndRegisteredEvents() {
                        var bus = new EventBus();
                        int[] calls = {0}; bus.register("registered", () -> calls[0]++);
                        bus.publish("missing");
                        if (calls[0] != 0) throw new AssertionError("unknown event must be ignored");
                        bus.publish("registered");
                        if (calls[0] != 1) throw new AssertionError("existing dispatch must be preserved");
                        System.out.println("EventBusTest: unknown and existing event assertions passed");
                    }
                }
                """);
    }
    private void prepareMavenProject() throws Exception {
        Files.writeString(source.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>events</artifactId><version>1</version>
                <properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>6.0.3</version><scope>test</scope></dependency></dependencies>
                <build><plugins>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.5.0</version></plugin>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version></plugin>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version><configuration><forkCount>0</forkCount></configuration></plugin>
                </plugins></build></project>
                """);
        Files.createDirectories(source.resolve(".mvn")); Files.writeString(source.resolve(".mvn/maven.config"), "-o\n");
    }
}
