package io.opencode.loopper.template;

import java.util.*;

/** Ordered, lossless batching; one oversized chapter remains visible as its own batch. */
public final class DocumentAssessmentBatches {
    private DocumentAssessmentBatches(){ }
    public record Chapter(DirectDocumentAssessment.Source source,int characters){ }
    public record Batch(int ordinal,List<DirectDocumentAssessment.Source> sections,long characters){public Batch{sections=List.copyOf(sections);}}
    public static List<Batch> partition(List<Chapter> chapters) {
        var result=new ArrayList<Batch>();var current=new ArrayList<DirectDocumentAssessment.Source>();
        var seen=new HashSet<DirectDocumentAssessment.Source>();long size=0;
        for(var chapter:chapters) {
            if(chapter.source()==null||chapter.characters()<0||!seen.add(chapter.source()))throw new IllegalArgumentException("原文章节重复或长度无效");
            if(!current.isEmpty()&&(current.size()==256||size+chapter.characters()>48000)) {
                result.add(new Batch(result.size(),current,size));current=new ArrayList<>();size=0;
            }
            current.add(chapter.source());size+=chapter.characters();
        }
        if(!current.isEmpty())result.add(new Batch(result.size(),current,size));return List.copyOf(result);
    }
}
