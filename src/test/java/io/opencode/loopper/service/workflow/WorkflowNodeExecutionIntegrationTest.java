package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static io.opencode.loopper.workflow.WorkflowGraph.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes=LoopperApplication.class, properties={"loopper.opencode.mode=fake", "loopper.monitor-delay=1h", "loopper.designer-monitor-delay=1h"})
class WorkflowNodeExecutionIntegrationTest {
    private static final Path DATA = data();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("loopper.data-dir", () -> DATA.toString());
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATA.resolve("execution.db")
                + "?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    @Autowired Flyway flyway;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowPlans plans;
    @Autowired WorkflowNodeActions actions;
    @Autowired WorkflowNodeRuns nodes;
    @Autowired WorkflowPlanRevisions revisions;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean WorkflowInputSnapshots snapshots;
    @Autowired ProjectService projects;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @TempDir Path directory;
    private String project;
    @BeforeEach void prepare() throws Exception {
        flyway.clean(); flyway.migrate();
        project = projects.create("节点执行", Files.createDirectory(directory.resolve("project")).toString(), "test").id();
    }
    @Test void manualChainPinsUpstreamDeliveryAndFinishesWithoutHiddenTaskOrJudge() {
        String id = requirement(chain());
        assertThat(nodes.summaries(id, 1)).extracting(row -> row.state()).containsExactly("PENDING", "PENDING");
        assertThat(count("workflow_node_attempt")).isZero();
        var request = new WorkflowNodeActions.Start(key(), plans.require(id).version(), null);
        var first = actions.startHuman(id, "first", request);
        assertThat(actions.startHuman(id, "first", request)).isEqualTo(first);
        assertThat(plans.require(id).state()).isEqualTo("PAUSED");
        var submitted = new WorkflowNodeActions.Complete(key(), plans.require(id).version(), first.attemptId(), first.attemptVersion(), result("设计结果", "SUCCESS"));
        var completed = actions.completeHuman(id, "first", submitted);
        assertThat(actions.completeHuman(id, "first", submitted)).isEqualTo(completed);
        var second = start(id, "second");
        var input = actions.inputs(id, "second", second.attemptId()).values().getFirst();
        assertThat(input.attemptId()).isEqualTo(first.attemptId());
        assertThat(input.sha256()).isEqualTo(actions.result(id, "first", first.attemptId()).sha256());
        assertThat(input.content().isNull()).isTrue(); assertThat(input.reference().version()).isEqualTo(1);
        assertThat(actions.inputContent(id, "second", second.attemptId(), "upstream", 0, 12000).text()).isEqualTo("设计结果");
        complete(id, "second", second, result("最终交付", "SUCCESS"));
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
        for (String table : List.of("task", "task_queue", "workspace_lease", "judge_run")) assertThat(count(table)).isZero();
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
        assertThat(json.writeValueAsString(actions.overview(id))).doesNotContain("设计结果", "最终交付", "inputsJson", "definitionJson");
    }
    @Test void acceptedOutputCannotReleaseDependencyBeforeStopAndCompletion() {
        String id = requirement(chain()); var first = start(id, "first");
        var attempt = nodes.attempt(first.attemptId()); var delivery = result("已提交但仍运行", "SUCCESS");
        transactions.executeWithoutResult(status -> nodes.accept(attempt, delivery));
        assertThat(actions.result(id, "first", first.attemptId()).state()).isEqualTo("WAITING_INPUT");
        assertThatThrownBy(() -> start(id, "second")).isInstanceOf(ConflictException.class).hasMessageContaining("前置节点");
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> nodes.finish(attempt, WorkflowAttemptState.SUCCEEDED)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("停止");
        assertThatThrownBy(() -> jdbc.update("UPDATE workflow_node_attempt SET state='SUCCEEDED' WHERE id=?", attempt.id()))
                .hasMessageContaining("stop proof");
        assertThat(count("workflow_node_attempt")).isEqualTo(1);
        complete(id, "first", first, delivery);
        assertThat(start(id, "second").state()).isEqualTo("WAITING_INPUT");
    }
    @Test void branchJoinWaitsForSelectedWorkAndOmitsOptionalSkippedInput() {
        var inputs = List.of(new Input("left", InputSource.NODE, "left", "result", DataKind.TEXT, false),
                new Input("right", InputSource.NODE, "right", "result", DataKind.TEXT, false));
        var graph = new WorkflowGraph(1, List.of(human("decision", List.of()), human("left", List.of()), human("right", List.of()), human("join", inputs)),
                List.of(new Edge("dl", "decision", "left", "SUCCESS"), new Edge("dr", "decision", "right", "REVISE"),
                        new Edge("lj", "left", "join", null), new Edge("rj", "right", "join", null)), List.of());
        String id = requirement(graph); var decision = start(id, "decision");
        complete(id, "decision", decision, result("需要修正", "REVISE"));
        assertThat(nodes.node(id, 1, "left").state()).isEqualTo("SKIPPED");
        assertThatThrownBy(() -> start(id, "join")).isInstanceOf(ConflictException.class);
        var right = start(id, "right"); complete(id, "right", right, result("完成修正", "SUCCESS"));
        var join = start(id, "join");
        assertThat(actions.inputs(id, "join", join.attemptId()).values()).extracting(WorkflowDelivery.Input::name).containsExactly("right");
        complete(id, "join", join, result("汇总", "SUCCESS"));
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
    }
    @Test void publicInputsAreFrozenAndRetriesRetainSnapshotsWhileLateResultsAreRejected() {
        var input = new Input("source", InputSource.REQUIREMENT, "brief", null, DataKind.TEXT, true);
        String id = requirement(new WorkflowGraph(1, List.of(human("first", List.of(input))), List.of(),
                List.of(new PublicInput("brief", "原始资料", DataKind.TEXT, true))));
        var first = actions.startHuman(id, "first", new WorkflowNodeActions.Start(key(), plans.require(id).version(), Map.of("brief", value("固定资料"))));
        var old = nodes.attempt(first.attemptId());
        var stopping = transactions.execute(status -> nodes.transition(old, WorkflowAttemptState.STOPPING, LifecycleEvent.CANCEL));
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> nodes.finish(stopping, WorkflowAttemptState.FAILED)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> start(id, "first")).isInstanceOf(ConflictException.class);
        assertThat(count("workflow_node_attempt")).isEqualTo(1);
        transactions.executeWithoutResult(status -> {
            nodes.stopHuman(stopping); nodes.finish(stopping, WorkflowAttemptState.FAILED);
        });
        assertThatThrownBy(() -> actions.startHuman(id, "first", new WorkflowNodeActions.Start(key(), plans.require(id).version(), Map.of("brief", value("偷换资料")))))
                .isInstanceOf(ConflictException.class);
        var retry = start(id, "first");
        assertThat(nodes.attempt(retry.attemptId()).ordinal()).isEqualTo(2);
        assertThat(nodes.attempt(retry.attemptId()).inputsJson()).isEqualTo(old.inputsJson());
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> nodes.accept(old, result("迟到结果", "SUCCESS"))))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> nodes.stopHuman(old))).isInstanceOf(ConflictException.class);
        assertThat(nodes.findDelivery(old.id())).isEmpty();
        complete(id, "first", retry, result("重试交付", "SUCCESS"));
        assertThat(count("workflow_node_attempt")).isEqualTo(2);
    }
    @Test void wrongNodeMissingOutputAndWrongTypeCannotCompleteOrLeakBodies() {
        String id = requirement(chain()); var first = start(id, "first");
        String other = requirement(chain());
        assertThatThrownBy(() -> actions.inputs(other, "first", first.attemptId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> actions.inputs(id, "second", first.attemptId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> actions.inputContent(other, "first", first.attemptId(), "result", 0, 12000)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> actions.inputContent(id, "second", first.attemptId(), "result", 0, 12000)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> complete(id, "first", first, new WorkflowDelivery("缺少输出", "SUCCESS", Map.of()))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> complete(id, "first", first, new WorkflowDelivery("类型错误", "SUCCESS", Map.of("result",
                new WorkflowDelivery.Value(DataKind.JSON, json.createObjectNode()))))).isInstanceOf(BadRequestException.class);
        assertThat(count("workflow_node_delivery")).isZero(); assertThat(count("workflow_attempt_stop")).isZero();
        assertThat(nodes.attempt(first.attemptId()).state()).isEqualTo("WAITING_INPUT");
    }
    @Test void referencedInputsSurviveFailureAndRetryWithTheSameBytesAndNoNewProducer() {
        String id=requirement(chain());var first=start(id,"first");complete(id,"first",first,result("原始 😀 交付","SUCCESS"));
        var second=start(id,"second");var attempt=nodes.attempt(second.attemptId());
        assertThat(actions.inputs(id,"second",attempt.id()).version()).isEqualTo(2);
        assertThat(actions.inputContent(id,"second",attempt.id(),"upstream",0,4).text()).isEqualTo("原始 😀");
        transactions.executeWithoutResult(status->{nodes.stopHuman(attempt);nodes.finish(attempt,WorkflowAttemptState.FAILED);});
        var retry=start(id,"second");assertThat(nodes.attempt(retry.attemptId()).inputsJson()).isEqualTo(attempt.inputsJson());
        assertThat(actions.inputContent(id,"second",retry.attemptId(),"upstream",0,12000).text()).isEqualTo("原始 😀 交付");
        assertThatThrownBy(()->actions.inputContent(id,"second",retry.attemptId(),"not-bound",0,12000)).isInstanceOf(BadRequestException.class);
        complete(id,"second",retry,result("重试完成","SUCCESS"));assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
    }
    @Test void historicalInlineAttemptRecoversAndRetriesAgainstTheSamePinnedProducer() {
        String id=requirement(chain());var first=start(id,"first");complete(id,"first",first,result("历史内联交付","SUCCESS"));
        org.mockito.Mockito.doAnswer(call->call.getArgument(0)).when(snapshots).freeze(org.mockito.ArgumentMatchers.any());
        WorkflowNodeActions.Receipt second;
        try {second=start(id,"second");} finally {org.mockito.Mockito.doCallRealMethod().when(snapshots).freeze(org.mockito.ArgumentMatchers.any());}
        var attempt=nodes.attempt(second.attemptId());assertThat(actions.inputs(id,"second",attempt.id()).version()).isEqualTo(1);
        assertThat(attempt.inputsJson()).doesNotContain("reference");
        transactions.executeWithoutResult(status->{nodes.stopHuman(attempt);nodes.finish(attempt,WorkflowAttemptState.FAILED);});
        var retry=start(id,"second");assertThat(actions.inputs(id,"second",retry.attemptId()).version()).isEqualTo(2);
        assertThat(nodes.inputs(nodes.attempt(retry.attemptId()))).isEqualTo(nodes.inputs(attempt));
        assertThat(actions.inputContent(id,"second",attempt.id(),"upstream",0,12000).text()).isEqualTo("历史内联交付");
        complete(id,"second",retry,result("恢复完成","SUCCESS"));
    }
    @Test void replacingTheProducerAndFailedConsumerDoesNotChangeHistoricalInputReferences() {
        String id=requirement(chain());var first=start(id,"first");complete(id,"first",first,result("旧生产者原交付","SUCCESS"));
        var second=start(id,"second");var old=nodes.attempt(second.attemptId());
        transactions.executeWithoutResult(status->{nodes.stopHuman(old);nodes.finish(old,WorkflowAttemptState.FAILED);});
        var replacement=new WorkflowGraph(1,List.of(human("replacement",List.of()),human("second",List.of(new Input("upstream",InputSource.NODE,"replacement","result",DataKind.TEXT,true)))),
                List.of(new Edge("new-edge","replacement","second",null)),List.of());
        revisions.revise(id,new WorkflowRequests.RevisePlan(key(),plans.require(id).version(),1,replacement));
        var producer=start(id,"replacement");complete(id,"replacement",producer,result("新版生产者交付","SUCCESS"));var consumer=start(id,"second");
        assertThat(actions.inputContent(id,"second",consumer.attemptId(),"upstream",0,12000).text()).isEqualTo("新版生产者交付");
        assertThat(actions.inputContent(id,"second",old.id(),"upstream",0,12000).text()).isEqualTo("旧生产者原交付");
        assertThat(nodes.inputSnapshot(old).values().getFirst().attemptId()).isEqualTo(first.attemptId());
        assertThat(nodes.attempt(old.id()).inputsJson()).isEqualTo(old.inputsJson());
        complete(id,"second",consumer,result("完成新计划","SUCCESS"));
    }
    @Test void acknowledgementFailureRollsBackDeliveryStopProofCompletionAndAudit() {
        String id = requirement(chain()); var first = start(id, "first");
        int events = count("state_transition_event");
        jdbc.execute("CREATE TRIGGER refuse_execution_ack BEFORE INSERT ON workflow_command BEGIN SELECT RAISE(ABORT,'execution ack failure'); END");
        try {
            assertThatThrownBy(() -> complete(id, "first", first, result("不能部分完成", "SUCCESS"))).hasStackTraceContaining("execution ack failure");
            assertThat(nodes.attempt(first.attemptId()).state()).isEqualTo("WAITING_INPUT");
            assertThat(nodes.node(id, 1, "first").state()).isEqualTo("ACTIVE");
            assertThat(count("workflow_node_delivery")).isZero(); assertThat(count("workflow_attempt_stop")).isZero();
            assertThat(count("state_transition_event")).isEqualTo(events);
        } finally { jdbc.execute("DROP TRIGGER refuse_execution_ack"); }
        complete(id, "first", first, result("正常恢复", "SUCCESS"));
    }
    @Test void concurrentReplayCannotCreateTwoAttempts() throws Exception {
        String id = requirement(chain()); var request = new WorkflowNodeActions.Start(key(), plans.require(id).version(), null);
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        Callable<WorkflowNodeActions.Receipt> run = () -> { ready.countDown(); if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError(); return actions.startHuman(id, "first", request); };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(run); var two = pool.submit(run);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(one.get(15, TimeUnit.SECONDS)).isEqualTo(two.get(15, TimeUnit.SECONDS));
        } finally { start.countDown(); }
        assertThat(count("workflow_node_attempt")).isEqualTo(1);
        assertThat(nodes.node(id, 1, "first").attemptCount()).isEqualTo(1);
    }
    @Test void frozenPlanNodeAttemptAndDeliveryCannotBeRewritten() {
        String id = requirement(chain()); var first = start(id, "first");
        complete(id, "first", first, result("原交付", "SUCCESS"));
        assertThatThrownBy(() -> jdbc.update("UPDATE workflow_node_attempt SET inputs_json='{}' WHERE id=?", first.attemptId())).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE workflow_node_run SET definition_json='{}' WHERE id=?", first.nodeRunId())).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE workflow_node_delivery SET content_json='{}' WHERE attempt_id=?", first.attemptId())).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM workflow_attempt_stop WHERE attempt_id=?", first.attemptId())).hasMessageContaining("retained");
        assertThatThrownBy(() -> plans.revise(id, new WorkflowRequests.RevisePlan(key(), plans.require(id).version(), 1, chain())))
                .isInstanceOf(ConflictException.class);
    }
    private String requirement(WorkflowGraph graph) {
        var template = templates.create(new WorkflowRequests.CreateTemplate(key(), "人工流程", "", graph, null));
        var created = plans.create(new WorkflowRequests.CreateRequirement(key(), project, "需求", "按本次原始目标工作", template.id(), 1));
        plans.confirm(created.id(), new WorkflowRequests.VersionCommand(key(), created.version())); return created.id();
    }
    private WorkflowNodeActions.Receipt start(String id, String node) { return actions.startHuman(id, node, new WorkflowNodeActions.Start(key(), plans.require(id).version(), null)); }
    private WorkflowNodeActions.Receipt complete(String id, String node, WorkflowNodeActions.Receipt attempt, WorkflowDelivery output) {
        return actions.completeHuman(id, node, new WorkflowNodeActions.Complete(key(), plans.require(id).version(), attempt.attemptId(), attempt.attemptVersion(), output));
    }
    private WorkflowDelivery result(String value, String outcome) { return new WorkflowDelivery("人工完成记录", outcome, Map.of("result", value(value))); }
    private WorkflowDelivery.Value value(String value) { return new WorkflowDelivery.Value(DataKind.TEXT, json.getNodeFactory().stringNode(value)); }
    private static Node human(String id, List<Input> inputs) {
        return new Node(id, id, NodeKind.HUMAN, null, 0, null, "人工检查 " + id, inputs,
                List.of(new Output("result", "处理结果", DataKind.TEXT, true)), List.of("SUCCESS", "REVISE"),
                new Completion(CompletionKind.HUMAN, "人工确认并提交结果", null), 1, true, Map.of());
    }
    private static WorkflowGraph chain() { return new WorkflowGraph(1, List.of(human("first", List.of()), human("second",
            List.of(new Input("upstream", InputSource.NODE, "first", "result", DataKind.TEXT, true)))),
            List.of(new Edge("first-second", "first", "second", null)), List.of()); }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private static String key() { return UUID.randomUUID().toString(); }
    private static Path data() {
        try { return Files.createTempDirectory("loopper-workflow-execution-"); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
}
