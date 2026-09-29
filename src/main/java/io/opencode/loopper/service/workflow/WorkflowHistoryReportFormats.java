package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import io.opencode.loopper.workflow.*;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Layout is fixed at node confirmation; persistent report numbering is allocated before rendering. */
@Service
@Transactional(readOnly=true)
public class WorkflowHistoryReportFormats {
    private final WorkflowHistoryReportMapper mapper;
    private final LoopperProjectMapper projects;
    private final TemplateReportBundleMapper numbers;
    private final WorkflowHistoryInputs inputs;
    private final WorkflowEncoding encoding;
    private final TemplateReportLayout.Frozen review=TemplateReportLayout.freezeHistory(),contribution=TemplateReportLayout.freeze();
    public WorkflowHistoryReportFormats(WorkflowHistoryReportMapper mapper,@org.springframework.beans.factory.annotation.Qualifier("loopperProjectMapper") LoopperProjectMapper projects,TemplateReportBundleMapper numbers,WorkflowHistoryInputs inputs,WorkflowEncoding encoding){this.mapper=mapper;this.projects=projects;this.numbers=numbers;this.inputs=inputs;this.encoding=encoding;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void freeze(WorkflowExecutionRows.Node node,WorkflowGraph.Node definition,WorkflowExecutionRows.Node previous) {
        if(!WorkflowHistoryReport.MODULE.equals(definition.moduleId()))return;
        WorkflowHistoryReport.require(definition);var kind=WorkflowHistoryReport.kind(definition);
        var old=previous==null?null:mapper.format(previous.id()).orElse(null);
        String body=old!=null&&old.kind().equals(kind.name())?checked(old):encoding.encode(kind==TemplateTaskDefinition.CODE_REVIEW?review:contribution);
        if(mapper.insertFormat(new WorkflowHistoryReportMapper.Format(node.id(),kind.name(),body,WorkflowEncoding.hash(body),Instant.now().toString()))!=1)throw WorkflowCommands.conflict();
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void begin(WorkflowExecutionRows.Attempt attempt,WorkflowGraph.Node definition,WorkflowDelivery.Inputs snapshot,String project) {
        if(!WorkflowHistoryReport.MODULE.equals(definition.moduleId()))return;
        var format=mapper.format(attempt.nodeRunId()).orElseThrow(WorkflowCommands::conflict);checked(format);
        if(!format.kind().equals(WorkflowHistoryReport.kind(definition).name()))throw WorkflowCommands.conflict();
        var source=inputs.read(snapshot);String name=projects.findProject(project).orElseThrow(WorkflowCommands::conflict).name();
        String type=format.kind().equals("CODE_REVIEW")?"历史提交审查":"项目贡献周报";
        var base=new TemplateReportNames(type,name,source.manifest().startDate(),source.manifest().endDate(),1);
        if(numbers.next(base.namespace())!=1)throw WorkflowCommands.conflict();
        var names=new TemplateReportNames(type,name,source.manifest().startDate(),source.manifest().endDate(),numbers.current(base.namespace()));
        var row=new WorkflowHistoryReportMapper.Bundle(attempt.id(),names.namespace(),names.sequence(),name,source.source().sha256(),names.folder(),names.main());
        if(mapper.insertBundle(row)!=1)throw WorkflowCommands.conflict();
    }
    public record Fixed(TemplateReportLayout.Frozen layout,WorkflowHistoryReportMapper.Bundle bundle){ }
    public Fixed require(WorkflowExecutionRows.Attempt attempt,WorkflowHistoryInputs.Context source) {
        var format=mapper.format(attempt.nodeRunId()).orElseThrow(WorkflowCommands::conflict);var bundle=mapper.bundle(attempt.id()).orElseThrow(WorkflowCommands::conflict);
        if(!bundle.sourceSha256().equals(source.source().sha256()))throw WorkflowCommands.conflict();
        return new Fixed(encoding.decode(checked(format),TemplateReportLayout.Frozen.class),bundle);
    }
    private String checked(WorkflowHistoryReportMapper.Format value){if(!WorkflowEncoding.hash(value.layoutJson()).equals(value.sha256()))throw WorkflowCommands.conflict();return value.layoutJson();}
}
