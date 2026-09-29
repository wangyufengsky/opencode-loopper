package io.opencode.loopper.service.workflow;

import io.opencode.loopper.workflow.*;
import java.util.*;

/** Applies the selected result's baseline delta to a current inventory, preserving unrelated user work. */
public final class WorkflowDirectoryChanges {
    private WorkflowDirectoryChanges() { }
    public record Plan(WorkflowDirectorySnapshot before,WorkflowDirectorySnapshot after,List<String> conflicts,
                       int added,int modified,int deleted,int preservedChanges) {
        public Plan {conflicts=List.copyOf(conflicts);}
    }
    public static Plan plan(WorkflowDirectorySnapshot baseline,WorkflowDirectorySnapshot result,WorkflowDirectorySnapshot current) {
        for(var snapshot:List.of(baseline,result,current)) {
            if(snapshot.version()!=1||!snapshot.canonicalRoot().equals(baseline.canonicalRoot())||!snapshot.rootFingerprint().equals(baseline.rootFingerprint()))throw GitDirectoryTrees.invalid();
            GitDirectoryTrees.validate(snapshot.files());
        }
        var old=index(baseline);var wanted=index(result);var actual=index(current);var target=new TreeMap<>(actual);
        var names=new TreeSet<>(old.keySet());names.addAll(wanted.keySet());var changes=new TreeSet<String>();var conflicts=new TreeSet<String>();
        for(String name:names)if(!Objects.equals(old.get(name),wanted.get(name))) {
            changes.add(name);var present=actual.get(name);
            if(!Objects.equals(present,old.get(name))&&!Objects.equals(present,wanted.get(name)))conflicts.add(name);
            else if(wanted.containsKey(name))target.put(name,wanted.get(name));else target.remove(name);
        }
        for(String name:target.keySet()) {
            String ancestor=name;
            while(ancestor.contains("/")){ancestor=ancestor.substring(0,ancestor.lastIndexOf('/'));if(target.containsKey(ancestor)){conflicts.add(ancestor);conflicts.add(name);}}
        }
        int preserved=0;var userNames=new TreeSet<>(actual.keySet());userNames.addAll(old.keySet());
        for(String name:userNames)if(!changes.contains(name)&&!Objects.equals(old.get(name),actual.get(name)))preserved++;
        if(!conflicts.isEmpty())return new Plan(current,null,new ArrayList<>(conflicts),0,0,0,preserved);
        GitDirectoryTrees.validate(new ArrayList<>(target.values()));int added=0,modified=0,deleted=0;
        for(var entry:target.entrySet())if(!actual.containsKey(entry.getKey()))added++;else if(!actual.get(entry.getKey()).equals(entry.getValue()))modified++;
        for(String name:actual.keySet())if(!target.containsKey(name))deleted++;
        return new Plan(current,new WorkflowDirectorySnapshot(1,current.canonicalRoot(),current.rootFingerprint(),new ArrayList<>(target.values())),List.of(),added,modified,deleted,preserved);
    }
    private static TreeMap<String,WorkflowCodeSnapshot.File> index(WorkflowDirectorySnapshot snapshot) {
        var values=new TreeMap<String,WorkflowCodeSnapshot.File>();snapshot.files().forEach(file->values.put(file.path(),file));return values;
    }
}
