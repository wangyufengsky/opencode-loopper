package io.opencode.loopper.workflow;

import static org.assertj.core.api.Assertions.*;
import static io.opencode.loopper.workflow.WorkflowGraph.*;
import io.opencode.loopper.template.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorkflowHistoryAnalysisTest {
    final WorkflowHistorySnapshot.Reference source=new WorkflowHistorySnapshot.Reference(1,"GIT_HISTORY","source","a".repeat(64));
    @Test void partitionCoverageAndSourceIdentityCannotBeReplacedOrDuplicated() {
        var evidence=evidence();var first=prepare(node(false,0),evidence,List.of());var second=prepare(node(false,1),evidence,List.of());
        assertThat(first.units()).hasSize(12);assertThat(second.units()).hasSize(1);assertThat(first.batchCount()).isEqualTo(2);
        var one=review(first);var two=review(second);
        assertThatThrownBy(()->prepare(node(true,0),evidence,List.of(one))).hasMessageContaining("覆盖本人全部变更");
        assertThatThrownBy(()->prepare(node(true,0),evidence,List.of(one,one,two))).hasMessageContaining("重复绑定");
        var wrong=new WorkflowHistoryAnalysis.Review(1,one.type(),new WorkflowHistorySnapshot.Reference(1,"GIT_HISTORY","other","b".repeat(64)),0,2,one.reviews(),one.locations());
        assertThatThrownBy(()->prepare(node(true,0),evidence,List.of(wrong,two))).hasMessageContaining("不一致");
        var person=prepare(node(true,0),evidence,List.of(one,two));assertThat(person.units()).hasSize(13);assertThat(person.reviews()).hasSize(13);
        assertThat(person.person().effectiveLines()).isEqualTo(13);assertThat(person.person().evidenceIds()).doesNotContain("foreign");
    }
    @Test void noCommitsAndUnknownBatchDoNotInventModelWork() {
        var evidence=evidence();assertThatThrownBy(()->prepare(node(false,2),evidence,List.of())).hasMessageContaining("批次不存在");
        var empty=new TemplateGitEvidence(evidence.version(),evidence.branchId(),evidence.head(),evidence.startDate(),evidence.endDate(),evidence.timezone(),null,List.of());
        assertThatThrownBy(()->prepare(node(false,0),empty,List.of())).hasMessageContaining("空范围无需模型");
        assertThatThrownBy(()->prepare(node(true,0),empty,List.of())).hasMessageContaining("不存在该贡献者");
    }
    @Test void reviewMustCoverExactUnitsWithAddressesAndContributionMustUseOwnedEvidence() {
        var input=prepare(node(false,0),evidence(),List.of());var accepted=review(input);
        var values=new ArrayList<>(accepted.reviews());values.set(0,new TemplateAnalysis.UnitReview("foreign","摘要",List.of(),List.of()));
        assertThatThrownBy(()->TemplateAnalysisValidation.batch(input.units(),new TemplateAnalysis.BatchCandidate(values))).hasMessageContaining("未知证据");
        var assessment=new ContributionScore.Assessment(2,"理由",List.of("foreign"));
        assertThatThrownBy(()->TemplateAnalysisValidation.contributor("person",Set.of("own"),new TemplateAnalysis.ContributorCandidate("person","概述",assessment,assessment,assessment,assessment))).hasMessageContaining("其他贡献者");
    }
    private WorkflowHistoryAnalysis.Input prepare(Node node,TemplateGitEvidence evidence,List<WorkflowHistoryAnalysis.Review> reviews){return WorkflowHistoryAnalysis.prepare(node,source,"producer",evidence,reviews,java.util.stream.IntStream.range(0,reviews.size()).mapToObj(i->"review"+i).toList());}
    private WorkflowHistoryAnalysis.Review review(WorkflowHistoryAnalysis.Input input){return new WorkflowHistoryAnalysis.Review(1,WorkflowHistoryAnalysis.REVIEW_TYPE,source,input.batchOrdinal(),input.batchCount(),input.units().stream().map(u->new TemplateAnalysis.UnitReview(u.id(),"审查",List.of(),List.of())).toList(),WorkflowHistoryAnalysis.locations(input));}
    private Node node(boolean contribution,int ordinal) {
        var inputs=new ArrayList<Input>();inputs.add(new Input("source",InputSource.NODE,"source","source",DataKind.DOCUMENT,true));if(contribution)inputs.add(new Input("review",InputSource.NODE,"review","analysis",DataKind.JSON,true));
        return new Node("analysis","分析",NodeKind.WORK,contribution?WorkflowHistoryAnalysis.CONTRIBUTION:WorkflowHistoryAnalysis.REVIEW,1,"role","任务",inputs,
                List.of(new Output("summary","说明",DataKind.TEXT,true),new Output("analysis","分析",DataKind.JSON,true)),List.of(),new Completion(CompletionKind.DELIVERABLES,"完成",null),0,false,Map.of("historyBatchOrdinal",String.valueOf(ordinal),"historyContributorEmail","person"),"role-version");
    }
    private TemplateGitEvidence evidence() {
        var changes=new ArrayList<TemplateGitEvidence.Change>();for(int i=0;i<13;i++)changes.add(new TemplateGitEvidence.Change("e"+i,"File"+i,"b","a",1,0,false,1,null,"@@ -0,0 +1 @@\n+code\n"));
        var commit=new TemplateGitEvidence.Commit("a".repeat(40),List.of(),"2026-09-11T00:00:00Z","fixture",List.of(new TemplateGitEvidence.Contributor("person","作者","person",false)),"ANALYZE",changes);
        return new TemplateGitEvidence(TemplateGitEvidence.VERSION,"local:refs/heads/main",commit.sha(),"2026-09-11","2026-09-11","Asia/Shanghai",null,List.of(commit));
    }
}
