package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static io.opencode.loopper.workflow.WorkflowFixtures.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes=LoopperApplication.class, properties={
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h", "loopper.designer-monitor-delay=1h"})
class WorkflowPlanningIntegrationTest {
    @Autowired Flyway flyway;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowNodePresets presets;
    @Autowired WorkflowBuiltinFlows builtins;
    @Autowired io.opencode.loopper.service.roles.RolePublishingService rolePublishing;
    @Autowired WorkflowPlans plans;
    @Autowired WorkflowEncoding encoding;
    @Autowired ProjectService projects;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;
    String project;
    @BeforeEach void prepare() throws Exception {
        flyway.clean(); flyway.migrate();
        project = projects.create("流程试验", Files.createDirectory(directory.resolve("project")).toString(), "test").id();
    }
    @Test void templatesCopyAndArchiveKeepImmutableVersionsAndIndependentRequirementPlans() {
        templates.installBuiltin("builtin.example", "内置示例", "说明", chain(), CanvasLayout.empty());
        var original = templates.get("builtin.example", null);
        assertThatThrownBy(() -> templates.archive(original.id(), new WorkflowRequests.VersionCommand(key(), original.version())))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> templates.revise(original.id(), new WorkflowRequests.ReviseTemplate(key(), original.version(),
                original.revision(), "改名", "", single()))).isInstanceOf(ConflictException.class);
        var copy = templates.copy(original.id(), new WorkflowRequests.CopyTemplate(key(), 1, "我的流程"));
        var requirement = plans.create(new WorkflowRequests.CreateRequirement(key(), project, "实际需求", "完成目标", copy.id(), 1));
        var changed = templates.revise(copy.id(), new WorkflowRequests.ReviseTemplate(key(), copy.version(), 1, "我的新流程", "", single()));
        assertThat(changed.revision()).isEqualTo(2);
        assertThat(templates.get(copy.id(), 1).graph()).isEqualTo(chain());
        assertThat(plans.get(requirement.id(), null).graph()).isEqualTo(chain());
        templates.archive(copy.id(), new WorkflowRequests.VersionCommand(key(), changed.version()));
        assertThat(templates.list("", "CUSTOM", null, 50).items()).isEmpty();
        assertThat(plans.get(requirement.id(), null).sourceRevision()).isEqualTo(1);
        assertThat(templates.get(copy.id(), 1).archived()).isTrue();
        assertThatThrownBy(() -> plans.create(new WorkflowRequests.CreateRequirement(key(), project, "新任务", "目标", copy.id(), 2)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE workflow_template_revision SET sha256=? WHERE template_id=?", "0".repeat(64), copy.id()))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM workflow_template_revision WHERE template_id=?", copy.id()))
                .hasMessageContaining("retained");
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void confirmationDoesNotStartExecutionAndEditingRequiresFreshConfirmation() {
        var template = create("我的流程");
        var request = new WorkflowRequests.CreateRequirement(key(), project, "需求", "实际目标", template.id(), 1);
        var created = plans.create(request);
        assertThat(plans.create(request)).isEqualTo(created);
        assertThat(created.state()).isEqualTo("PLANNING");
        var confirmation = new WorkflowRequests.VersionCommand(key(), created.version());
        var confirmed = plans.confirm(created.id(), confirmation);
        assertThat(plans.confirm(created.id(), confirmation)).isEqualTo(confirmed);
        assertThat(confirmed.state()).isEqualTo("PENDING_START");
        for (String table : List.of("task", "task_queue", "workspace_lease"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
        var updated = plans.revise(created.id(), new WorkflowRequests.RevisePlan(key(), confirmed.version(), 1, chain()));
        assertThat(updated.state()).isEqualTo("PLANNING");
        assertThat(updated.revision()).isEqualTo(2);
        assertThat(plans.get(created.id(), 1).graph()).isEqualTo(single());
        assertThat(templates.get(template.id(), null).graph()).isEqualTo(single());
        assertThatThrownBy(() -> plans.confirm(created.id(), new WorkflowRequests.VersionCommand(key(), confirmed.version())))
                .isInstanceOf(ConflictException.class);
        assertThat(jdbc.queryForList("SELECT to_state FROM state_transition_event WHERE machine_type='WORKFLOW_REQUIREMENT' ORDER BY sequence",
                String.class)).containsExactly("PLANNING", "PENDING_START", "PLANNING");
    }
    @Test void movingCanvasUsesItsOwnVersionAndDoesNotChangeTheDefinitionOrConfirmation() {
        var template = create("布局");
        var moved = new WorkflowRequests.Layout(key(), 1, 0, new CanvasLayout(Map.of("first", new CanvasLayout.Point(120, -80)), 5, 6, 1.5));
        var saved = templates.layout(template.id(), moved);
        assertThat(saved.version()).isEqualTo(template.version());
        assertThat(saved.revision()).isEqualTo(1);
        assertThat(saved.layoutVersion()).isEqualTo(1);
        assertThat(templates.layout(template.id(), moved)).isEqualTo(saved);
        assertThatThrownBy(() -> templates.layout(template.id(), new WorkflowRequests.Layout(key(), 1, 0, CanvasLayout.empty())))
                .isInstanceOf(ConflictException.class);
        var created = plans.create(new WorkflowRequests.CreateRequirement(key(), project, "需求", "目标", template.id(), 1));
        var confirmed = plans.confirm(created.id(), new WorkflowRequests.VersionCommand(key(), created.version()));
        var plannedLayout = plans.layout(created.id(), new WorkflowRequests.Layout(key(), 1, 0, CanvasLayout.empty()));
        assertThat(plannedLayout.version()).isEqualTo(confirmed.version());
        assertThat(plannedLayout.state()).isEqualTo("PENDING_START");
    }
    @Test void sameRequestCannotChangePayloadAndStaleEditsCannotReplaceNewerPlans() {
        var request = new WorkflowRequests.CreateTemplate(key(), "名称", "", single(), null);
        var first = templates.create(request);
        assertThat(templates.create(request)).isEqualTo(first);
        assertThatThrownBy(() -> templates.create(new WorkflowRequests.CreateTemplate(request.requestKey(), "不同名称", "", single(), null)))
                .isInstanceOf(ConflictException.class);
        templates.revise(first.id(), new WorkflowRequests.ReviseTemplate(key(), 0, 1, "新版", "", chain()));
        assertThatThrownBy(() -> templates.revise(first.id(), new WorkflowRequests.ReviseTemplate(key(), 0, 1, "过期版本", "", single())))
                .isInstanceOf(ConflictException.class);
        assertThat(templates.get(first.id(), null).graph()).isEqualTo(chain());
    }
    @Test void aFailedAcknowledgementRollsBackPlanRevisionAndLifecycleAudit() {
        var template = create("回滚");
        int before = jdbc.queryForObject("SELECT count(*) FROM state_transition_event", Integer.class);
        jdbc.execute("CREATE TRIGGER refuse_workflow_ack BEFORE INSERT ON workflow_command BEGIN SELECT RAISE(ABORT,'ack failed'); END");
        try {
            assertThatThrownBy(() -> plans.create(new WorkflowRequests.CreateRequirement(key(), project, "需求", "目标", template.id(), 1)))
                    .hasStackTraceContaining("ack failed");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_requirement", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_plan_revision", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM state_transition_event", Integer.class)).isEqualTo(before);
        } finally { jdbc.execute("DROP TRIGGER refuse_workflow_ack"); }
    }
    @Test void summariesArePagedAndNeverReturnBodiesAndDeletedTemplatesStayOutOfLists() {
        var first = create("查找一"); var second = create("查找二"); create("别的");
        var page = templates.list("查找", "CUSTOM", null, 1);
        assertThat(page.items()).hasSize(1); assertThat(page.nextCursor()).isNotBlank();
        var next = templates.list("查找", "CUSTOM", page.nextCursor(), 1);
        assertThat(next.items()).hasSize(1);
        assertThat(List.of(page.items().getFirst().id(), next.items().getFirst().id())).containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(encoding.encode(page)).doesNotContain("graph", "definitionJson", "layoutJson", "按需求完成");
        assertThatThrownBy(() -> templates.list("", "CUSTOM", "bad-cursor", 50)).isInstanceOf(BadRequestException.class);
    }
    @Test void incompletePlansCanBeSavedButCannotBeConfirmedAndCancellationRetainsHistory() {
        var blank = templates.create(new WorkflowRequests.CreateTemplate(key(), "草稿", "", WorkflowGraph.empty(), null));
        var plan = plans.create(new WorkflowRequests.CreateRequirement(key(), project, "待补充需求", "目标", blank.id(), 1));
        assertThatThrownBy(() -> plans.confirm(plan.id(), new WorkflowRequests.VersionCommand(key(), plan.version())))
                .isInstanceOf(BadRequestException.class);
        var cancelled = plans.cancelPlanning(plan.id(), new WorkflowRequests.VersionCommand(key(), plan.version()));
        assertThat(cancelled.state()).isEqualTo("CANCELLED");
        assertThat(plans.get(plan.id(), 1).graph()).isEqualTo(WorkflowGraph.empty());
        assertThatThrownBy(() -> plans.revise(plan.id(), new WorkflowRequests.RevisePlan(key(), cancelled.version(), 1, single())))
                .isInstanceOf(ConflictException.class);
    }

    @Test void builtinPublishingIsRepeatableAndUpdatesNeverRewriteCustomCopiesOrRequirementSnapshots() {
        builtins.publish();var first=templates.get("builtin.workflow.design",null);assertThat(first.diagnostics()).isEmpty();
        var copied=templates.copy(first.id(),new WorkflowRequests.CopyTemplate(key(),first.revision(),"我的需求设计"));
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"需求","目标",first.id(),first.revision()));
        builtins.publish();assertThat(templates.get(first.id(),null)).isEqualTo(first);
        var graph=first.graph();var source=graph.nodes().getFirst();var changed=new WorkflowGraph.Node(source.id(),"新版澄清",source.kind(),source.moduleId(),source.moduleVersion(),source.roleId(),source.task(),source.inputs(),source.outputs(),source.outcomes(),source.completion(),source.maxRetries(),source.pauseAfter(),source.parameters(),source.roleRevisionId());
        var nodes=new ArrayList<>(graph.nodes());nodes.set(0,changed);var updated=new WorkflowGraph(1,nodes,graph.edges(),graph.inputs());
        templates.installBuiltin(first.id(),first.title(),first.description(),updated,first.layout());
        assertThat(templates.get(first.id(),null).revision()).isEqualTo(2);assertThat(templates.get(first.id(),1).graph()).isEqualTo(first.graph());
        assertThat(templates.get(copied.id(),null).graph()).isEqualTo(first.graph());assertThat(plans.get(owner.id(),null).graph()).isEqualTo(first.graph());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM role_owner_snapshot",Integer.class)).isZero();
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void presetReadsArePagedAndResolveExplicitRolesWithoutStartingOrFreezingWork() {
        var first=presets.list("",null,2);var page=first;var ids=new ArrayList<String>();
        while(true){page.items().forEach(item->ids.add(item.id()));if(page.nextCursor()==null)break;page=presets.list("",page.nextCursor(),2);}
        assertThat(ids).hasSize(45).doesNotHaveDuplicates().contains("review.snapshot","review.snapshot-full","snapshot.analyze","snapshot.review","snapshot.plan","snapshot.report");assertThat(encoding.encode(first)).doesNotContain("workInstructions","roleRevisionId","task", "outputs");
        assertThatThrownBy(()->presets.list("设计",first.nextCursor(),2)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->presets.get("design.plan",1)).isInstanceOf(ConflictException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM role_definition",Integer.class)).isZero();rolePublishing.seedBuiltin();
        for(String id:ids) { var preset=presets.get(id,1); if(preset.node().kind()==WorkflowGraph.NodeKind.WORK)assertThat(preset.node().roleRevisionId()).isNotBlank(); }
        assertThat(presets.get("design.plan",1).node().outputs()).extracting(output->output.kind()).contains(WorkflowGraph.DataKind.PLAN);
        assertThat(presets.get("development.implement",1).node().outputs()).extracting(output->output.kind()).contains(WorkflowGraph.DataKind.CODE);
        assertThat(presets.get("verification.command",1).node().roleId()).isNull();
        assertThat(presets.get("verification.command",1).node().parameters()).containsKey("commandVerification");
        assertThatThrownBy(()->presets.get("design.plan",2)).isInstanceOf(NotFoundException.class);
        for(String table:List.of("workflow_requirement","workflow_node_attempt","role_owner_snapshot","task","workspace_lease"))assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class)).isZero();
    }
    @Test void builtinUpdateFailureRollsBackHeaderAndKeepsTheOriginalRevision() {
        builtins.publish();var before=templates.get("builtin.workflow.analysis",null);
        jdbc.execute("CREATE TRIGGER fail_builtin_revision BEFORE INSERT ON workflow_template_revision WHEN NEW.revision>1 BEGIN SELECT RAISE(ABORT,'builtin test failure'); END");
        assertThatThrownBy(()->templates.installBuiltin(before.id(),"调整标题",before.description(),before.graph(),before.layout())).hasStackTraceContaining("builtin test failure");
        assertThat(templates.get(before.id(),null)).isEqualTo(before);assertThat(templates.list("", "BUILTIN",null,50).items()).hasSize(12);
    }
    private WorkflowCommands.Receipt create(String title) {
        return templates.create(new WorkflowRequests.CreateTemplate(key(), title, "", single(), null));
    }
    private static String key() { return UUID.randomUUID().toString(); }
}
