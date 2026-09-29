package io.opencode.loopper.service.workflow;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.persistence.WorkflowRows;
import io.opencode.loopper.persistence.WorkflowTemplateMapper;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.opencode.loopper.service.workflow.WorkflowCommands.conflict;

/** Versioned reusable definitions. Archiving hides a custom template without deleting its history. */
@Service
public class WorkflowTemplates {
    private final WorkflowTemplateMapper mapper;
    private final WorkflowEncoding encoding;
    private final WorkflowCommands commands;
    public WorkflowTemplates(WorkflowTemplateMapper mapper, WorkflowEncoding encoding, WorkflowCommands commands) {
        this.mapper = mapper; this.encoding = encoding; this.commands = commands;
    }
    public record Detail(String id, String title, String description, boolean builtin, boolean archived, int revision,
                         int headRevision, long version, long layoutVersion, WorkflowGraph graph, CanvasLayout layout,
                         String sourceTemplateId, Integer sourceRevision, List<WorkflowGraphValidator.Diagnostic> diagnostics) { }
    public CursorPage<WorkflowRows.TemplateSummary> list(String query, String kind, String cursor, Integer requestedLimit) {
        query = query == null ? "" : query.strip(); kind = kind == null ? "ALL" : kind;
        if (query.length() > 200 || !Set.of("ALL", "BUILTIN", "CUSTOM").contains(kind))
            throw new BadRequestException("WORKFLOW_FILTER_INVALID", "流程筛选条件无效");
        int limit = PageCursor.limit(requestedLimit); var after = PageCursor.decode(cursor);
        var rows = mapper.page(query, kind, after == null ? null : after.value(), after == null ? null : after.id(), limit + 1);
        var items = rows.stream().limit(limit).toList();
        var last = items.isEmpty() ? null : items.getLast();
        return new CursorPage<>(items, rows.size() > limit ? new PageCursor(last.createdAt(), last.id()).encode() : null);
    }
    public Detail get(String id, Integer revision) {
        var row = require(id);
        var snapshot = revision(id, revision == null ? row.headRevision() : revision);
        var graph = encoding.read(snapshot.definitionJson(), snapshot.sha256());
        return new Detail(id, row.title(), row.description(), row.builtin(), row.archived(), snapshot.revision(),
                row.headRevision(), row.version(), row.layoutVersion(), graph, encoding.readLayout(row.layoutJson(), graph),
                row.sourceTemplateId(), row.sourceRevision(), encoding.diagnostics(graph));
    }
    @Transactional
    public WorkflowCommands.Receipt create(WorkflowRequests.CreateTemplate request) {
        String digest = encoding.digest("TEMPLATE_CREATE", null, request);
        var replay = commands.replay(request.requestKey(), digest); if (replay.isPresent()) return replay.get();
        var row = createDefinition(request.title(), request.description(), request.graph(), request.layout());
        return acknowledge(request.requestKey(), digest, "CREATE", row);
    }
    /** The caller owns the command receipt and any source audit in the same transaction. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public WorkflowRows.Template createDefinition(String title, String description, WorkflowGraph value, CanvasLayout layout) {
        var graph = encoding.definition(value);
        return insert(UUID.randomUUID().toString(), title, description, false, graph,
                encoding.layout(layout, graph.graph()), null, null);
    }
    @Transactional
    public WorkflowCommands.Receipt revise(String id, WorkflowRequests.ReviseTemplate request) {
        String digest = encoding.digest("TEMPLATE_REVISE", id, request);
        var replay = commands.replay(request.requestKey(), digest); if (replay.isPresent()) return replay.get();
        var row = editable(id); if (row.version() != request.expectedVersion() || row.headRevision() != request.expectedRevision()) throw conflict();
        var graph = encoding.definition(request.graph()); String now = Instant.now().toString();
        if (mapper.revise(id, row.version(), row.headRevision() + 1, encoding.title(request.title()), encoding.description(request.description()), now) != 1) throw conflict();
        if (mapper.insertRevision(new WorkflowRows.Revision(id, row.headRevision() + 1, graph.body(), graph.sha256(), now)) != 1) throw conflict();
        return acknowledge(request.requestKey(), digest, "REVISE", require(id));
    }
    @Transactional
    public WorkflowCommands.Receipt copy(String id, WorkflowRequests.CopyTemplate request) {
        String digest = encoding.digest("TEMPLATE_COPY", id, request);
        var replay = commands.replay(request.requestKey(), digest); if (replay.isPresent()) return replay.get();
        var source = available(id);
        var snapshot = revision(id, request.sourceRevision());
        var graph = encoding.definition(encoding.read(snapshot.definitionJson(), snapshot.sha256()));
        var row = insert(UUID.randomUUID().toString(), request.title(), source.description(), false, graph,
                encoding.layout(encoding.readLayout(source.layoutJson(), graph.graph()), graph.graph()), id, snapshot.revision());
        return acknowledge(request.requestKey(), digest, "COPY", row);
    }
    @Transactional
    public WorkflowCommands.Receipt archive(String id, WorkflowRequests.VersionCommand request) {
        String digest = encoding.digest("TEMPLATE_ARCHIVE", id, request);
        var replay = commands.replay(request.requestKey(), digest); if (replay.isPresent()) return replay.get();
        var row = editable(id);
        if (row.version() != request.expectedVersion() || mapper.archive(id, row.version(), Instant.now().toString()) != 1) throw conflict();
        return acknowledge(request.requestKey(), digest, "ARCHIVE", require(id));
    }
    @Transactional
    public WorkflowCommands.Receipt layout(String id, WorkflowRequests.Layout request) {
        String digest = encoding.digest("TEMPLATE_LAYOUT", id, request);
        var replay = commands.replay(request.requestKey(), digest); if (replay.isPresent()) return replay.get();
        var row = editable(id);
        var snapshot = revision(id, row.headRevision());
        String body = encoding.layout(request.layout(), encoding.read(snapshot.definitionJson(), snapshot.sha256()));
        if (mapper.layout(id, request.expectedRevision(), request.expectedLayoutVersion(), body) != 1) throw conflict();
        return acknowledge(request.requestKey(), digest, "LAYOUT", require(id));
    }
    /** Built-in publication is internal; public mutations always require a custom template. */
    @Transactional
    public void installBuiltin(String id, String title, String description, WorkflowGraph value, CanvasLayout layout) {
        var graph = encoding.definition(value);
        var existing = mapper.find(id);
        if (existing.isPresent()) {
            var old=existing.get();
            if (!old.builtin() || old.archived())throw new ConflictException("WORKFLOW_BUILTIN_CONFLICT", "内置流程身份与现有记录冲突");
            String body=encoding.layout(layout,graph.graph()),checkedTitle=encoding.title(title),checkedDescription=encoding.description(description);
            if(revision(id,old.headRevision()).sha256().equals(graph.sha256()) && old.title().equals(checkedTitle)
                    && old.description().equals(checkedDescription) && old.layoutJson().equals(body))return;
            int next=old.headRevision()+1;String now=Instant.now().toString();
            if(mapper.publishBuiltin(id,old.version(),next,checkedTitle,checkedDescription,body,now)!=1
                    || mapper.insertRevision(new WorkflowRows.Revision(id,next,graph.body(),graph.sha256(),now))!=1)throw conflict();
            return;
        }
        insert(id, title, description, true, graph, encoding.layout(layout, graph.graph()), null, null);
    }
    public WorkflowRows.Template available(String id) {
        var row = require(id); if (row.archived()) throw new NotFoundException("该流程已删除，请选择其他流程"); return row;
    }
    public WorkflowRows.Revision revision(String id, int revision) {
        return mapper.revision(id, revision).orElseThrow(() -> new NotFoundException("流程版本不存在"));
    }
    private WorkflowRows.Template require(String id) {
        return mapper.find(id).orElseThrow(() -> new NotFoundException("流程不存在"));
    }
    private WorkflowRows.Template editable(String id) {
        var row = available(id);
        if (row.builtin()) throw new ConflictException("BUILTIN_WORKFLOW_READ_ONLY", "内置流程只能查看或复制，请先创建副本");
        return row;
    }
    private WorkflowRows.Template insert(String id, String title, String description, boolean builtin,
            WorkflowEncoding.Definition graph, String layout, String source, Integer sourceRevision) {
        String now = Instant.now().toString();
        var row = new WorkflowRows.Template(id, encoding.title(title), encoding.description(description), builtin,
                false, 1, 0, layout, 0, source, sourceRevision, now, now);
        if (mapper.insert(row) != 1 || mapper.insertRevision(new WorkflowRows.Revision(id, 1, graph.body(), graph.sha256(), now)) != 1) throw conflict();
        return row;
    }
    private WorkflowCommands.Receipt acknowledge(String key, String digest, String action, WorkflowRows.Template row) {
        return commands.record(key, digest, "TEMPLATE", action, new WorkflowCommands.Receipt(row.id(), row.headRevision(),
                row.version(), row.layoutVersion(), row.archived() ? "ARCHIVED" : "ACTIVE"));
    }
}
