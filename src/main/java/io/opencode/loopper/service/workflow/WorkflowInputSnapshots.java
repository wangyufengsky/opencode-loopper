package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.WorkflowExecutionMapper;
import io.opencode.loopper.persistence.WorkflowExecutionRows.Attempt;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.workflow.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

/** Immutable input identities. Reading a reference never consults the current plan or latest attempt. */
@Component
public final class WorkflowInputSnapshots {
    private final WorkflowExecutionMapper mapper;
    private final WorkflowEncoding encoding;
    public WorkflowInputSnapshots(WorkflowExecutionMapper mapper, WorkflowEncoding encoding) {
        this.mapper = mapper; this.encoding = encoding;
    }
    public WorkflowDelivery.Inputs freeze(WorkflowDelivery.Inputs inputs) {
        validate(inputs);
        if (inputs.version() != 1) throw corrupt();
        var values = inputs.values().stream().map(input -> {
            if (!referencable(input)) return input;
            String body = encoding.encode(input.content());
            return new WorkflowDelivery.Input(input.name(), input.kind(), input.source(), input.sourceId(),
                    input.outputName(), input.attemptId(), input.sha256(), null,
                    new WorkflowDelivery.OutputReference(1, WorkflowEncoding.hash(body), bytes(body)));
        }).toList();
        var frozen = copy(inputs, values.stream().anyMatch(input -> input.reference() != null) ? 2 : 1, values);
        // Admission must prove the reference identifies precisely the accepted bytes it was given.
        if (!materialize(frozen).equals(inputs)) throw corrupt();
        return frozen;
    }
    public WorkflowDelivery.Inputs snapshot(Attempt attempt) {
        if (!WorkflowEncoding.hash(attempt.inputsJson()).equals(attempt.inputsSha256())) throw corrupt();
        var inputs = encoding.decode(attempt.inputsJson(), WorkflowDelivery.Inputs.class);
        var node = mapper.node(attempt.nodeRunId()).orElseThrow(WorkflowInputSnapshots::corrupt);
        if (!node.requirementId().equals(inputs.requirementId()) || !node.nodeKey().equals(inputs.nodeId())
                || inputs.planRevision() != attempt.planRevision()) throw corrupt();
        validate(inputs);
        return inputs;
    }
    public WorkflowDelivery.Inputs materialize(WorkflowDelivery.Inputs inputs) {
        validate(inputs);
        var references = inputs.values().stream().filter(input -> input.reference() != null).toList();
        var outputs = outputs(inputs.requirementId(), references);
        return copy(inputs, 1, inputs.values().stream().map(input -> resolve(input, outputs)).toList());
    }
    public WorkflowDelivery.Input input(WorkflowDelivery.Inputs inputs, String name) {
        validate(inputs);
        var input = inputs.values().stream().filter(value -> value.name().equals(name)).findFirst()
                .orElseThrow(() -> new BadRequestException("WORKFLOW_INPUT_UNKNOWN", "该名称不属于本节点的固定输入"));
        return input.reference() == null ? input : resolve(input, outputs(inputs.requirementId(), List.of(input)));
    }
    private Map<String, WorkflowDelivery> outputs(String requirement, List<WorkflowDelivery.Input> references) {
        if (references.isEmpty()) return Map.of();
        var ids = new LinkedHashSet<String>(); references.forEach(input -> ids.add(input.attemptId()));
        var rows = new HashMap<String, WorkflowExecutionMapper.InputDelivery>();
        var outputs = new HashMap<String, WorkflowDelivery>();
        for (var row : mapper.inputDeliveries(ids)) {
            if (!requirement.equals(row.requirementId()) || !WorkflowEncoding.hash(row.contentJson()).equals(row.sha256())) throw corrupt();
            rows.put(row.attemptId(), row);
            outputs.put(row.attemptId(), encoding.decode(row.contentJson(), WorkflowDelivery.class));
        }
        for (var input : references) {
            var row = rows.get(input.attemptId());
            if (row == null || !row.nodeRunId().equals(input.sourceId()) || !row.sha256().equals(input.sha256())) throw corrupt();
        }
        return outputs;
    }
    private WorkflowDelivery.Input resolve(WorkflowDelivery.Input input, Map<String, WorkflowDelivery> deliveries) {
        if (input.reference() == null) return input;
        var delivery = deliveries.get(input.attemptId());
        var output = delivery == null ? null : delivery.outputs().get(input.outputName());
        if (!WorkflowDeliveries.valid(output) || input.kind() != output.kind()) throw corrupt();
        String body = encoding.encode(output.content());
        if (!WorkflowEncoding.hash(body).equals(input.reference().contentSha256()) || bytes(body) != input.reference().sizeBytes()) throw corrupt();
        return new WorkflowDelivery.Input(input.name(), input.kind(), input.source(), input.sourceId(),
                input.outputName(), input.attemptId(), input.sha256(), output.content());
    }
    private static void validate(WorkflowDelivery.Inputs inputs) {
        if (inputs.version() != 1 && inputs.version() != 2) throw corrupt();
        var names = new HashSet<String>();
        for (var input : inputs.values()) {
            if (input.name() == null || !names.add(input.name()) || input.kind() == null) throw corrupt();
            var reference = input.reference();
            if (reference == null) {
                if (!WorkflowDeliveries.valid(new WorkflowDelivery.Value(input.kind(), input.content()))) throw corrupt();
            } else if (inputs.version() != 2 || !referencable(input) || input.content() != null && !input.content().isNull()
                    || reference.version() != 1 || reference.sizeBytes() < 1 || reference.sizeBytes() > WorkflowSnapshotWork.MAX_DELIVERY_BYTES
                    || reference.contentSha256() == null || !reference.contentSha256().matches("[0-9a-f]{64}")
                    || input.sourceId() == null || input.attemptId() == null || input.outputName() == null || input.sha256() == null) throw corrupt();
        }
    }
    private static boolean referencable(WorkflowDelivery.Input input) {
        return "NODE".equals(input.source()) && input.kind() != WorkflowGraph.DataKind.CODE && input.kind() != WorkflowGraph.DataKind.DOCUMENT;
    }
    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static WorkflowDelivery.Inputs copy(WorkflowDelivery.Inputs inputs, int version, List<WorkflowDelivery.Input> values) {
        return new WorkflowDelivery.Inputs(version, inputs.requirementId(), inputs.planRevision(), inputs.nodeId(), inputs.objective(), values);
    }
    private static ConflictException corrupt() {
        return new ConflictException("WORKFLOW_INPUT_CORRUPT", "固定输入与原交付版本不一致，已保留记录，请检查来源交付物。");
    }
}
