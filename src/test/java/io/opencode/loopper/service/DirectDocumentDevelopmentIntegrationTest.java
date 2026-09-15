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
class DirectDocumentDevelopmentIntegrationTest {
    @Autowired org.springframework.context.ApplicationContext applicationContext;
    @Autowired Flyway flyway;
    @Autowired DocumentTemplateService service;
    @Autowired DocumentTemplateAdmission admission;
    @Autowired DocumentTemplateMapper runs;
    @Autowired DocumentRequirementMapper requirements;
    @Autowired DocumentDevelopmentBootstrap bootstrap;
    @Autowired DocumentDevelopmentDesign designs;
    @Autowired DocumentDevelopmentPromotion promotion;
    @Autowired DocumentDevelopmentFlow development;
    @Autowired DocumentTemplateControl controls;
    @Autowired DocumentTemplateCoordinator coordinator;
    @Autowired DesignerAutoModeService autoMode;
    @Autowired DesignerSessionService designers;
    @Autowired MachineCandidateSubmission submissions;
    @Autowired TaskService tasks;
    @Autowired DocumentRequirementReportService reports;
    @Autowired DocumentRequirementClarifications clarifications;
    @Autowired DocumentRequirementWorkflow requirementWorkflow;
    @Autowired DocumentSupplementService supplements;
    @Autowired DocumentSupplementDesign supplementDesign;
    @Autowired DocumentSupplementAdmission supplementAdmission;
    @Autowired DocumentSupplementMapper supplementRows;
    @Autowired DocumentTemplateStorage documentStorage;
    @Autowired RollingPackagePlanGenerationService planGeneration;
    @Autowired RollingPackageService rolling;
    @Autowired DocumentDevelopmentScope developmentScopes;
    @Autowired DocumentDevelopmentReads sourceReads;
    @Autowired AssistMapper assist;
    @Autowired DocumentTemplateModelMapper models;
    @Autowired DocumentModelExecution modelExecution;
    @Autowired GitEvidenceProcess git;
    @Autowired DesignerConversationCoordinator conversations;
    @Autowired InternalMcpRuntimeAccess access;
    @Autowired DocumentDevelopmentMapper bindings;
    @Autowired LoopperMapper domain;
    @Autowired ProjectService projects;
    @Autowired LoopperProperties properties;
    @Autowired OpenCodeClient client;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path temporary;
    private DocumentTemplateRunRow run;
    private DocumentTemplateService.Contract contract;
    private Path source;
    @BeforeEach void prepare() throws Exception {
        flyway.clean(); flyway.migrate(); ((FakeOpenCodeClient) client).reset();
        properties.getOpenCode().setModel("fake/test-model");
        source = Files.createDirectory(temporary.resolve("source")); Files.writeString(source.resolve("existing.txt"), "用户未提交内容");
        String project = projects.create("原文开发", source.toString(), "test").id();
        run = service.create(new DocumentTemplateService.Request(UUID.randomUUID().toString(), "REQUIREMENT_DEVELOPMENT", io.opencode.loopper.template.DocumentTemplateDefinition.VERSION, project, null),
                List.of(new DocumentTemplateStorage.Incoming("需求.md", ("# EventBus 回归\n工作包范围：`src/test/java/example/EventBusTest.java`。"
                        + "新增 EventBusTest 聚焦验证未注册事件安全忽略，并回归既有分发行为。").getBytes(StandardCharsets.UTF_8))));
        contract = json.readValue(run.contractJson(), DocumentTemplateService.Contract.class);
        assertThat(run.state()).isEqualTo("DESIGNING"); assertThat(run.requirementRevision()).isZero();
    }
    @Test void supplementFreezesNewOriginalsWithoutExtractionAndPreservesOldDesignSources() {
        var credentials = new InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(credentials); access.connected(credentials.generation());
        var fake = (FakeOpenCodeClient) client; fake.setManagedRuntime(credentials.generation(), credentials.serverName());
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_V2_READ_ONLY, true);
        var designer = bootstrap.create(run, contract); designs.startSingle(designer.id());
        var old = domain.findCurrentDesignRequirementRevision(designer.id()).orElseThrow();
        var pack = domain.listDesignWorkPackages(old.id()).getFirst();
        String original = DocumentRequirementContext.resolve(domain, old, pack);
        jdbc.update("UPDATE designer_session SET state='WAITING_INPUT',version=version+1 WHERE id=?", designer.id());
        admission.transition(admission.require(run.id()), DocumentTemplateState.WAITING_INPUT,
                LifecycleEvent.REQUIRE_INPUT, "DOCUMENT_DEVELOPMENT_WAIT", "业务规则待补充");
        var option = supplements.options(run.id()); assertThat(option.available()).as(option.message()).isTrue();
        var files = List.of(new DocumentTemplateStorage.Incoming("补充.md", "# 补充\n新增事件在注册后按顺序分发".getBytes(StandardCharsets.UTF_8)));
        var next = supplements.upload(run.id(), option.request(), files);
        assertThat(next.state()).isEqualTo("DESIGNING"); assertThat(next.sourceRevision()).isEqualTo(2);
        assertThat(next.requirementRevision()).isZero();
        assertThat(supplements.upload(run.id(), option.request(), files).sourceRevision()).isEqualTo(2);
        var pending = supplementRows.pending(run.id()).orElseThrow();
        supplementDesign.advance(next, pending); supplementDesign.advance(next, pending);
        var revised = domain.findCurrentDesignRequirementRevision(designer.id()).orElseThrow();
        assertThat(revised.id()).isNotEqualTo(old.id());
        assertThat(bindings.design(revised.id()).orElseThrow().documentRevision()).isEqualTo(2);
        assertThat(DocumentRequirementContext.resolve(domain, old, pack)).isEqualTo(original);
        assertThat(runs.sourceFiles(run.id(), 1)).hasSize(1); assertThat(runs.sourceFiles(run.id(), 2)).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_requirement_revision", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_template_model_run", Integer.class)).isZero();
        assertThat(fake.abortedSessionIds()).contains(pack.designerExternalSessionId());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void singlePackageRunsCurrentDirectoryTestsAndReportsWithoutAcceptingResult(boolean gitProject) throws Exception {
        prepareMavenProject(gitProject);
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
                .set("sourceRefs", json.valueToTree(List.of("DOC-1", DocumentRequirementContext.FINAL_REGRESSION)));
        var submitted = submissions.submit(new MachineCandidateSubmission.SubmitCommand(candidateRun.id(), "document-package-fixture",
                json.writeValueAsString(candidate), candidateRun.version(), MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                MachineCandidateSubmission.SubmissionSchema.ROLE_SPECIFIC_V2));
        assertThat(submitted.outcome()).as("%s", submitted.problems()).isEqualTo(MachineCandidateOutcome.ACCEPTED);
        fake.setSessionState(candidateRun.externalSessionId(), "COMPLETED");
        for (int i = 0; i < 12 && admission.require(run.id()).taskId() == null; i++) {
            designers.pollActiveHandoffs(); development.advance(admission.require(run.id()), contract);
        }
        var current = admission.require(run.id());
        assertThat(current.state()).as("%s", current.waitingMessage()).isEqualTo("EXECUTING");
        var task = domain.findTask(current.taskId()).orElseThrow();
        assertThat(task.state()).isEqualTo("PENDING_START"); assertThat(task.executionMode()).isNotEqualTo("TEMPLATE_REPORT");
        assertThat(domain.listStages(task.id())).isNotEmpty().allSatisfy(stage -> assertThat(stage.testPolicy()).isEqualTo("REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Integer.class)).isEqualTo(1);
        assertThat(Files.readString(source.resolve("existing.txt"))).isEqualTo("用户未提交内容");
        development.advance(current, contract);
        task = tasks.get(task.id());
        assertThat(task.state()).as("%s", tasks.errors(task.id())).isEqualTo("RUNNING");
        assertThat(Path.of(task.worktreePath()).toRealPath()).isEqualTo(source.toRealPath());
        if (gitProject) {
            assertThat(task.branchName()).isNotEqualTo("main");
            assertThat(git.read(source, "branch", "--show-current").trim()).isEqualTo(task.branchName());
            String previous=domain.activeSessions(task.id()).getFirst().externalSessionId();
            tasks.recoverAfterRestart();
            assertThat(tasks.get(task.id()).state()).isEqualTo("RETRY_WAIT");
            assertThat(fake.abortedSessionIds()).contains(previous);
            assertThat(tasks.attempts(task.id())).hasSize(1);
            jdbc.update("UPDATE task_retry_schedule SET due_at=? WHERE task_id=? AND state='SCHEDULED'",Instant.EPOCH.toString(),task.id());
            tasks.startDueRetries();
            assertThat(tasks.get(task.id()).state()).isEqualTo("RUNNING");
            assertThat(domain.activeSessions(task.id())).hasSize(1).allSatisfy(session->assertThat(session.externalSessionId()).isNotEqualTo(previous));
            assertThat(domain.documentTaskRevision(task.id())).isEqualTo(1);
            assertThat(admission.require(run.id()).taskId()).isEqualTo(task.id());
        }
        // Controlled implementation fixture writes executable assertions; the formal verifier runs this native entry.
        Path test = source.resolve("src/test/java/example/EventBusTest.java"); Files.createDirectories(test.getParent());
        Files.writeString(test, """
                import java.util.*;
                class EventBusTest {
                    @org.junit.jupiter.api.Test void preservesUnknownAndRegisteredEvents() {
                        var handlers = new HashMap<String, Runnable>();
                        int[] calls = {0}; handlers.put("registered", () -> calls[0]++);
                        handlers.getOrDefault("missing", () -> {}).run();
                        if (calls[0] != 0) throw new AssertionError("unknown event must be ignored");
                        handlers.getOrDefault("registered", () -> {}).run();
                        if (calls[0] != 1) throw new AssertionError("existing dispatch must be preserved");
                        System.out.println("EventBusTest: unknown and existing event assertions passed");
                    }
                }
                """);
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.JUDGE_CANDIDATE_READ_ONLY, true);
        {
            tasks.verify(task.id());
            assertThat(tasks.get(task.id()).state()).as("%s", tasks.verifications(domain.latestAttempt(domain.listStages(task.id()).getFirst().id()).orElseThrow().id())).isEqualTo("JUDGING");
            finishJudges(task.id());
            assertThat(tasks.get(task.id()).state()).as("%s", tasks.verifications(domain.latestAttempt(domain.listStages(task.id()).getFirst().id()).orElseThrow().id())).isEqualTo("AWAITING_DECISION");
            development.advance(admission.require(run.id()), contract);
            assertThat(admission.require(run.id()).state()).as("%s", admission.require(run.id()).waitingMessage()).isEqualTo("REPORTING");
            development.advance(admission.require(run.id()), contract);
            assertThat(admission.require(run.id()).state()).as("%s", admission.require(run.id()).waitingMessage()).isEqualTo("COMPLETED");
            assertThat(reports.named(run.id(), "matrix.json").content()).contains("ACCEPTED_BY_EXECUTION_AND_DUAL_JUDGES", "DOC-1");
            assertThat(tasks.get(task.id()).state()).isEqualTo("AWAITING_DECISION");
        }
    }
    private void readOriginal(String session, String profile) {
        if (assist.session(session) == null) assist.insertSession(new AssistMapper.Session(session, access.current().orElseThrow().generation(),
                source.toString(), profile, "[]", "[]", Instant.now().toString()));
        var body = new HashMap<String, Object>(); developmentScopes.enrich(session, body);
        var match = java.util.regex.Pattern.compile("scope=(lpd_[A-Za-z0-9_.-]+)").matcher(body.get("system").toString());
        assertThat(match.find()).isTrue(); String grant = match.group(1);
        sourceReads.documents(grant);
        for (var file : runs.sourceFiles(run.id(), run.sourceRevision()))
            for (int i = 0; i < file.sectionCount(); i++) sourceReads.source(grant, file.id(), i, file.sha256());
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
    private void prepareMavenProject(boolean gitProject) throws Exception {
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
        if (gitProject) {
            Files.writeString(source.resolve(".gitignore"), "target/\n");
            git.read(source, "init", "-b", "main", "--template="); git.read(source, "add", ".");
            git.read(source, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-m", "frozen fixture baseline");
        }
    }
}
