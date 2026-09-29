package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static io.opencode.loopper.workflow.WorkflowGraph.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.WorkflowPlanTemplateMapper;
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
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes=LoopperApplication.class,properties={"loopper.opencode.mode=fake","loopper.monitor-delay=1h","loopper.designer-monitor-delay=1h"})
class WorkflowPlanTemplatesIntegrationTest {
    @Autowired Flyway flyway;
    @Autowired WorkflowPlanTemplates exports;
    @Autowired WorkflowPlans plans;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowNodeActions actions;
    @Autowired WorkflowPlanTemplateMapper origins;
    @Autowired WorkflowEncoding encoding;
    @Autowired ProjectService projects;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @TempDir Path directory;
    String project,id;
    @BeforeEach void prepare()throws Exception {
        flyway.clean();flyway.migrate();project=projects.create("模板回用",Files.createDirectory(directory.resolve("project")).toString(),"test").id();
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"模板","",graph("原步骤"),CanvasLayout.empty()));
        id=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"原需求","本次独有目标",template.id(),1)).id();
    }
    @Test void draftExportPreservesUnsavedConfigurationWithoutSavingTheSourcePlan() {
        var before=plans.get(id,null);var current=graph("未保存的新步骤");
        var selection=selection(WorkflowTemplateExport.Mode.CURRENT,current);var preview=exports.preview(id,selection);
        assertThat(preview.initialAvailable()).isFalse();assertThat(preview.graph()).isEqualTo(current);
        var saved=exports.save(id,save(selection,preview));
        assertThat(templates.get(saved.id(),null).graph()).isEqualTo(current);assertThat(plans.get(id,null)).isEqualTo(before);
        var origin=origins.source(saved.id()).orElseThrow();assertThat(origin.planRevision()).isEqualTo(1);
        assertThat(origin.mode()).isEqualTo("CURRENT");assertThat(origin.planSha256()).isEqualTo(plans.revision(id,1).sha256());
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_template_plan_source SET mode='INITIAL' WHERE template_id=?",saved.id())).hasStackTraceContaining("immutable");
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_template_plan_source WHERE template_id=?",saved.id())).hasStackTraceContaining("retained");
        assertThat(count("workflow_node_attempt")).isZero();assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void unknownAcknowledgementReplaysAfterPlanRevisionChangesButChangedPayloadAndStaleNewSavesFail() {
        var selection=selection(WorkflowTemplateExport.Mode.CURRENT,graph("原步骤"));var preview=exports.preview(id,selection);var command=save(selection,preview);
        var receipt=exports.save(id,command);plans.revise(id,new WorkflowRequests.RevisePlan(key(),plans.require(id).version(),1,graph("后改步骤")));
        assertThat(exports.save(id,command)).isEqualTo(receipt);assertThat(count("workflow_template_plan_source")).isEqualTo(1);
        assertThatThrownBy(()->exports.save(id,new WorkflowTemplateExport.Save(command.requestKey(),"不同标题","",selection,preview.sha256()))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->exports.save(id,save(selection,preview))).isInstanceOf(ConflictException.class);
    }
    @Test void previewMismatchAndReceiptFailureLeaveNoPartialTemplateOrOrigin() {
        var selection=selection(WorkflowTemplateExport.Mode.CURRENT,graph("原步骤"));var preview=exports.preview(id,selection);int before=count("workflow_template");
        assertThatThrownBy(()->exports.save(id,new WorkflowTemplateExport.Save(key(),"错误摘要","",selection,"0".repeat(64)))).isInstanceOf(ConflictException.class);
        jdbc.execute("CREATE TRIGGER fail_export_ack BEFORE INSERT ON workflow_command WHEN NEW.action='SAVE_PLAN' BEGIN SELECT RAISE(ABORT,'export rollback'); END");
        var command=save(selection,preview);
        assertThatThrownBy(()->exports.save(id,command)).hasStackTraceContaining("export rollback");
        assertThat(count("workflow_template")).isEqualTo(before);assertThat(count("workflow_template_plan_source")).isZero();
        jdbc.execute("DROP TRIGGER fail_export_ack");exports.save(id,command);assertThat(count("workflow_template")).isEqualTo(before+1);
    }
    @Test void firstExecutionUsesTheCustomizedPlanAndCompletedSourceValuesNeverBecomeNewTaskInputs() {
        assertThatThrownBy(()->exports.preview(id,selection(WorkflowTemplateExport.Mode.INITIAL,graph("原步骤")))).hasMessageContaining("尚未执行");
        plans.revise(id,new WorkflowRequests.RevisePlan(key(),plans.require(id).version(),1,graph("执行前定制")));
        plans.confirm(id,new WorkflowRequests.VersionCommand(key(),plans.require(id).version()));
        var input=new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("本次私有资料"));
        var run=actions.startHuman(id,"step",new WorkflowNodeActions.Start(key(),plans.require(id).version(),Map.of("brief",input)));
        var before=plans.get(id,null);var selection=selection(WorkflowTemplateExport.Mode.INITIAL,graph("本地草稿"));var preview=exports.preview(id,selection);
        assertThat(preview.sourceRevision()).isEqualTo(2);assertThat(preview.graph()).isEqualTo(graph("执行前定制"));
        var delivery=new WorkflowDelivery("本次成果",null,Map.of("result",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("只属于旧任务"))));
        actions.completeHuman(id,"step",new WorkflowNodeActions.Complete(key(),plans.require(id).version(),run.attemptId(),run.attemptVersion(),delivery));
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
        var saved=exports.save(id,save(selection,preview)); // Runtime version changes alone do not invalidate a definition preview.
        var fresh=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"新需求","新的目标",saved.id(),1));
        plans.confirm(fresh.id(),new WorkflowRequests.VersionCommand(key(),fresh.version()));
        assertThatThrownBy(()->actions.startHuman(fresh.id(),"step",new WorkflowNodeActions.Start(key(),plans.require(fresh.id()).version(),null))).isInstanceOf(ConflictException.class);
        assertThat(encoding.encode(templates.get(saved.id(),null))).doesNotContain("本次私有资料","只属于旧任务","本次独有目标",run.attemptId());
        assertThat(count("workflow_node_attempt")).isEqualTo(1);assertThat(count("workflow_node_delivery")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_input_snapshot WHERE requirement_id=?",Integer.class,fresh.id())).isZero();
        assertThat(plans.get(id,null).graph()).isEqualTo(before.graph());
    }
    private WorkflowTemplateExport.PreviewRequest selection(WorkflowTemplateExport.Mode mode,WorkflowGraph graph) {
        return new WorkflowTemplateExport.PreviewRequest(plans.require(id).headRevision(),mode,graph,CanvasLayout.empty());
    }
    private WorkflowTemplateExport.Save save(WorkflowTemplateExport.PreviewRequest selection,WorkflowTemplateExport.Preview preview) {
        return new WorkflowTemplateExport.Save(key(),"复用流程","可配置的流程",selection,preview.sha256());
    }
    private WorkflowGraph graph(String title) {
        var node=new Node("step",title,NodeKind.HUMAN,null,0,null,"检查资料",List.of(new Input("brief",InputSource.REQUIREMENT,"brief",null,DataKind.TEXT,true)),
                List.of(new Output("result","检查结果",DataKind.TEXT,true)),List.of(),new Completion(CompletionKind.HUMAN,"人工确认",null),0,false,Map.of());
        return new WorkflowGraph(1,List.of(node),List.of(),List.of(new PublicInput("brief","需求资料",DataKind.TEXT,true)));
    }
    private int count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
    private static String key(){return UUID.randomUUID().toString();}
}
