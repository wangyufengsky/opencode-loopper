package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.opencode.loopper.runtime.DurableCommandProtocol.*;
import io.opencode.loopper.template.SourceTestProfile;
import io.opencode.loopper.workflow.*;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class WorkflowNativeTestContractTest {
    @TempDir Path directory;
    private final WorkflowEncoding encoding=new WorkflowEncoding(new ObjectMapper());
    @Test void maximumBatchAndScenarioCountsKeepTheProgramReportConsumableByDownstreamNodes() {
        String hash="a".repeat(64),writer=UUID.randomUUID().toString();
        var module=new SourceTestProfile.Module(".","junit",List.of("src/Calculator.java"),List.of("src/test/java"),List.of(),List.of("mvn","test"));
        var source=new WorkflowSourceSnapshot.Reference(1,UUID.randomUUID().toString(),hash);
        var fixed=new WorkflowTestInputs.Context(input("source",WorkflowGraph.DataKind.DOCUMENT,"source",source),null,
                new WorkflowTestProfile.Frozen(1,WorkflowTestProfile.TYPE,"source",source,new SourceTestProfile(hash,List.of(module))),null);
        var scenarios=IntStream.range(0,64).mapToObj(i->new WorkflowTestDesign.Scenario(String.format("s%02d",i)+"x".repeat(77),"src/Calculator.java","NORMAL","场景",List.of("执行"),"返回结果",List.of())).toList();
        var design=new WorkflowTestDesign.Frozen(1,WorkflowTestDesign.TYPE,"source",source,"profile",hash,new WorkflowTestDesign.Candidate("设计","说明",scenarios,List.of()));
        var batches=IntStream.range(0,61).mapToObj(i->new WorkflowNativeTestContract.Batch(input(i==0?"design":"design_"+i,WorkflowGraph.DataKind.JSON,UUID.randomUUID().toString(),design),design,writer)).toList();
        var lineage=new ArrayList<String>();lineage.add(writer);IntStream.range(0,255).forEach(i->lineage.add(UUID.randomUUID().toString()));
        var context=new WorkflowNativeTestContract.Context(fixed,design,input("code",WorkflowGraph.DataKind.CODE,writer,new WorkflowCodeSnapshot.Reference(1,UUID.randomUUID().toString(),hash)),module,null,batches,lineage);
        var evidence=mock(WorkflowNativeTestEvidence.class);var result=new Result(hash,Identity.current(),0,true,false,false,false,true,"","",List.of());
        when(evidence.report("test",result)).thenReturn(new WorkflowNativeTestReports.Report(true,"已保存实际报告",new WorkflowNativeTestReports.Counts(1,1,0,0),List.of(),true));
        when(evidence.request("test",result)).thenReturn(new Request(UUID.randomUUID().toString(),directory.toString(),List.of("mvn","test"),60));
        var contract=new WorkflowNativeTestContract(null,null,null,null,null,encoding,evidence,null);
        var node=new WorkflowGraph.Node("test","最终回归",WorkflowGraph.NodeKind.SYSTEM,WorkflowNativeTest.MODULE,2,null,"实际测试",List.of(),List.of(),List.of("PASS","FAIL"),new WorkflowGraph.Completion(WorkflowGraph.CompletionKind.VERIFIED,"通过",null),0,false,Map.of());
        var evaluated=contract.evaluate("test",node,context,result);assertThat(evaluated.success()).isTrue();
        assertThat(encoding.encode(evaluated.delivery()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(WorkflowDeliveries.limit(node.moduleId()));
        var report=evaluated.delivery().outputs().get("report").content();assertThat(report.path("batches").size()).isEqualTo(61);
        for(var batch:report.path("batches")){assertThat(batch.path("scenarioCount").asInt()).isEqualTo(64);assertThat(batch.has("scenarioIds")).isFalse();}
    }
    private WorkflowDelivery.Input input(String name,WorkflowGraph.DataKind kind,String attempt,Object value) {
        return new WorkflowDelivery.Input(name,kind,"NODE",UUID.randomUUID().toString(),name,attempt,"a".repeat(64),encoding.decode(encoding.encode(value),tools.jackson.databind.JsonNode.class));
    }
}
