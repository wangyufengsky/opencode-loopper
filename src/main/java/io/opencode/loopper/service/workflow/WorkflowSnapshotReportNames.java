package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import io.opencode.loopper.workflow.*;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Allocate the visible report name once, before rendering, so recovery keeps its identity. */
@Service
@Transactional(readOnly=true)
public class WorkflowSnapshotReportNames {
    private final WorkflowSnapshotReportMapper mapper;
    private final LoopperProjectMapper projects;
    private final TemplateReportBundleMapper numbers;
    private final WorkflowSnapshotInputs inputs;
    public WorkflowSnapshotReportNames(WorkflowSnapshotReportMapper mapper,@org.springframework.beans.factory.annotation.Qualifier("loopperProjectMapper") LoopperProjectMapper projects,TemplateReportBundleMapper numbers,WorkflowSnapshotInputs inputs){this.mapper=mapper;this.projects=projects;this.numbers=numbers;this.inputs=inputs;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void begin(WorkflowExecutionRows.Attempt attempt,WorkflowGraph.Node node,WorkflowDelivery.Inputs snapshot,String project) {
        if(!WorkflowSnapshotReport.MODULE.equals(node.moduleId()))return;
        WorkflowSnapshotReport.require(node);var source=inputs.source(snapshot);
        String name=projects.findProject(project).orElseThrow(WorkflowCommands::conflict).name();var base=names(name,source.manifest(),1);
        if(numbers.next(base.namespace())!=1)throw WorkflowCommands.conflict();var selected=names(name,source.manifest(),numbers.current(base.namespace()));
        if(mapper.insert(new WorkflowSnapshotReportMapper.Bundle(attempt.id(),selected.namespace(),selected.sequence(),name,source.source().sha256(),selected.folder(),selected.main()))!=1)throw WorkflowCommands.conflict();
    }
    public WorkflowSnapshotReportMapper.Bundle require(String attempt,WorkflowSnapshotInputs.Source source){var row=mapper.bundle(attempt).orElseThrow(WorkflowCommands::conflict);if(!row.sourceSha256().equals(source.source().sha256()))throw WorkflowCommands.conflict();return row;}
    public static TemplateReportNames names(String project,WorkflowReviewSource.Manifest source,long sequence){return new TemplateReportNames(source.baselineSha()==null?"代码审查-全面":"代码审查-日期增量",project,start(source),end(source),sequence);}
    public static String start(WorkflowReviewSource.Manifest source){return source.startDate()==null?Instant.parse(source.capturedAt()).atZone(TemplateDateRange.ZONE).toLocalDate().toString():source.startDate();}
    public static String end(WorkflowReviewSource.Manifest source){return source.endDate()==null?start(source):source.endDate();}
}
