package io.opencode.loopper.workflow;

import io.opencode.loopper.template.SourceTestProfile;
import java.util.*;

/** Native test configuration is a program-produced fact, not proof that tests ran. */
public final class WorkflowTestProfile {
    public static final String MODULE="system.source.test-profile",ADAPTER="system.source.test-profile.v1",TYPE="SOURCE_TEST_PROFILE";
    private WorkflowTestProfile(){ }
    public record Frozen(int version,String type,String sourceAttemptId,WorkflowSourceSnapshot.Reference source,SourceTestProfile profile){ }
    public static String require(WorkflowGraph.Node node) {
        if(node.kind()!=WorkflowGraph.NodeKind.SYSTEM||!MODULE.equals(node.moduleId())||node.moduleVersion()!=1||node.roleId()!=null||node.roleRevisionId()!=null
                ||node.completion()==null||node.completion().kind()!=WorkflowGraph.CompletionKind.DELIVERABLES||!node.outcomes().isEmpty()
                ||node.inputs().size()!=1||!node.inputs().getFirst().name().equals("source")||node.inputs().getFirst().source()!=WorkflowGraph.InputSource.NODE
                ||node.inputs().getFirst().kind()!=WorkflowGraph.DataKind.DOCUMENT||!node.inputs().getFirst().required()
                ||node.outputs().size()!=3
                ||node.outputs().stream().noneMatch(o->o.name().equals("profile")&&o.kind()==WorkflowGraph.DataKind.JSON&&!o.required())
                ||node.outputs().stream().noneMatch(o->o.name().equals("report")&&o.kind()==WorkflowGraph.DataKind.JSON&&o.required())
                ||node.outputs().stream().noneMatch(o->o.name().equals("summary")&&o.kind()==WorkflowGraph.DataKind.TEXT&&o.required()))
            throw new IllegalArgumentException("测试配置识别需要一份单测用途的冻结源码，以及测试配置、识别报告和说明交付。");
        String path=node.parameters().get("testOutputPath");
        if(path==null||path.isBlank())return null;
        if(path.length()>2048||path.startsWith("/")||path.contains("\\")||path.contains(":")||path.chars().anyMatch(Character::isISOControl)
                ||Arrays.stream(path.split("/",-1)).anyMatch(part->part.isBlank()||part.equals(".")||part.equals("..")))
            throw new IllegalArgumentException("测试输出目录必须是项目内的相对路径，不能包含上级跳转。");
        return path;
    }
}
