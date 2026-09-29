package io.opencode.loopper.service.workflow;

import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.workflow.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Shared command definition parsing for plan feedback and runtime admission; no execution or persistence. */
final class WorkflowCommandParser {
    private WorkflowCommandParser() { }
    static WorkflowCommandVerification parse(WorkflowGraph.Node node,ObjectMapper json,boolean allowUnconfigured) {
        try {
            if(node.kind()!=WorkflowGraph.NodeKind.SYSTEM || !WorkflowCommandVerification.MODULE.equals(node.moduleId()) || node.moduleVersion()!=1
                    || node.roleId()!=null || node.roleRevisionId()!=null || node.completion()==null
                    || !Set.of(WorkflowGraph.CompletionKind.VERIFIED,WorkflowGraph.CompletionKind.OUTCOME,WorkflowGraph.CompletionKind.DELIVERABLES).contains(node.completion().kind())
                    || !new HashSet<>(node.outcomes()).equals(Set.of("PASS","FAIL")) || node.outcomes().size()!=2
                    || node.outputs().size()!=2 || node.outputs().stream().noneMatch(o->o.name().equals("report")&&o.kind()==WorkflowGraph.DataKind.JSON&&o.required())
                    || node.outputs().stream().noneMatch(o->o.name().equals("summary")&&o.kind()==WorkflowGraph.DataKind.TEXT&&o.required()))
                throw new IllegalArgumentException("命令检查需要系统节点、检查报告及说明，并保留通过和未通过两个结果。");
            String body=node.parameters().get("commandVerification");var value=json.readTree(body);
            if(!value.isObject() || !Set.of("version","inputName","argv","timeoutSeconds","purpose","outputContains").containsAll(value.propertyNames())
                    || !value.path("version").isIntegralNumber() || !value.path("timeoutSeconds").isIntegralNumber()
                    || !value.path("inputName").isString() || !value.path("purpose").isString() || !value.path("argv").isArray()
                    || value.hasNonNull("outputContains") && !value.path("outputContains").isString())throw new IllegalArgumentException("命令配置包含缺失或不支持的字段。");
            for(var arg:value.path("argv"))if(!arg.isString())throw new IllegalArgumentException("每个命令参数必须是独立文本。");
            var spec=json.readValue(body,WorkflowCommandVerification.class);spec.validate(allowUnconfigured);
            if(node.inputs().stream().noneMatch(input->input.name().equals(spec.inputName()) && input.source()==WorkflowGraph.InputSource.NODE
                    && input.kind()==WorkflowGraph.DataKind.CODE && input.required()))throw new IllegalArgumentException("请选择必需的上游代码交付作为检查输入。");
            return spec;
        } catch(RuntimeException invalid) {
            throw new BadRequestException("WORKFLOW_COMMAND_INVALID",invalid instanceof IllegalArgumentException?invalid.getMessage():"检查命令配置无效，请重新设置。");
        }
    }
}
