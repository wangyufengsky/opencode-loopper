package io.opencode.loopper.template;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceDesignBatchesTest {
    @Test void allTargetsRetainTheirFrozenOrderAcrossFileAndDirectoryBoundaries() {
        var files=new ArrayList<SourceDesignBatches.File>();
        for(int i=0;i<13;i++)files.add(new SourceDesignBatches.File("src/File"+i+".java",1));
        files.add(new SourceDesignBatches.File("other/Last.java",1));
        var plan=SourceDesignBatches.partition(files);
        assertThat(plan.batches()).extracting(batch->batch.paths().size()).containsExactly(12,1,1);
        assertThat(plan.batches().stream().flatMap(batch->batch.paths().stream()).toList()).isEqualTo(files.stream().map(SourceDesignBatches.File::path).toList());
        assertThat(plan.batches()).extracting(SourceDesign.Batch::ordinal).containsExactly(0,1,2);
    }
    @Test void byteBoundaryAndSingleOversizedFileRemainComplete() {
        var files=List.of(new SourceDesignBatches.File("A.java",80000),new SourceDesignBatches.File("B.java",80000),
                new SourceDesignBatches.File("C.java",160001),new SourceDesignBatches.File("D.java",1));
        var plan=SourceDesignBatches.partition(files);
        assertThat(plan.batches()).extracting(SourceDesign.Batch::paths).containsExactly(List.of("A.java","B.java"),List.of("C.java"),List.of("D.java"));
        assertThat(SourceDesignBatches.partition(List.of()).batches()).isEmpty();
    }
}
