package io.opencode.loopper.service.workflow;

import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.template.SourceManifest;
import io.opencode.loopper.workflow.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Shared bounded batch selection over an already authenticated frozen manifest. */
final class WorkflowSourceTargets {
    private WorkflowSourceTargets(){ }
    static List<String> select(WorkflowGraph.Node node,SourceManifest manifest,WorkflowEncoding encoding) {
        var available=manifest.files().stream().filter(SourceManifest.File::processable).toList();Set<String> selected;
        String parameter=node.parameters().get("targetPaths");
        if(parameter==null||parameter.isBlank())selected=new LinkedHashSet<>(available.stream().map(SourceManifest.File::path).toList());
        else {
            JsonNode value;try{value=encoding.decode(parameter,JsonNode.class);}catch(RuntimeException malformed){throw invalid("本批源码路径必须是 JSON 字符串数组。");}
            selected=new LinkedHashSet<>();if(!value.isArray())throw invalid("本批源码路径必须是 JSON 字符串数组。");
            for(var item:value)if(!item.isString()||!selected.add(item.asString()))throw invalid("本批源码路径不能重复或包含非文本内容。");
        }
        var files=available.stream().filter(file->selected.contains(file.path())).toList();
        if(selected.isEmpty()||files.size()!=selected.size())throw invalid("请选择冻结资料中可处理的目标源码，不能用上下文或排除项扩大范围。");
        if(files.size()>12||files.size()>1&&files.stream().mapToLong(SourceManifest.File::sizeBytes).sum()>160000)
            throw new BadRequestException("WORKFLOW_SOURCE_BATCH_REQUIRED","每个编写或复核节点最多 12 个文件、160000 字节（单个大文件独立一批）。请在候选计划中拆分目标路径，并经用户确认后执行。");
        return files.stream().map(SourceManifest.File::path).toList();
    }
    private static BadRequestException invalid(String message){return new BadRequestException("WORKFLOW_SOURCE_DESIGN_INVALID",message);}
}
