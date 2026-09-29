package io.opencode.loopper.service.workflow;

import static io.opencode.loopper.workflow.WorkflowGraph.*;
import static io.opencode.loopper.workflow.WorkflowTemplateExport.*;
import io.opencode.loopper.persistence.WorkflowPlanTemplateMapper;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Preview and atomically create a reusable definition without changing the source requirement. */
@Service
public class WorkflowPlanTemplates {
    private final WorkflowPlans plans;
    private final WorkflowTemplates templates;
    private final WorkflowPlanTemplateMapper mapper;
    private final WorkflowEncoding encoding;
    private final WorkflowCommands commands;
    public WorkflowPlanTemplates(WorkflowPlans plans, WorkflowTemplates templates, WorkflowPlanTemplateMapper mapper,
                                 WorkflowEncoding encoding, WorkflowCommands commands) {
        this.plans=plans; this.templates=templates; this.mapper=mapper; this.encoding=encoding; this.commands=commands;
    }
    @Transactional(readOnly=true)
    public Preview preview(String id, PreviewRequest request) {
        if(request==null || request.mode()==null)throw new BadRequestException("WORKFLOW_TEMPLATE_MODE_REQUIRED","请选择保存当前步骤或首次执行时的结构。");
        var owner=plans.require(id);
        if(owner.headRevision()!=request.expectedRevision())throw WorkflowCommands.conflict();
        var first=mapper.firstExecutionRevision(id);
        int revision=request.mode()==Mode.CURRENT?owner.headRevision():first.orElseThrow(() ->
                new BadRequestException("WORKFLOW_TEMPLATE_INITIAL_UNAVAILABLE","本任务尚未执行，请先保存当前步骤。"));
        var source=plans.revision(id,revision);
        var graph=request.mode()==Mode.CURRENT?request.graph():encoding.read(source.definitionJson(),source.sha256());
        encoding.definition(graph);
        var fixed=new ArrayList<String>();
        if(request.mode()==Mode.CURRENT)graph=fixedSteps(graph,mapper.appliedPlanners(id,revision),fixed);
        var definition=encoding.definition(graph);
        var requestedLayout=request.mode()==Mode.CURRENT?request.layout():encoding.readLayout(owner.layoutJson(),graph);
        var layout=encoding.decode(encoding.layout(requestedLayout,graph),CanvasLayout.class);
        String digest=encoding.digest("PLAN_TEMPLATE_PREVIEW",id,List.of(request.mode(),revision,definition.sha256(),layout));
        return new Preview(request.mode(),revision,first.isPresent(),graph,layout,List.copyOf(fixed),digest,
                encoding.diagnostics(graph));
    }
    @Transactional
    public WorkflowCommands.Receipt save(String id, Save request) {
        if(request==null)throw new BadRequestException("WORKFLOW_TEMPLATE_SAVE_REQUIRED","请提供模板名称与已预览的保存内容。");
        String digest=encoding.digest("PLAN_TEMPLATE_SAVE",id,request);
        var replay=commands.replay(request.requestKey(),digest);if(replay.isPresent())return replay.get();
        var preview=preview(id,request.selection());
        if(!preview.sha256().equals(request.previewSha256()))throw new ConflictException("WORKFLOW_TEMPLATE_PREVIEW_CHANGED","保存内容已变化，请重新预览后保存；当前草稿仍保留。");
        var row=templates.createDefinition(request.title(),request.description(),preview.graph(),preview.layout());
        var source=plans.revision(id,preview.sourceRevision());
        if(mapper.insert(new WorkflowPlanTemplateMapper.Source(row.id(),id,source.revision(),source.sha256(),
                encoding.definition(preview.graph()).sha256(),preview.mode().name(),Instant.now().toString()))!=1)throw WorkflowCommands.conflict();
        return commands.record(request.requestKey(),digest,"TEMPLATE","SAVE_PLAN",
                new WorkflowCommands.Receipt(row.id(),1,row.version(),row.layoutVersion(),"ACTIVE"));
    }
    private WorkflowGraph fixedSteps(WorkflowGraph graph, List<WorkflowPlanTemplateMapper.AppliedPlanner> applied, List<String> fixed) {
        var nodes=new ArrayList<>(graph.nodes());
        for(var planner:applied) {
            if(!WorkflowEncoding.hash(planner.definitionJson()).equals(planner.definitionSha256()))
                throw new ConflictException("WORKFLOW_SNAPSHOT_CORRUPT","分批节点的历史定义校验失败，已保留原记录。");
            var original=encoding.decode(planner.definitionJson(),Node.class);
            for(int i=0;i<nodes.size();i++) {
                var node=nodes.get(i);
                if(!node.id().equals(planner.nodeKey())||node.kind()!=NodeKind.SYSTEM||node.moduleVersion()!=original.moduleVersion()
                        ||!Objects.equals(node.moduleId(),original.moduleId()))continue;
                if(!node.outcomes().isEmpty())throw new BadRequestException("WORKFLOW_TEMPLATE_PLAN_OUTPUT_USED","分批节点已有自定义分支，请先调整分支，或选择首次执行时的结构。");
                if(graph.nodes().stream().flatMap(value->value.inputs().stream()).anyMatch(input->input.source()==InputSource.NODE&&node.id().equals(input.sourceId())))
                    throw new BadRequestException("WORKFLOW_TEMPLATE_PLAN_OUTPUT_USED","节点“"+node.title()+"”的规划输出仍被使用，请先调整这些输入，或选择首次执行时的结构。");
                String title="确认固定步骤 · "+node.title();if(title.length()>120)title=title.substring(0,120);
                nodes.set(i,new Node(node.id(),title,NodeKind.HUMAN,null,0,null,
                        "确认当前固定的后续步骤及具体路径、章节适合本次资料；需要调整时先修改计划。确认后继续执行。",
                        node.inputs(),List.of(new Output("result","确认意见",DataKind.TEXT,true)),List.of(),
                        new Completion(CompletionKind.HUMAN,"人工确认固定步骤适用于本次资料",null),node.maxRetries(),node.pauseAfter(),Map.of()));
                fixed.add(node.title());
            }
        }
        return new WorkflowGraph(graph.schemaVersion(),nodes,graph.edges(),graph.inputs());
    }
}
