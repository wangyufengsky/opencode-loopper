package io.opencode.loopper.service.workflow;

import static io.opencode.loopper.service.workflow.WorkflowCommands.conflict;
import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.domain.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Per-requirement immutable plan revisions. Confirmation performs no execution or workspace admission. */
@Service
public class WorkflowPlans {
    private final WorkflowPlanMapper mapper;
    private final WorkflowTemplates templates;
    private final WorkflowEncoding encoding;
    private final WorkflowCommands commands;
    private final ProjectService projects;
    private final LifecycleTransitionService lifecycle;
    private final WorkflowNodeRuns nodes;
    public WorkflowPlans(WorkflowPlanMapper mapper, WorkflowTemplates templates, WorkflowEncoding encoding,
            WorkflowCommands commands, ProjectService projects, LifecycleTransitionService lifecycle, WorkflowNodeRuns nodes) {
        this.mapper = mapper; this.templates = templates; this.encoding = encoding;
        this.commands = commands; this.projects = projects; this.lifecycle = lifecycle; this.nodes = nodes;
    }
    public record Detail(String id, String projectId, String title, String objective, WorkflowState state,
                         int revision, int headRevision, long version, long layoutVersion,
                         String sourceTemplateId, int sourceRevision, WorkflowGraph graph, CanvasLayout layout,
                         List<WorkflowGraphValidator.Diagnostic> diagnostics) { }
    public CursorPage<WorkflowRows.RequirementSummary> list(String project, String cursor, Integer requestedLimit) {
        if (project != null) projects.get(project);
        int limit = PageCursor.limit(requestedLimit); var after = PageCursor.decode(cursor);
        var rows = mapper.page(project, after == null ? null : after.value(), after == null ? null : after.id(), limit + 1);
        var items = rows.stream().limit(limit).toList(); var last = items.isEmpty() ? null : items.getLast();
        return new CursorPage<>(items, rows.size() > limit ? new PageCursor(last.createdAt(), last.id()).encode() : null);
    }
    public Detail get(String id, Integer revision) {
        var row = require(id);
        var snapshot = revision(id, revision == null ? row.headRevision() : revision);
        var graph = encoding.read(snapshot.definitionJson(), snapshot.sha256());
        return new Detail(id, row.projectId(), row.title(), row.objective(), WorkflowState.valueOf(row.state()),
                snapshot.revision(), row.headRevision(), row.version(), row.layoutVersion(), row.sourceTemplateId(),
                row.sourceRevision(), graph, encoding.readLayout(row.layoutJson(), graph),
                encoding.diagnostics(graph));
    }
    @Transactional
    public WorkflowCommands.Receipt create(WorkflowRequests.CreateRequirement request) {
        String digest = encoding.digest("REQUIREMENT_CREATE", null, request);
        var replay = commands.replay(request.requestKey(), digest); if (replay.isPresent()) return replay.get();
        projects.get(request.projectId());
        var template = templates.available(request.templateId());
        var snapshot = templates.revision(template.id(), request.templateRevision());
        var graph = encoding.read(snapshot.definitionJson(), snapshot.sha256());
        String now = Instant.now().toString(), id = UUID.randomUUID().toString();
        var row = new WorkflowRows.Requirement(id, request.projectId(), encoding.title(request.title()),
                encoding.objective(request.objective()), WorkflowState.PLANNING.name(), 1, 0, template.id(), snapshot.revision(),
                encoding.layout(encoding.readLayout(template.layoutJson(), graph), graph), 0, now, now);
        lifecycle.create(subject(row), row.state(), Map.of("templateId", template.id(), "templateRevision", snapshot.revision()),
                () -> mapper.insert(row), WorkflowCommands::conflict);
        if (mapper.insertRevision(new WorkflowRows.Revision(id, 1, snapshot.definitionJson(), snapshot.sha256(), now), "TEMPLATE", null) != 1) throw conflict();
        return acknowledge(request.requestKey(), digest, "CREATE", row);
    }
    @Transactional
    public WorkflowCommands.Receipt revise(String id, WorkflowRequests.RevisePlan request) {
        String digest = encoding.digest("PLAN_REVISE", id, request);
        var replay = commands.replay(request.requestKey(), digest); if (replay.isPresent()) return replay.get();
        var row = planning(id, request.expectedVersion());
        if (row.headRevision() != request.expectedRevision()) throw conflict();
        var graph = encoding.definition(request.graph());
        if (row.state().equals("PENDING_START")) { transition(row, WorkflowState.PLANNING, LifecycleEvent.UPDATE); row = require(id); }
        String now = Instant.now().toString(); int next = row.headRevision() + 1;
        if (mapper.revise(id, row.version(), next, now) != 1
                || mapper.insertRevision(new WorkflowRows.Revision(id, next, graph.body(), graph.sha256(), now), "USER", row.headRevision()) != 1) throw conflict();
        return acknowledge(request.requestKey(), digest, "REVISE", require(id));
    }
    @Transactional
    public WorkflowCommands.Receipt confirm(String id, WorkflowRequests.VersionCommand request) {
        String digest = encoding.digest("PLAN_CONFIRM", id, request);
        var replay = commands.replay(request.requestKey(), digest); if (replay.isPresent()) return replay.get();
        var row = planning(id, request.expectedVersion());
        if (!row.state().equals("PLANNING")) throw conflict();
        var snapshot = revision(id, row.headRevision());
        var issues = encoding.diagnostics(encoding.read(snapshot.definitionJson(), snapshot.sha256()));
        if (!issues.isEmpty()) throw new BadRequestException("WORKFLOW_NOT_READY", issues.getFirst().message() + "（" + issues.getFirst().path() + "）");
        nodes.initialize(row, encoding.read(snapshot.definitionJson(), snapshot.sha256()));
        transition(row, WorkflowState.PENDING_START, LifecycleEvent.CONFIRM);
        return acknowledge(request.requestKey(), digest, "CONFIRM", require(id));
    }
    @Transactional
    public WorkflowCommands.Receipt cancelPlanning(String id, WorkflowRequests.VersionCommand request) {
        String digest = encoding.digest("PLAN_CANCEL", id, request);
        var replay = commands.replay(request.requestKey(), digest); if (replay.isPresent()) return replay.get();
        var row = planning(id, request.expectedVersion());
        // This entry only accepts never-started requirements, so no external session can require stopping.
        transition(row, WorkflowState.STOPPING, LifecycleEvent.CANCEL);
        transition(require(id), WorkflowState.CANCELLED, LifecycleEvent.ABORT);
        return acknowledge(request.requestKey(), digest, "CANCEL", require(id));
    }
    @Transactional
    public WorkflowCommands.Receipt layout(String id, WorkflowRequests.Layout request) {
        String digest = encoding.digest("PLAN_LAYOUT", id, request);
        var replay = commands.replay(request.requestKey(), digest); if (replay.isPresent()) return replay.get();
        var row = require(id); var snapshot = revision(id, row.headRevision());
        String body = encoding.layout(request.layout(), encoding.read(snapshot.definitionJson(), snapshot.sha256()));
        if (mapper.layout(id, request.expectedRevision(), request.expectedLayoutVersion(), body) != 1) throw conflict();
        return acknowledge(request.requestKey(), digest, "LAYOUT", require(id));
    }
    public WorkflowRows.Requirement require(String id) {
        return mapper.find(id).orElseThrow(() -> new NotFoundException("需求任务不存在"));
    }
    public WorkflowRows.Revision revision(String id, int revision) {
        return mapper.revision(id, revision).orElseThrow(() -> new NotFoundException("需求计划版本不存在"));
    }
    private WorkflowRows.Requirement planning(String id, long version) {
        var row = require(id);
        if (row.version() != version) throw conflict();
        if (!Set.of("PLANNING", "PENDING_START").contains(row.state()))
            throw new ConflictException("WORKFLOW_EDIT_UNAVAILABLE", "当前需求不在开始前的规划阶段");
        return row;
    }
    private void transition(WorkflowRows.Requirement row, WorkflowState next, LifecycleEvent event) {
        lifecycle.transition(subject(row), row.state(), next.name(), event, null, Map.of(),
                () -> mapper.transition(row.id(), row.version(), row.state(), next.name(), Instant.now().toString()), WorkflowCommands::conflict);
    }
    private LifecycleTransitionService.Subject subject(WorkflowRows.Requirement row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.WORKFLOW_REQUIREMENT, row.id(), LifecycleScopeType.PROJECT, row.projectId());
    }
    private WorkflowCommands.Receipt acknowledge(String key, String digest, String action, WorkflowRows.Requirement row) {
        return commands.record(key, digest, "REQUIREMENT", action, new WorkflowCommands.Receipt(row.id(), row.headRevision(),
                row.version(), row.layoutVersion(), row.state()));
    }
}
