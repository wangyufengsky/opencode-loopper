package io.opencode.loopper.workflow;

import static io.opencode.loopper.workflow.WorkflowGraph.*;
import java.util.*;

/** Pure structural and data-flow validation. Runtime role permissions and resource admission are separate checks. */
public final class WorkflowGraphValidator {
    private WorkflowGraphValidator() { }
    public enum Mode { DRAFT, EXECUTION }
    public enum Severity { ERROR, WARNING }
    public record Diagnostic(String path, String code, String message, Severity severity) { }
    private static final int MAX_NODES = 256;
    private static final int MAX_EDGES = 1024;

    public static List<Diagnostic> validate(WorkflowGraph graph, Mode mode) {
        var issues = new ArrayList<Diagnostic>();
        if (graph == null || graph.schemaVersion() != 1) {
            error(issues, "schemaVersion", "WORKFLOW_SCHEMA_UNSUPPORTED", "请选择受支持的流程格式");
            return List.copyOf(issues);
        }
        if (graph.nodes().size() > MAX_NODES || graph.edges().size() > MAX_EDGES || graph.inputs().size() > 64) {
            error(issues, "", "WORKFLOW_TOO_LARGE", "单个流程最多 256 个节点、1024 条连接及 64 项公共输入");
            return List.copyOf(issues);
        }
        if (graph.nodes().isEmpty()) incomplete(issues, mode, "nodes", "WORKFLOW_EMPTY", "请至少添加一个工作节点");
        var nodes = new LinkedHashMap<String, Node>();
        for (int index = 0; index < graph.nodes().size(); index++) {
            var node = graph.nodes().get(index);
            String path = "nodes[" + index + "]";
            if (!id(node.id()) || nodes.putIfAbsent(node.id(), node) != null)
                error(issues, path + ".id", "NODE_ID_INVALID", "节点标识无效或重复");
            validateNode(node, path, mode, issues);
        }
        var publicInputs = new HashMap<String, PublicInput>();
        for (var input : graph.inputs()) {
            if (!key(input.name()) || publicInputs.putIfAbsent(input.name(), input) != null || input.kind() == null)
                error(issues, "inputs", "PUBLIC_INPUT_INVALID", "公共输入名称重复、无效或缺少类型");
            if (!text(input.title(), 120)) error(issues, "inputs", "PUBLIC_INPUT_INVALID", "请填写不超过 120 字的输入名称");
        }
        var parents = new LinkedHashMap<String, Set<String>>();
        nodes.keySet().forEach(node -> parents.put(node, new LinkedHashSet<>()));
        var edgeIds = new HashSet<String>();
        var connections = new HashSet<List<String>>();
        for (int index = 0; index < graph.edges().size(); index++) {
            var edge = graph.edges().get(index);
            String path = "edges[" + index + "]";
            if (!id(edge.id()) || !edgeIds.add(edge.id())) error(issues, path + ".id", "EDGE_ID_INVALID", "连接标识无效或重复");
            if (!nodes.containsKey(edge.from()) || !nodes.containsKey(edge.to()) || Objects.equals(edge.from(), edge.to())) {
                error(issues, path, "EDGE_ENDPOINT_INVALID", "连接两端必须是不同的已有节点");
                continue;
            }
            String outcome = edge.outcome() == null ? "" : edge.outcome();
            if (!connections.add(List.of(edge.from(), edge.to())))
                error(issues, path, "EDGE_DUPLICATED", "两个节点之间只能存在一条依赖连接");
            if (edge.outcome() != null && (!key(outcome) || !nodes.get(edge.from()).outcomes().contains(outcome)))
                error(issues, path + ".outcome", "EDGE_OUTCOME_UNKNOWN", "条件必须选择前置节点声明的业务结果");
            parents.get(edge.to()).add(edge.from());
        }
        var order = order(parents);
        if (order.size() != nodes.size()) error(issues, "edges", "WORKFLOW_CYCLE", "依赖存在循环，请删除循环连接；重试属于同一节点的执行尝试");
        else validateInputs(order, nodes, parents, publicInputs, issues);
        return List.copyOf(issues);
    }

    private static void validateNode(Node node, String path, Mode mode, List<Diagnostic> issues) {
        if (!text(node.title(), 120)) incomplete(issues, mode, path + ".title", "NODE_TITLE_REQUIRED", "请填写节点名称，最多 120 字");
        if (node.kind() == null) error(issues, path + ".kind", "NODE_KIND_REQUIRED", "请选择工作或人工节点");
        if (node.kind() == NodeKind.WORK) {
            if (node.roleRevisionId() != null && !text(node.roleRevisionId(), 160))
                error(issues, path + ".roleRevisionId", "ROLE_REVISION_INVALID", "角色版本标识无效");
            if (!text(node.moduleId(), 100) || node.moduleVersion() < 1)
                incomplete(issues, mode, path + ".moduleId", "MODULE_REQUIRED", "请选择预设工作模块或自由任务");
            if (!text(node.roleId(), 160)) incomplete(issues, mode, path + ".roleId", "ROLE_REQUIRED", "请选择执行角色");
        }
        if(node.kind()==NodeKind.SYSTEM && (!text(node.moduleId(),100) || node.moduleVersion()<1 || node.roleId()!=null || node.roleRevisionId()!=null))
            error(issues,path,"SYSTEM_MODULE_INVALID","程序节点需要明确模块版本，无需配置 AI 角色。");
        if (!text(node.task(), 24000)) incomplete(issues, mode, path + ".task", "TASK_REQUIRED", "请填写工作内容，最多 24000 字");
        if (node.maxRetries() < 0 || node.maxRetries() > 10)
            error(issues, path + ".maxRetries", "RETRY_LIMIT_INVALID", "自动重试次数必须在 0–10 之间");
        if (node.inputs().size() > 64 || node.outputs().size() > 32 || node.outcomes().size() > 32 || node.parameters().size() > 64) {
            error(issues, path, "NODE_TOO_LARGE", "节点输入、输出、业务结果或参数数量超过范围");
            return;
        }
        if (node.outputs().stream().noneMatch(Output::required))
            incomplete(issues, mode, path + ".outputs", "OUTPUT_REQUIRED", "至少指定一份必需交付物");
        var outputs = new HashSet<String>();
        for (var output : node.outputs()) if (!key(output.name()) || !outputs.add(output.name()) || output.kind() == null || !text(output.title(), 120))
            error(issues, path + ".outputs", "OUTPUT_INVALID", "交付物名称重复、无效或缺少标题/类型");
        var outcomes = new HashSet<String>();
        for (var outcome : node.outcomes()) if (!key(outcome) || !outcomes.add(outcome))
            error(issues, path + ".outcomes", "OUTCOME_INVALID", "业务结果名称重复或无效");
        var completion = node.completion();
        if (completion == null || completion.kind() == null || !text(completion.criterion(), 4000))
            incomplete(issues, mode, path + ".completion", "COMPLETION_REQUIRED", "请选择完成方式并填写完成标准");
        else if (completion.kind() == CompletionKind.OUTCOME && !node.outcomes().contains(completion.expectedOutcome()))
            error(issues, path + ".completion", "COMPLETION_OUTCOME_UNKNOWN", "完成条件必须选择该节点声明的业务结果");
        for (var parameter : node.parameters().entrySet())
            if (!key(parameter.getKey()) || parameter.getValue().length() > 24000)
                error(issues, path + ".parameters", "PARAMETER_INVALID", "模块参数名称无效或内容过长");
    }

    private static void validateInputs(List<String> order, Map<String, Node> nodes, Map<String, Set<String>> parents,
            Map<String, PublicInput> publicInputs, List<Diagnostic> issues) {
        var ancestors = new HashMap<String, Set<String>>();
        for (var id : order) {
            var reachable = new HashSet<String>();
            for (var parent : parents.get(id)) { reachable.add(parent); reachable.addAll(ancestors.get(parent)); }
            ancestors.put(id, reachable);
            var names = new HashSet<String>();
            for (var input : nodes.get(id).inputs()) {
                String path = "node:" + id + ".inputs." + input.name();
                if (!key(input.name()) || !names.add(input.name()) || input.source() == null || input.kind() == null) {
                    error(issues, path, "INPUT_INVALID", "输入名称重复、无效或缺少来源/类型"); continue;
                }
                DataKind actual = null;
                if (input.source() == InputSource.REQUIREMENT) {
                    var item = publicInputs.get(input.sourceId());
                    if (item != null) actual = item.kind();
                } else if (reachable.contains(input.sourceId())) {
                    actual = nodes.get(input.sourceId()).outputs().stream().filter(output -> Objects.equals(output.name(), input.output()))
                            .map(Output::kind).findFirst().orElse(null);
                }
                if (actual == null) error(issues, path, "INPUT_SOURCE_UNAVAILABLE", "输入必须引用已声明的公共资料或依赖可达的上游交付物");
                else if (actual != input.kind()) error(issues, path, "INPUT_TYPE_MISMATCH", "输入类型与上游交付物类型不一致");
            }
        }
    }

    static List<String> order(Map<String, Set<String>> parents) {
        var pending = new LinkedHashMap<String, Set<String>>();
        parents.forEach((id, values) -> pending.put(id, new HashSet<>(values)));
        var order = new ArrayList<String>();
        while (!pending.isEmpty()) {
            var ready = pending.entrySet().stream().filter(entry -> entry.getValue().isEmpty()).map(Map.Entry::getKey).toList();
            if (ready.isEmpty()) break;
            ready.forEach(pending::remove); order.addAll(ready);
            pending.values().forEach(values -> values.removeAll(ready));
        }
        return order;
    }
    private static boolean id(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,80}"); }
    private static boolean key(String value) { return value != null && value.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"); }
    private static boolean text(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
    private static void error(List<Diagnostic> issues, String path, String code, String message) {
        issues.add(new Diagnostic(path, code, message, Severity.ERROR));
    }
    private static void incomplete(List<Diagnostic> issues, Mode mode, String path, String code, String message) {
        issues.add(new Diagnostic(path, code, message, mode == Mode.EXECUTION ? Severity.ERROR : Severity.WARNING));
    }
}
