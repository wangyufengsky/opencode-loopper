package io.opencode.loopper.workflow;

import io.opencode.loopper.template.SourceDesign;
import java.util.*;

/** Test scenarios are proposed work, never execution or coverage proof. */
public final class WorkflowTestDesign {
    public static final String MODULE="source.test-design", TYPE="SOURCE_TEST_DESIGN";
    private WorkflowTestDesign(){ }
    public record Candidate(String title,String summary,List<Scenario> scenarios,List<String> limitations){ }
    public record Scenario(String key,String path,String category,String title,List<String> steps,String expected,List<SourceDesign.Reference> references){ }
    public record Frozen(int version,String type,String sourceAttemptId,WorkflowSourceSnapshot.Reference source,
                         String profileAttemptId,String profileSha256,Candidate design){ }
    public static boolean supports(String module){return MODULE.equals(module);}
    public static void require(WorkflowGraph.Node node) {
        if(!supports(node.moduleId())||node.kind()!=WorkflowGraph.NodeKind.WORK||node.moduleVersion()!=1
                ||node.completion()==null||node.completion().kind()!=WorkflowGraph.CompletionKind.DELIVERABLES||!node.outcomes().isEmpty()
                ||node.inputs().stream().filter(i->i.name().equals("source")&&i.kind()==WorkflowGraph.DataKind.DOCUMENT&&i.required()&&i.source()==WorkflowGraph.InputSource.NODE).count()!=1
                ||node.inputs().stream().filter(i->i.name().equals("profile")&&i.kind()==WorkflowGraph.DataKind.JSON&&i.required()&&i.source()==WorkflowGraph.InputSource.NODE).count()!=1
                ||node.inputs().stream().anyMatch(i->!Set.of("source","profile").contains(i.name())&&i.kind()!=WorkflowGraph.DataKind.TEXT)
                ||node.outputs().size()!=2||node.outputs().stream().noneMatch(o->o.name().equals("design")&&o.kind()==WorkflowGraph.DataKind.JSON&&o.required())
                ||node.outputs().stream().noneMatch(o->o.name().equals("summary")&&o.kind()==WorkflowGraph.DataKind.TEXT&&o.required()))
            throw new IllegalArgumentException("单测场景设计需要同版冻结源码和程序识别的测试配置，并交付场景设计与说明。");
    }
}
