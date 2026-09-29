package io.opencode.loopper.service.workflow;

import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.workflow.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public final class WorkflowEncoding {
    private final ObjectMapper json;
    public WorkflowEncoding(ObjectMapper json) { this.json = json; }
    public record Definition(WorkflowGraph graph, String body, String sha256) { }
    public Definition definition(WorkflowGraph graph) {
        if (graph == null) throw bad("WORKFLOW_REQUIRED", "请提供流程定义");
        String body = encode(graph);
        if (body.getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024) throw bad("WORKFLOW_TOO_LARGE", "流程定义不能超过 2 MiB");
        var errors = WorkflowGraphValidator.validate(graph, WorkflowGraphValidator.Mode.DRAFT).stream()
                .filter(issue -> issue.severity() == WorkflowGraphValidator.Severity.ERROR).toList();
        if (!errors.isEmpty()) throw bad("WORKFLOW_INVALID", errors.getFirst().message() + "（" + errors.getFirst().path() + "）");
        return new Definition(graph, body, hash(body));
    }
    public WorkflowGraph read(String body, String sha256) {
        if (!hash(body).equals(sha256)) throw new ConflictException("WORKFLOW_SNAPSHOT_CORRUPT", "流程版本内容校验失败，已保留原记录");
        return json.readValue(body, WorkflowGraph.class);
    }
    public String layout(CanvasLayout value, WorkflowGraph graph) {
        if (value == null) value = CanvasLayout.empty();
        var ids = new HashSet<>(graph.nodes().stream().map(WorkflowGraph.Node::id).toList());
        if (value.positions().size() > 256 || !ids.containsAll(value.positions().keySet()) || !coordinate(value.x())
                || !coordinate(value.y()) || !Double.isFinite(value.zoom()) || value.zoom() < .1 || value.zoom() > 4)
            throw bad("CANVAS_LAYOUT_INVALID", "画布坐标、缩放或节点位置无效");
        for (var point : value.positions().values()) if (!coordinate(point.x()) || !coordinate(point.y()))
            throw bad("CANVAS_LAYOUT_INVALID", "节点坐标无效");
        return encode(value);
    }
    public CanvasLayout readLayout(String body, WorkflowGraph graph) {
        var value = json.readValue(body, CanvasLayout.class);
        var positions = new LinkedHashMap<String, CanvasLayout.Point>();
        for (var node : graph.nodes()) if (value.positions().containsKey(node.id())) positions.put(node.id(), value.positions().get(node.id()));
        return new CanvasLayout(positions, value.x(), value.y(), value.zoom());
    }
    public String title(String value) { return text(value, 120, false, "标题"); }
    public String description(String value) { return text(value, 4000, true, "说明"); }
    public String objective(String value) { return text(value, 24000, false, "需求内容"); }
    public String encode(Object value) {
        var tree=json.valueToTree(value);
        // V1 command JSON is frozen too; adding an empty V2 field must not change its canonical digest.
        if(value instanceof io.opencode.loopper.runtime.DurableCommandProtocol.Request r&&r.preparations().isEmpty()
                ||value instanceof io.opencode.loopper.runtime.DurableCommandProtocol.Result result&&result.preparations().isEmpty())
            ((tools.jackson.databind.node.ObjectNode)tree).remove("preparations");
        return json.writeValueAsString(canonical(tree));
    }
    public <T> T decode(String value, Class<T> type) { return json.readValue(value, type); }
    public WorkflowCommandVerification command(WorkflowGraph.Node node) { return WorkflowCommandParser.parse(node,json,false); }
    public List<WorkflowGraphValidator.Diagnostic> diagnostics(WorkflowGraph graph) {
        var result=new ArrayList<>(WorkflowGraphValidator.validate(graph,WorkflowGraphValidator.Mode.EXECUTION));
        if(graph!=null)for(var node:graph.nodes())if(WorkflowCommandVerification.MODULE.equals(node.moduleId())) {
            try { WorkflowCommandParser.parse(node,json,true); }
            catch(BadRequestException invalid) { result.add(new WorkflowGraphValidator.Diagnostic("node:"+node.id()+".parameters.commandVerification",
                    "WORKFLOW_COMMAND_INVALID",invalid.getMessage(),WorkflowGraphValidator.Severity.ERROR)); }
        }
        return List.copyOf(result);
    }
    public void requireConfiguredCommands(WorkflowGraph graph) {
        for(var node:graph.nodes())if(WorkflowCommandVerification.MODULE.equals(node.moduleId()))WorkflowCommandParser.parse(node,json,true);
    }
    public String digest(String operation, String owner, Object request) {
        return hash(operation + "\n" + (owner == null ? "" : owner) + "\n" + encode(request));
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private tools.jackson.databind.JsonNode canonical(tools.jackson.databind.JsonNode value) {
        if (value.isObject()) {
            var result = json.createObjectNode();
            value.properties().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> result.set(entry.getKey(), canonical(entry.getValue())));
            return result;
        }
        if (value.isArray()) {
            var result = json.createArrayNode(); value.forEach(child -> result.add(canonical(child))); return result;
        }
        return value;
    }
    private String text(String value, int maximum, boolean optional, String field) {
        value = value == null ? "" : value.strip();
        if ((!optional && value.isEmpty()) || value.length() > maximum) throw bad("WORKFLOW_TEXT_INVALID", field + "不能为空或超过 " + maximum + " 字");
        return value;
    }
    private static boolean coordinate(double value) { return Double.isFinite(value) && Math.abs(value) <= 1_000_000; }
    private static BadRequestException bad(String code, String message) { return new BadRequestException(code, message); }
}
