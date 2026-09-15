package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Allocates one durable user-facing identity per report attempt, independently of filesystem retries. */
@Service
public class TemplateReportBundleService {
    private final TemplateReportBundleMapper bundles;
    private final LoopperMapper mapper;
    public TemplateReportBundleService(TemplateReportBundleMapper bundles, LoopperMapper mapper) { this.bundles = bundles; this.mapper = mapper; }

    @Transactional
    public TemplateReportBundleRow prepare(TaskRow task, AttemptRow attempt, TemplateTaskDefinition definition, TemplateGitEvidence snapshot) {
        String project = mapper.findProject(task.projectId()).orElseThrow().name();
        return prepareNamed(task, attempt, TemplateReportNames.of(definition, project, snapshot, 1).type(), snapshot.startDate(), snapshot.endDate());
    }
    @Transactional
    public TemplateReportBundleRow prepareNamed(TaskRow task, AttemptRow attempt, String type, String start, String end) {
        var previous = bundles.find(task.id(), attempt.id());
        if (previous.isPresent()) return previous.get();
        var current = mapper.findAttempt(attempt.id()).orElseThrow();
        if (!current.taskId().equals(task.id()) || !current.state().equals("RUNNING")
                || !mapper.findTask(task.id()).orElseThrow().state().equals("RUNNING"))
            throw new ConflictException("TEMPLATE_ARTIFACT_OWNER_CHANGED", "报告生成期间任务状态已改变");
        String project = mapper.findProject(task.projectId()).orElseThrow().name();
        String namespace = new TemplateReportNames(type, project, start, end, 1).namespace();
        if (bundles.next(namespace) != 1) throw new ConflictException("TEMPLATE_REPORT_SEQUENCE_CONFLICT", "报告编号分配冲突，请重试");
        long sequence = bundles.current(namespace);
        var names = new TemplateReportNames(type, project, start, end, sequence);
        var row = new TemplateReportBundleRow(attempt.id(), task.id(), namespace, sequence, project, names.folder(), names.main());
        if (bundles.insert(row) != 1) throw new ConflictException("TEMPLATE_REPORT_SEQUENCE_CONFLICT", "报告编号保存冲突，请重试");
        return row;
    }
    public TemplateReportBundleRow require(String taskId, String attemptId) {
        return bundles.find(taskId, attemptId).orElseThrow(() -> new NotFoundException("报告目录记录不存在"));
    }
}
