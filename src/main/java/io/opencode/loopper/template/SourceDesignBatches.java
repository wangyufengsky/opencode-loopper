package io.opencode.loopper.template;

import java.util.*;

/** Stable, complete partition used by both the legacy template and canvas planning. */
public final class SourceDesignBatches {
    private SourceDesignBatches(){ }
    public record File(String path,long sizeBytes) { }
    public static SourceDesign.Plan partition(List<File> files) {
        var batches=new ArrayList<SourceDesign.Batch>();var batch=new ArrayList<String>();
        String directory=null;long bytes=0;
        for(var file:files) {
            int separator=file.path().lastIndexOf('/');
            String parent=separator<0?".":file.path().substring(0,separator);
            if(!batch.isEmpty()&&(batch.size()>=12||bytes+file.sizeBytes()>160000||!parent.equals(directory))) {
                batches.add(new SourceDesign.Batch(batches.size(),directory,List.copyOf(batch)));batch.clear();bytes=0;
            }
            directory=parent;batch.add(file.path());bytes+=file.sizeBytes();
        }
        if(!batch.isEmpty())batches.add(new SourceDesign.Batch(batches.size(),directory,List.copyOf(batch)));
        return new SourceDesign.Plan(List.copyOf(batches));
    }
}
