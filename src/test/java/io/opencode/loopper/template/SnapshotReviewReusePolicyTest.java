package io.opencode.loopper.template;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.template.SnapshotReview.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotReviewReusePolicyTest {
    @Test void remapsIdentityButRetainsFrozenMaterialAndCompleteCoverageRequirements(){
        var source=input("old","a","code",null);var target=input("new","b","code",null);var analysis=analysis("old",List.of(),List.of());
        assertThat(SnapshotReviewReusePolicy.remap(analysis,source,target).orElseThrow().coverage().getFirst().unitId()).isEqualTo("new");
        assertThat(SnapshotReviewReusePolicy.remap(analysis,source,input("new","b","changed",null))).isEmpty();
        assertThat(SnapshotReviewReusePolicy.remap(analysis,source,input("new","b","code","缺失正文"))).isEmpty();
        assertThat(SnapshotReviewReusePolicy.remap(analysis("other",List.of(),List.of()),source,target)).isEmpty();
        assertThat(SnapshotReviewReusePolicy.remap(new Analysis(List.of(),List.of(),List.of(),List.of()),source,target)).isEmpty();
        var coverage=analysis.coverage().getFirst();assertThat(SnapshotReviewReusePolicy.remap(new Analysis(List.of(coverage,coverage),List.of(),List.of(),List.of()),source,target)).isEmpty();
    }
    @Test void evidenceAndUncertaintyCannotBeRelabeledAsClosedNegativeAnalysis(){
        var source=input("old","a","code",null);var target=input("new","b","code",null);
        assertThat(SnapshotReviewReusePolicy.remap(analysis("old",source.units().getFirst().initialEvidence(),List.of()),source,target)).isEmpty();
        assertThat(SnapshotReviewReusePolicy.remap(analysis("old",List.of(),List.of("待确认")),source,target)).isEmpty();
        assertThat(SnapshotReviewReusePolicy.remap(new Analysis(analysis("old",List.of(),List.of()).coverage(),List.of(),List.of(),List.of("证据不足")),source,target)).isEmpty();
        assertThat(SnapshotReviewReusePolicy.closed(new Input("SNAPSHOT_ANALYSIS",List.of(new Unit("old","a.txt",null,"ADD","code",null,List.of())),List.of(),List.of(),List.of(),"review",null,SnapshotReview.COMPACT))).isFalse();
    }
    private Input input(String id,String version,String code,String limitation){return new Input("SNAPSHOT_ANALYSIS",List.of(new Unit(id,"a.txt",null,"ADD",code,limitation,List.of(new Reference(version,"a.txt","blob",1,1,code)))),List.of(),List.of(),List.of(),"review",null,SnapshotReview.COMPACT);}
    private Analysis analysis(String id,List<Reference> refs,List<String> limitations){return new Analysis(List.of(new Coverage(id,"已审查",refs,limitations)),List.of(),List.of(),List.of());}
}
