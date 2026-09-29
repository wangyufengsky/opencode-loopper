package io.opencode.loopper.template;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.template.SnapshotReview.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SnapshotReviewPartialCompilerTest {
    @Test void partialCoverageCountsUnitsOnceAndKeepsEveryIndependentOpinion() {
        var finding=finding("candidate");var analysis=new Analysis(List.of(new Coverage("done","已分析",List.of(),List.of("单元局限"))),List.of(finding),List.of(),List.of("分析局限"));
        var batches=List.of(new SnapshotReviewPartialCompiler.Batch("one","第一分析",analysis),new SnapshotReviewPartialCompiler.Batch("two","第二分析",analysis));
        var reviews=List.of(judgment("r1","第一复核","one","candidate",Verdict.SUPPORTED,null),judgment("r2","第二复核","one","candidate",Verdict.DISMISSED,null),judgment("r3","第三复核","two","candidate",Verdict.UNDETERMINED,null));
        var report=SnapshotReviewPartialCompiler.compile(snapshot(),"now","已取消","只读观察",batches,reviews,refs->"作者依据\n");
        assertThat(report.analyzedUnits()).isEqualTo(1);assertThat(report.pendingUnits()).isEqualTo(1);assertThat(report.excludedUnits()).isEqualTo(1);
        assertThat(report.content()).contains("第一复核：独立复核支持","第二复核：复核不成立","第三复核：复核待确认","单元局限","分析局限","复核局限","作者依据","非完整报告");
    }
    @Test void duplicateEvidenceRemainsVisibleWithoutInventingMissingSupport() {
        var analysis=new Analysis(List.of(new Coverage("done","已分析",List.of(),List.of())),List.of(finding("a"),finding("b")),List.of(),List.of());
        var batch=new SnapshotReviewPartialCompiler.Batch("one","第一批",analysis);
        var review=new Review(List.of("done"),List.of(new Decision("a",Verdict.SUPPORTED,"有证据",null,List.of()),new Decision("b",Verdict.DUPLICATE,"重复依据","a",List.of())),List.of(),"结论",List.of());
        var own=new SnapshotReviewPartialCompiler.Judgment("r1","复核甲","one",review);
        var report=SnapshotReviewPartialCompiler.compile(snapshot(),"now","已暂停","",List.of(batch),List.of(own),refs->"");
        assertThat(report.content()).contains("复核判定重复","重复依据","合并目标：复核甲 / 问题 a");
        var external=judgment("r2","复核乙","one","b",Verdict.DUPLICATE,"removed-private-attempt/key");
        var missing=SnapshotReviewPartialCompiler.compile(snapshot(),"now","已暂停","",List.of(batch),List.of(external),refs->"");
        assertThat(missing.content()).contains("引用的复核不在当前有效结果中","尚未独立复核（仅候选）").doesNotContain("removed-private-attempt","独立复核支持");
    }
    @Test void noFindingsDoesNotClaimAnIndependentReviewOrCompleteCoverage() {
        var analysis=new Analysis(List.of(new Coverage("done","已分析",List.of(),List.of())),List.of(),List.of(),List.of());
        var report=SnapshotReviewPartialCompiler.compile(snapshot(),"now","执行中","",List.of(new SnapshotReviewPartialCompiler.Batch("private","分析 <script>",analysis)),List.of(),refs->"");
        assertThat(report.content()).contains("本批未报告候选问题，未经独立复核","尚未完成分析","本轮未执行目标项目测试").doesNotContain("<script>","private");
    }
    private static Finding finding(String key){return new Finding(key,TemplateAnalysis.Severity.HIGH,"问题 "+key,"触发","错误","建议",Attribution.UNDETERMINED,List.of(new Reference("target","code.java","blob",1,1,"code")));}
    private static SnapshotReviewPartialCompiler.Judgment judgment(String id,String title,String analysis,String key,Verdict verdict,String duplicate){return new SnapshotReviewPartialCompiler.Judgment(id,title,analysis,new Review(List.of("done"),List.of(new Decision(key,verdict,"判断依据",duplicate,List.of())),List.of(),"结论",List.of("复核局限")));}
    private static Snapshot snapshot(){return new Snapshot("source",null,"target",null,"tree","now",null,null,"FULL",false,false,List.of(),List.of(new Unit("done","code.java",null,"FULL","code",null),new Unit("pending","todo.java",null,"FULL","later",null),new Unit("excluded",".env",null,"FULL","","敏感文件")));}
}
