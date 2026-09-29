package io.opencode.loopper.template;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DocumentAssessmentBatchesTest {
    @Test void partitionsEveryChapterInOrderWithIndependentCharacterAndCountLimits() {
        var chapters=new ArrayList<DocumentAssessmentBatches.Chapter>();
        IntStream.rangeClosed(1,257).forEach(i->chapters.add(chapter(i,0)));
        chapters.add(chapter(258,48000));chapters.add(chapter(259,48001));chapters.add(chapter(260,1));
        var batches=DocumentAssessmentBatches.partition(chapters);
        assertThat(batches).extracting(b->b.sections().size()).containsExactly(256,2,1,1);
        assertThat(batches).extracting(DocumentAssessmentBatches.Batch::ordinal).containsExactly(0,1,2,3);
        assertThat(batches.stream().flatMap(b->b.sections().stream()).toList()).containsExactlyElementsOf(chapters.stream().map(DocumentAssessmentBatches.Chapter::source).toList());
    }
    @Test void duplicatesAndUnknownLengthsAreRejectedAndLargeChapterIsNotTruncated() {
        assertThatThrownBy(()->DocumentAssessmentBatches.partition(List.of(chapter(1,2),chapter(1,2)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->DocumentAssessmentBatches.partition(List.of(chapter(1,-1)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(DocumentAssessmentBatches.partition(List.of(chapter(1,Integer.MAX_VALUE))).getFirst().characters()).isEqualTo(Integer.MAX_VALUE);
    }
    private DocumentAssessmentBatches.Chapter chapter(int id,int size){return new DocumentAssessmentBatches.Chapter(new DirectDocumentAssessment.Source("DOC-1",id),size);}
}
