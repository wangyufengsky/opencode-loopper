package io.opencode.loopper.workflow;

import io.opencode.loopper.template.SourceTestProfile;
import java.util.*;

/** One explicit native module per node; the frozen profile supplies all executable arguments. */
public final class WorkflowNativeTest {
    public static final String MODULE="system.source.test-run",TYPE="SOURCE_TEST_RUN";
    private WorkflowNativeTest(){ }
    public record Settings(String moduleRoot,int timeoutSeconds){ }
    public static Settings require(WorkflowGraph.Node node) {
        if(node.kind()!=WorkflowGraph.NodeKind.SYSTEM||!MODULE.equals(node.moduleId())||!Set.of(1,2).contains(node.moduleVersion())||node.roleId()!=null||node.roleRevisionId()!=null
                ||node.completion()==null||!Set.of(WorkflowGraph.CompletionKind.VERIFIED,WorkflowGraph.CompletionKind.OUTCOME,WorkflowGraph.CompletionKind.DELIVERABLES).contains(node.completion().kind())
                ||node.outcomes().size()!=2||!new HashSet<>(node.outcomes()).equals(Set.of("PASS","FAIL"))||node.inputs().size()<4||node.inputs().size()>64
                ||node.moduleVersion()==1&&node.inputs().size()!=4||node.outputs().size()!=2)
            throw invalid();
        for(var port:Map.of("source",WorkflowGraph.DataKind.DOCUMENT,"profile",WorkflowGraph.DataKind.JSON,"design",WorkflowGraph.DataKind.JSON,"code",WorkflowGraph.DataKind.CODE).entrySet())
            if(node.inputs().stream().noneMatch(v->v.name().equals(port.getKey())&&v.kind()==port.getValue()&&v.source()==WorkflowGraph.InputSource.NODE&&v.required()))throw invalid();
        if(node.inputs().stream().anyMatch(v->!Set.of("source","profile","design","code").contains(v.name())
                &&(!v.name().matches("design_[a-zA-Z0-9_]+")||v.kind()!=WorkflowGraph.DataKind.JSON||v.source()!=WorkflowGraph.InputSource.NODE||!v.required())))throw invalid();
        for(var port:Map.of("report",WorkflowGraph.DataKind.JSON,"summary",WorkflowGraph.DataKind.TEXT).entrySet())
            if(node.outputs().stream().noneMatch(v->v.name().equals(port.getKey())&&v.kind()==port.getValue()&&v.required()))throw invalid();
        if(!Set.of("presetId","presetVersion","testModuleRoot","testTimeoutSeconds","outcomeTitles").containsAll(node.parameters().keySet()))throw invalid();
        String root=node.parameters().getOrDefault("testModuleRoot",".");
        if(!root.equals(".")&&(root.length()>2048||root.contains("\\")||root.contains(":")||root.chars().anyMatch(Character::isISOControl)
                ||Arrays.stream(root.split("/",-1)).anyMatch(p->p.isBlank()||p.equals(".")||p.equals(".."))))throw invalid();
        int timeout;try{timeout=Integer.parseInt(node.parameters().getOrDefault("testTimeoutSeconds","600"));}catch(NumberFormatException e){throw invalid();}
        if(timeout<1||timeout>3600)throw invalid();return new Settings(root,timeout);
    }
    public static List<String> argv(SourceTestProfile.Module module) {
        var args=new ArrayList<>(module.command());
        switch(module.framework()) {
            case "pytest"->args.add("--junitxml="+path(module.root(),".loopper-test-results/report.xml"));
            case "jest"->{args.add("--json");args.add("--outputFile=.loopper-test-results/report.json");}
            case "vitest"->{args.add("--reporter=json");args.add("--outputFile=.loopper-test-results/report.json");}
            case "junit","testng"->{if(args.getFirst().equals("gradle")){args.add("--no-daemon");args.add("--rerun-tasks");}}
            default->throw invalid();
        }
        return List.copyOf(args);
    }
    public static String reportPath(SourceTestProfile.Module module) {
        String relative=switch(module.framework()) {
            case "pytest"->".loopper-test-results/report.xml";
            case "jest","vitest"->".loopper-test-results/report.json";
            case "junit","testng"->module.command().getFirst().equals("gradle")?"build/test-results/test":"target/surefire-reports";
            default->throw invalid();
        };
        return path(module.root(),relative);
    }
    private static String path(String root,String path){return root.equals(".")?path:root+"/"+path;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("原生单测节点需要同版源码、测试配置、场景和代码；请选择固定配置中的模块路径及 1–3600 秒执行时限。");}
}
