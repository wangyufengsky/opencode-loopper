package io.opencode.loopper.workflow;

import java.util.*;
import tools.jackson.databind.JsonNode;

/** Explicit publication of selected source receipts, independent of private Session evidence. */
public final class WorkflowKnowledgeBundle {
    public static final String MODULE="knowledge.research", TYPE="KNOWLEDGE_EVIDENCE";
    private WorkflowKnowledgeBundle(){ }
    public record Entry(String reference,String toolName,String createdAt,String sha256,JsonNode content){ }
    public record Frozen(int version,String type,List<Entry> entries,List<String> limitations){ }
    public static boolean supports(String module){return MODULE.equals(module);}
    public static void require(WorkflowGraph.Node node) {
        if(!supports(node.moduleId())||node.kind()!=WorkflowGraph.NodeKind.WORK||node.moduleVersion()!=1
                ||node.outputs().stream().filter(o->o.name().equals("result")&&o.kind()==WorkflowGraph.DataKind.TEXT&&o.required()).count()!=1
                ||node.outputs().stream().filter(o->o.name().equals("evidence")&&o.kind()==WorkflowGraph.DataKind.JSON&&o.required()).count()!=1)
            throw new IllegalArgumentException("知识整理需要检索结论和来源证据两份必需交付物。");
    }
    public static Map<String,Object> submission(){return Map.of(
            "output","evidence","kind","JSON","content",Map.of("version",1,"references",List.of("call:本次实际知识调用的引用"),"limitations",List.of("尚未覆盖或不能核实的范围")),
            "instruction","先检索并实际读取原文，再选择本次 Session 返回的真实 call: 引用，最多 12 份。程序从保存记录填写固定正文；不要自行填写 entries、来源内容或 SHA。搜索/目录仅是发现线索，不能冒充读过原文。没有可交付证据时 references 可为空，但 limitations 必须说明缺口，不宣称全项目不存在资料。交付保持 128 KiB 上限，过大时缩小读取范围后重新选择，不截断现有引用。只有这些主动选中的保存内容进入后继固定输入。");}
}
