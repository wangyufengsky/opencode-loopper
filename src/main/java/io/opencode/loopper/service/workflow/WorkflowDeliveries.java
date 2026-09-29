package io.opencode.loopper.service.workflow;

import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.workflow.*;
import java.util.*;

/** Shape checks never substitute for a module's deterministic verifier or a user's review. */
final class WorkflowDeliveries {
    private WorkflowDeliveries() { }
    static int limit(String module){if(WorkflowSnapshotWork.supports(module))return WorkflowSnapshotWork.MAX_DELIVERY_BYTES;return WorkflowSourceDesign.supports(module)||WorkflowDocumentReview.supports(module)?300*1024:128*1024;}
    static void validate(WorkflowGraph.Node node, WorkflowDelivery delivery) {
        if (delivery == null || delivery.summary() == null || delivery.summary().isBlank() || delivery.summary().length() > 4000) throw invalid();
        if (delivery.outcome() != null && !node.outcomes().contains(delivery.outcome())
                || !node.outcomes().isEmpty() && delivery.outcome() == null) throw invalid();
        var declared = new HashMap<String, WorkflowGraph.Output>();
        for (var output : node.outputs()) {
            declared.put(output.name(), output);
            if (output.required() && !delivery.outputs().containsKey(output.name())) throw invalid();
        }
        for (var entry : delivery.outputs().entrySet()) {
            var definition = declared.get(entry.getKey());
            if (definition == null || definition.kind() != entry.getValue().kind() || !valid(entry.getValue())) throw invalid();
        }
    }
    static boolean valid(WorkflowDelivery.Value value) {
        if (value == null || value.kind() == null || value.content() == null || value.content().isNull()) return false;
        return switch (value.kind()) {
            case TEXT -> value.content().isString() && !value.content().asString().isBlank();
            case JSON -> value.content().isObject() || value.content().isArray();
            case CONTROL, DECISION, PLAN, DOCUMENT, CODE -> value.content().isObject();
        };
    }
    private static BadRequestException invalid() { return new BadRequestException("WORKFLOW_DELIVERY_INVALID", "交付物、业务结果或必需输出不符合节点定义"); }
}
