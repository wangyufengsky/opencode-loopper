package io.opencode.loopper.template;

import io.opencode.loopper.template.SnapshotReview.*;
import java.util.*;

/** A negative conclusion is reusable only with the full initial evidence and no unresolved claim. */
public final class SnapshotReviewReusePolicy {
    private SnapshotReviewReusePolicy(){ }
    public static boolean eligible(Analysis output){
        return output!=null&&output.findings().isEmpty()&&output.supplements().isEmpty()&&output.limitations().isEmpty()
            &&!output.coverage().isEmpty()&&output.coverage().stream().allMatch(c->c.limitations().isEmpty()&&c.evidence().isEmpty());
    }
    public static boolean closed(Input input){return input.compact()&&!input.units().isEmpty()&&input.units().stream().allMatch(u->u.limitation()==null&&u.initialEvidence()!=null&&!u.initialEvidence().isEmpty());}
    public static List<?> materials(Input input){return input.units().stream().map(u->Arrays.asList(u.path(),u.beforePath(),u.change(),u.excerpt(),
        u.initialEvidence().stream().map(r->List.of(r.path(),r.blob(),r.startLine(),r.endLine(),r.quote())).toList())).toList();}
    public static Optional<Analysis> remap(Analysis original,Input source,Input target){
        if(!eligible(original)||!closed(source)||!closed(target)||!materials(source).equals(materials(target)))return Optional.empty();
        var mapping=new HashMap<String,String>();for(int i=0;i<source.units().size();i++)mapping.put(source.units().get(i).id(),target.units().get(i).id());
        if(mapping.size()!=source.units().size()||original.coverage().size()!=mapping.size()
            ||!new HashSet<>(original.coverage().stream().map(Coverage::unitId).toList()).equals(mapping.keySet()))return Optional.empty();
        return Optional.of(new Analysis(original.coverage().stream().map(c->new Coverage(mapping.get(c.unitId()),c.conclusion(),List.of(),List.of())).toList(),List.of(),List.of(),List.of()));
    }
}
