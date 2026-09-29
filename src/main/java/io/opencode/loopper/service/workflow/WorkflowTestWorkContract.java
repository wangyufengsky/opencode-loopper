package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.template.*;
import io.opencode.loopper.workflow.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Test-scenario coverage, immutable input lineage and independent role reading evidence. */
@Component
public final class WorkflowTestWorkContract {
    private final WorkflowTestInputs inputs;
    private final WorkflowSourceReadMapper reads;
    private final WorkflowEncoding encoding;
    public WorkflowTestWorkContract(WorkflowTestInputs inputs,WorkflowSourceReadMapper reads,WorkflowEncoding encoding){this.inputs=inputs;this.reads=reads;this.encoding=encoding;}
    public record Context(WorkflowTestInputs.Context inputs,List<String> paths,WorkflowTestDesign.Frozen design){ }
    public Context context(WorkflowGraph.Node node,WorkflowDelivery.Inputs values) {
        try{if(WorkflowTestWrite.supports(node.moduleId()))WorkflowTestWrite.require(node);else WorkflowTestDesign.require(node);}catch(IllegalArgumentException invalid){throw invalid(invalid.getMessage());}
        var fixed=inputs.require(values);var design=WorkflowTestWrite.supports(node.moduleId())?inputs.design(values,fixed):null;
        var paths=design==null?WorkflowSourceTargets.select(node,fixed.manifest(),encoding):design.design().scenarios().stream().map(WorkflowTestDesign.Scenario::path).distinct().toList();
        var configured=fixed.frozen().profile().modules().stream().flatMap(m->m.sourcePaths().stream()).toList();
        if(!configured.containsAll(paths))throw invalid("测试配置没有覆盖本批目标源码。");
        return new Context(fixed,paths,design);
    }
    public WorkflowDelivery accept(WorkflowExecutionRows.Attempt attempt,WorkflowGraph.Node node,WorkflowDelivery.Inputs values,WorkflowDelivery delivery) {
        var context=context(node,values);var input=context.inputs().profile();String text=encoding.encode(input.content());int next=0;
        for(var read:reads.inputs(attempt.id(),"profile")) {
            if(!read.sha256().equals(WorkflowEncoding.hash(text))||read.totalLength()!=text.length()||read.startOffset()>next)break;
            next=Math.max(next,read.endOffset());
        }
        if(next<text.length())throw invalid("请完整读取本次固定测试配置，再提交单测场景设计。");
        WorkflowTestDesign.Candidate candidate;
        try{candidate=encoding.decode(encoding.encode(delivery.outputs().get("design").content()),WorkflowTestDesign.Candidate.class);}
        catch(tools.jackson.core.JacksonException malformed){throw invalid("请按工作信息提交完整的单测场景结构。");}
        validate(candidate,context.paths(),path->reads.sources(attempt.id(),"source",path).stream()
                .map(r->new SourceDesign.Read(r.sha256(),r.startLine(),r.endLine(),r.totalLines(),r.content())).toList());
        var fixed=context.inputs();var result=new WorkflowTestDesign.Frozen(1,WorkflowTestDesign.TYPE,fixed.source().attemptId(),fixed.frozen().source(),fixed.profile().attemptId(),WorkflowEncoding.hash(text),candidate);
        var outputs=new LinkedHashMap<>(delivery.outputs());outputs.put("design",new WorkflowDelivery.Value(WorkflowGraph.DataKind.JSON,encoding.decode(encoding.encode(result),tools.jackson.databind.JsonNode.class)));
        return new WorkflowDelivery(delivery.summary(),delivery.outcome(),outputs);
    }
    public void writerCandidate(WorkflowExecutionRows.Attempt attempt,WorkflowGraph.Node node,WorkflowDelivery.Inputs values) {
        var context=context(node,values);
        for(String name:List.of("profile","design")) {
            var input=values.values().stream().filter(v->v.name().equals(name)).findFirst().orElseThrow(WorkflowCommands::conflict);String text=encoding.encode(input.content());int next=0;
            for(var read:reads.inputs(attempt.id(),name)){if(!read.sha256().equals(WorkflowEncoding.hash(text))||read.totalLength()!=text.length()||read.startOffset()>next)break;next=Math.max(next,read.endOffset());}
            if(next<text.length())throw invalid("请完整读取固定测试配置和场景设计，再提交代码工作说明。");
        }
        SourceDesignValidation.fullReads(context.paths(),path->reads.sources(attempt.id(),"source",path).stream().map(r->new SourceDesign.Read(r.sha256(),r.startLine(),r.endLine(),r.totalLines(),r.content())).toList());
    }
    private static void validate(WorkflowTestDesign.Candidate value,List<String> paths,java.util.function.Function<String,List<SourceDesign.Read>> reads) {
        if(value==null)throw invalid("请提交完整的单测场景设计。");text(value.title(),200);text(value.summary(),4000);
        if(value.scenarios()==null||value.scenarios().isEmpty()||value.scenarios().size()>64)throw invalid("每批需要 1–64 个具体测试场景，过大时请拆分批次。");
        var keys=new HashSet<String>();var covered=new HashSet<String>();
        for(var scenario:value.scenarios()) {
            if(scenario==null||scenario.key()==null||!scenario.key().matches("[A-Za-z0-9_-]{1,80}")||!keys.add(scenario.key()))throw invalid("测试场景编号必须唯一，仅使用字母、数字、下划线和连字符。");
            if(!paths.contains(scenario.path())||!Set.of("NORMAL","BOUNDARY","ERROR","BRANCH").contains(Objects.toString(scenario.category(),"")))throw invalid("场景必须对应本批源码，类型为正常、边界、异常或关键分支。");
            text(scenario.title(),200);text(scenario.expected(),4000);
            if(scenario.steps()==null||scenario.steps().isEmpty()||scenario.steps().size()>24)throw invalid("每个场景需要 1–24 条可执行的准备与测试步骤。");
            scenario.steps().forEach(step->text(step,2000));
            SourceDesignValidation.references(scenario.references(),List.of(scenario.path()),reads,"/scenarios/references");covered.add(scenario.path());
        }
        if(!covered.equals(new HashSet<>(paths)))throw invalid("测试场景必须覆盖本批全部源码，不能遗漏目标文件。");
        SourceDesignValidation.fullReads(paths,reads);
        if(value.limitations()==null||value.limitations().size()>32)throw invalid("请提供局限与待确认事项，最多 32 项。");
        value.limitations().forEach(item->text(item,2000));
    }
    public Map<String,Object> work(WorkflowGraph.Node node,WorkflowDelivery.Inputs values) {
        var context=context(node,values);
        if(WorkflowTestWrite.supports(node.moduleId()))return Map.of("paths",context.paths(),"files",context.inputs().manifest().files().stream().filter(f->context.paths().contains(f.path())).toList(),
                "instructions","完整读取 profile 和 design 两份输入及全部本批源码，再按场景补齐测试。源码使用 read_workflow_input_file {name:source,path,sha256,startLine:1,lineCount:200} 按 nextLine 读取。只在冻结原生测试与夹具目录工作，不改业务源码、构建配置、依赖或 Git 设置；不得删除已有文件、屏蔽测试或弱化已有断言。已有有效测试足够时可以没有代码差异。只提交 summary 文本交付，省略 code 与 scope，它们由程序在停止后生成；测试执行结果由后续独立验证节点负责。");
        return Map.of("paths",context.paths(),"files",context.inputs().manifest().files().stream().filter(f->context.paths().contains(f.path())).toList(),
                "candidateFields",Map.of("title","本批测试设计标题","summary","设计依据与范围","scenarios","[{key,path,category,title,steps,expected,references}]","limitations","局限与待确认事项字符串数组"),
                "categories",Map.of("NORMAL","正常","BOUNDARY","边界","ERROR","异常","BRANCH","关键分支"),
                "referenceFields",List.of("path","sha256","startLine","endLine","quote"),
                "instructions","先通过 read_workflow_node_input 完整读取 profile 到 nextOffset=null；再用 read_workflow_input_file {name:source,path,sha256,startLine:1,lineCount:200} 按 nextLine 完整读取本批全部源码，并按需读取已有测试和依赖上下文。依据实际源码提出具体输入、步骤和可观察的期望，保留已有有效测试；引用必须逐字对应本角色实际读取的源码。仅提交场景设计，不填写来源身份、不改测试配置、不声称测试已执行。设计交付仍需后续人工检查和真实测试证据。");
    }
    private static void text(String value,int max){if(value==null||value.isBlank()||value.length()>max||value.chars().anyMatch(c->c==0))throw invalid("场景文本不能为空或超过字段长度上限。");}
    private static BadRequestException invalid(String message){return new BadRequestException("WORKFLOW_TEST_DESIGN_INVALID",message);}
}
