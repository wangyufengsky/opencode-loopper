package io.opencode.loopper.workflow;

import static io.opencode.loopper.workflow.WorkflowGraph.*;
import java.util.List;
import java.util.Map;

public final class WorkflowFixtures {
    private WorkflowFixtures() { }
    public static Node node(String id, List<Input> inputs) {
        return new Node(id, id, NodeKind.WORK, "task.free", 1, "builtin.general",
                "按需求完成 " + id, inputs, List.of(new Output("result", "交付结果", DataKind.TEXT, true)),
                List.of("SUCCESS", "REVISE"), new Completion(CompletionKind.DELIVERABLES, "提交可核验的结果", null),
                2, false, Map.of());
    }
    public static Input input(String name, String from) {
        return new Input(name, InputSource.NODE, from, "result", DataKind.TEXT, true);
    }
    public static WorkflowGraph single() { return new WorkflowGraph(1, List.of(node("first", List.of())), List.of(), List.of()); }
    public static WorkflowGraph chain() {
        return new WorkflowGraph(1, List.of(node("first", List.of()), node("second", List.of(input("upstream", "first")))),
                List.of(new Edge("first-second", "first", "second", null)), List.of());
    }
}
