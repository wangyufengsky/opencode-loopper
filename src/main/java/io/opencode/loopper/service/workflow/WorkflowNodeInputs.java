package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Resolves explicit data-flow bindings into exact accepted versions inside the admission transaction. */
@Service
public class WorkflowNodeInputs {
    private final WorkflowExecutionMapper mapper;
    private final WorkflowEncoding encoding;
    private final WorkflowUploadStore uploads;
    public WorkflowNodeInputs(WorkflowExecutionMapper mapper, WorkflowEncoding encoding, WorkflowUploadStore uploads) { this.mapper = mapper; this.encoding = encoding; this.uploads=uploads; }
    public record PublicValues(Map<String, WorkflowDelivery.Value> values) {
        public PublicValues { values = values == null ? Map.of() : Map.copyOf(values); }
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void freezePublic(WorkflowRows.Requirement owner, WorkflowGraph graph, Map<String, WorkflowDelivery.Value> provided) {
        var previous = mapper.inputs(owner.id(), owner.headRevision());
        if (previous.isPresent()) {
            verify(previous.get().contentJson(), previous.get().sha256());
            if (provided != null && !provided.isEmpty() && !previous.get().contentJson().equals(encoding.encode(new PublicValues(provided))))
                throw new ConflictException("WORKFLOW_INPUT_VERSION_CONFLICT", "本计划公共输入已经冻结，请通过新计划版本调整");
            return;
        }
        var values = new PublicValues(provided);
        var declared = new HashMap<String, WorkflowGraph.PublicInput>();
        for (var input : graph.inputs()) {
            declared.put(input.name(), input);
            if (input.required() && !values.values().containsKey(input.name())) throw unavailable(input.name());
        }
        values.values().forEach((name, value) -> {
            var input = declared.get(name);
            if (input == null || input.kind() != value.kind() || !WorkflowDeliveries.valid(value)) throw unavailable(name);
            if(value.kind()==WorkflowGraph.DataKind.DOCUMENT)uploads.reference(owner.id(),value.content());
        });
        String body = encoding.encode(values);
        if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2 * 1024 * 1024) throw unavailable("公共输入超过 2 MiB");
        if (mapper.insertInputs(new WorkflowExecutionRows.PublicInputs(owner.id(), owner.headRevision(), body,
                WorkflowEncoding.hash(body), Instant.now().toString())) != 1) throw WorkflowCommands.conflict();
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void carry(WorkflowRows.Requirement previous,WorkflowRows.Requirement next) {
        var snapshot=mapper.inputs(previous.id(),previous.headRevision());if(snapshot.isEmpty())return;
        var value=snapshot.get();verify(value.contentJson(),value.sha256());
        if(mapper.insertInputs(new WorkflowExecutionRows.PublicInputs(next.id(),next.headRevision(),value.contentJson(),value.sha256(),Instant.now().toString()))!=1)throw WorkflowCommands.conflict();
    }
    public WorkflowDelivery.Inputs resolve(WorkflowRows.Requirement owner, WorkflowGraph.Node node) {
        var publicRow = mapper.inputs(owner.id(), owner.headRevision()).orElseThrow(() -> unavailable("公共资料"));
        verify(publicRow.contentJson(), publicRow.sha256());
        var publicValues = encoding.decode(publicRow.contentJson(), PublicValues.class).values();
        var summaries = new HashMap<String, WorkflowExecutionRows.Summary>();
        mapper.summaries(owner.id(), owner.headRevision()).forEach(row -> summaries.put(row.nodeKey(), row));
        var selected = new LinkedHashSet<String>();
        for (var input : node.inputs()) if (input.source() == WorkflowGraph.InputSource.NODE) {
            var producer = summaries.get(input.sourceId());
            if (producer != null && producer.state().equals("SUCCEEDED")) selected.add(producer.latestAttemptId());
            else if (producer == null || input.required() || !producer.state().equals("SKIPPED")) throw unavailable(input.name());
        }
        var deliveries = new HashMap<String, WorkflowExecutionRows.Delivery>();
        if (!selected.isEmpty()) for (var row : mapper.deliveries(selected)) {
            verify(row.contentJson(), row.sha256()); deliveries.put(row.attemptId(), row);
        }
        var values = new ArrayList<WorkflowDelivery.Input>();
        for (var input : node.inputs()) {
            if (input.source() == WorkflowGraph.InputSource.REQUIREMENT) {
                var value = publicValues.get(input.sourceId());
                if (value == null) { if (input.required()) throw unavailable(input.name()); else continue; }
                requireType(input, value);
                values.add(new WorkflowDelivery.Input(input.name(), input.kind(), "REQUIREMENT", owner.id(), input.sourceId(), null,
                        publicRow.sha256(), value.content()));
            } else {
                var producer = summaries.get(input.sourceId());
                if (producer.state().equals("SKIPPED") && !input.required()) continue;
                var row = deliveries.get(producer.latestAttemptId());
                if (row == null) throw unavailable(input.name());
                var value = encoding.decode(row.contentJson(), WorkflowDelivery.class).outputs().get(input.output());
                if (value == null) { if (input.required()) throw unavailable(input.name()); else continue; }
                requireType(input, value);
                values.add(new WorkflowDelivery.Input(input.name(), input.kind(), "NODE", producer.id(), input.output(), row.attemptId(), row.sha256(), value.content()));
            }
        }
        return new WorkflowDelivery.Inputs(1, owner.id(), owner.headRevision(), node.id(), owner.objective(), values);
    }
    private static void requireType(WorkflowGraph.Input input, WorkflowDelivery.Value value) {
        if (input.kind() != value.kind() || !WorkflowDeliveries.valid(value)) throw unavailable(input.name());
    }
    private static void verify(String body, String sha) {
        if (!WorkflowEncoding.hash(body).equals(sha)) throw new ConflictException("WORKFLOW_INPUT_CORRUPT", "输入版本内容校验失败，已保留原记录");
    }
    private static ConflictException unavailable(String name) { return new ConflictException("WORKFLOW_INPUT_UNAVAILABLE", "节点输入尚不可用或与声明不一致：" + name); }
}
