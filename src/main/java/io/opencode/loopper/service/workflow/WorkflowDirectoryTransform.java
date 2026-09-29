package io.opencode.loopper.service.workflow;

import io.opencode.loopper.workflow.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Resumable exact-file transformation; durable intent and writer ownership belong to its caller. */
@Component
public final class WorkflowDirectoryTransform {
    private final WorkflowDirectoryFiles files;
    public WorkflowDirectoryTransform(WorkflowDirectoryFiles files){this.files=files;}
    public void apply(String id,WorkflowDirectorySnapshot before,WorkflowDirectorySnapshot after,
            Function<WorkflowCodeSnapshot.File,byte[]> read,Supplier<WorkflowDirectorySnapshot> inspect) {
        GitDirectoryTrees.outsideTransaction();requireId(id);requireCompatible(before,after,inspect.get());
        if(!obstructions(before,after).isEmpty())throw GitDirectoryTrees.invalid();
        var old=index(before.files());var next=index(after.files());
        for(var file:after.files()) {
            String name=temporary(id,file);if(old.containsKey(name)||next.containsKey(name))throw GitDirectoryTrees.invalid();
            Path pending=path(before,name);if(Files.exists(pending,LinkOption.NOFOLLOW_LINKS))verifyTemporary(pending,read.apply(file));
        }
        for(var file:before.files()) if(!next.containsKey(file.path())) {
            files.requireIdentity(before);Path target=path(before,file.path());
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)&&!Files.isDirectory(target,LinkOption.NOFOLLOW_LINKS)) {
                requireCurrent(before,file);try{Files.delete(target);}catch(IOException failure){throw GitDirectoryTrees.invalid();}
            }
        }
        for(var file:after.files())replace(id,read,before,file,old.get(file.path()));
        if(!inspect.get().equals(after))throw GitDirectoryTrees.invalid();
        for(var file:after.files()) {
            Path pending=path(before,temporary(id,file));if(!Files.exists(pending,LinkOption.NOFOLLOW_LINKS))continue;
            verifyTemporary(pending,read.apply(file));try{Files.delete(pending);}catch(IOException failure){throw GitDirectoryTrees.invalid();}
        }
        files.requireIdentity(after);
    }
    public void requireCompatible(WorkflowDirectorySnapshot before,WorkflowDirectorySnapshot after,WorkflowDirectorySnapshot current) {
        GitDirectoryTrees.outsideTransaction();files.requireIdentity(before);files.requireIdentity(after);files.requireIdentity(current);
        if(!before.canonicalRoot().equals(after.canonicalRoot())||!before.canonicalRoot().equals(current.canonicalRoot())
                ||!before.rootFingerprint().equals(after.rootFingerprint())||!before.rootFingerprint().equals(current.rootFingerprint()))throw GitDirectoryTrees.invalid();
        GitDirectoryTrees.validate(before.files());GitDirectoryTrees.validate(after.files());GitDirectoryTrees.validate(current.files());
        var old=index(before.files());var next=index(after.files());var actual=index(current.files());
        for(var file:current.files())if(!file.equals(old.get(file.path()))&&!file.equals(next.get(file.path())))throw GitDirectoryTrees.invalid();
        for(String name:old.keySet())if(next.containsKey(name)&&!actual.containsKey(name))throw GitDirectoryTrees.invalid();
    }
    public static Set<String> temporaryPaths(String id,WorkflowDirectorySnapshot after) {
        requireId(id);var result=new TreeSet<String>();for(var file:after.files())result.add(temporary(id,file));return Set.copyOf(result);
    }
    /** Replacing a directory by a file must never discard ignored or protected descendants. */
    public List<String> obstructions(WorkflowDirectorySnapshot before,WorkflowDirectorySnapshot after) {
        GitDirectoryTrees.outsideTransaction();files.requireIdentity(before);files.requireIdentity(after);
        GitDirectoryTrees.validate(before.files());GitDirectoryTrees.validate(after.files());
        if(!before.canonicalRoot().equals(after.canonicalRoot())||!before.rootFingerprint().equals(after.rootFingerprint()))throw GitDirectoryTrees.invalid();
        var old=index(before.files());var next=index(after.files());var conflicts=new TreeSet<String>();int count=0;
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(30).toNanos();Path root=Path.of(before.canonicalRoot());
        for(var file:after.files()) {
            Path target=path(before,file.path());if(!Files.isDirectory(target,LinkOption.NOFOLLOW_LINKS))continue;
            try(var entries=Files.walk(target)) {
                var iterator=entries.iterator();while(iterator.hasNext()) {
                    Path entry=iterator.next();if(++count>50_000||System.nanoTime()>deadline)throw GitDirectoryTrees.invalid();GitDirectoryTrees.safe(entry);
                    if(Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS))continue;String name=root.relativize(entry).toString().replace('\\','/');
                    if(!Files.isRegularFile(entry,LinkOption.NOFOLLOW_LINKS)||!old.containsKey(name)||next.containsKey(name))conflicts.add(name);
                }
            }catch(IOException failure){throw GitDirectoryTrees.invalid();}
        }
        files.requireIdentity(before);return List.copyOf(conflicts);
    }
    private static void requireId(String id){if(id==null||!id.matches("[A-Za-z0-9-]{1,100}"))throw GitDirectoryTrees.invalid();}
    private void replace(String id,java.util.function.Function<WorkflowCodeSnapshot.File,byte[]> read,WorkflowDirectorySnapshot before,WorkflowCodeSnapshot.File after,WorkflowCodeSnapshot.File original) {
        files.requireIdentity(before);Path target=path(before,after.path());
        if(matches(before,after))return;
        if(original!=null)requireCurrent(before,original);
        else if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)&&!Files.isDirectory(target,LinkOption.NOFOLLOW_LINKS))throw GitDirectoryTrees.invalid();
        byte[] bytes=read.apply(after);GitDirectoryTrees.requireBytes(after,bytes);
        Path temporary=path(before,temporary(id,after));
        try {
            emptyDirectories(target);
            GitDirectoryTrees.safe(target.getParent());Files.createDirectories(target.getParent());GitDirectoryTrees.safe(target);
            int already=verifyTemporary(temporary,bytes);
            try(var out=Files.newOutputStream(temporary,StandardOpenOption.CREATE,StandardOpenOption.APPEND,LinkOption.NOFOLLOW_LINKS)) {
                out.write(bytes,already,bytes.length-already);
            }
            try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){channel.force(true);}
            if(verifyTemporary(temporary,bytes)!=bytes.length)throw GitDirectoryTrees.invalid();
            permissions(temporary,target,after.mode());files.requireIdentity(before);GitDirectoryTrees.safe(target);
            if(original!=null)requireCurrent(before,original);
            else if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw GitDirectoryTrees.invalid();
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            if(!matches(before,after))throw GitDirectoryTrees.invalid();
        }catch(IOException failure){throw GitDirectoryTrees.invalid();}
    }
    static int verifyTemporary(Path temporary,byte[] expected) {
        GitDirectoryTrees.safe(temporary);if(!Files.exists(temporary,LinkOption.NOFOLLOW_LINKS))return 0;
        if(!Files.isRegularFile(temporary,LinkOption.NOFOLLOW_LINKS))throw GitDirectoryTrees.invalid();
        try(var stream=Files.newInputStream(temporary,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=stream.readNBytes(expected.length+1);
            if(bytes.length>expected.length||!Arrays.equals(bytes,Arrays.copyOf(expected,bytes.length)))throw GitDirectoryTrees.invalid();
            return bytes.length;
        }catch(IOException failure){throw GitDirectoryTrees.invalid();}
    }
    private boolean matches(WorkflowDirectorySnapshot scope,WorkflowCodeSnapshot.File expected) {
        Path path=path(scope,expected.path());if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))return false;
        try {
            files.read(new WorkflowDirectorySnapshot(1,scope.canonicalRoot(),scope.rootFingerprint(),List.of(expected)),expected);return true;
        }catch(io.opencode.loopper.service.ConflictException changed){return false;}
    }
    private void requireCurrent(WorkflowDirectorySnapshot scope,WorkflowCodeSnapshot.File expected) { if(!matches(scope,expected))throw GitDirectoryTrees.invalid(); }
    private static Path path(WorkflowDirectorySnapshot scope,String relative) {
        Path root=Path.of(scope.canonicalRoot()),path=root.resolve(relative).normalize();
        if(!path.startsWith(root)||path.equals(root))throw GitDirectoryTrees.invalid();GitDirectoryTrees.safe(path);return path;
    }
    static String temporary(String id,WorkflowCodeSnapshot.File file) {
        int slash=file.path().lastIndexOf('/');String parent=slash<0?"":file.path().substring(0,slash+1);
        return parent+".loopper-"+id+"-"+WorkflowEncoding.hash(file.path()).substring(0,24)+".pending";
    }
    private static Map<String,WorkflowCodeSnapshot.File> index(List<WorkflowCodeSnapshot.File> values) {
        var result=new TreeMap<String,WorkflowCodeSnapshot.File>();values.forEach(file->result.put(file.path(),file));return result;
    }
    private static void emptyDirectories(Path target)throws IOException {
        if(!Files.isDirectory(target,LinkOption.NOFOLLOW_LINKS))return;
        List<Path> entries;
        try(var paths=Files.walk(target)){entries=paths.limit(50_001).toList();}
        if(entries.size()>50_000)throw GitDirectoryTrees.invalid();
        for(var entry:entries){GitDirectoryTrees.safe(entry);if(!Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS))throw GitDirectoryTrees.invalid();}
        for(var entry:entries.stream().sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList())Files.delete(entry);
    }
    private static void permissions(Path temporary,Path target,String mode)throws IOException {
        if(System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win"))return;
        var permissions=Files.getPosixFilePermissions(Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)?target:temporary,LinkOption.NOFOLLOW_LINKS);
        if(mode.equals("100755"))permissions.add(PosixFilePermission.OWNER_EXECUTE);else permissions.remove(PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(temporary,permissions);
    }
}
