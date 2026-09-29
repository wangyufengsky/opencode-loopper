package io.opencode.loopper.service.workflow;

import io.opencode.loopper.workflow.*;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Persisted before/after manifests own every path; one OS lock excludes concurrent recovery writers. */
@Component
public final class WorkflowWritebackFiles {
    private final WorkflowDirectoryFiles files;
    private final WorkflowDirectoryStorage storage;
    private final WorkflowDirectoryTransform transform;
    public WorkflowWritebackFiles(WorkflowDirectoryFiles files,WorkflowDirectoryStorage storage,WorkflowDirectoryTransform transform){this.files=files;this.storage=storage;this.transform=transform;}
    public Guard lock(String id) {
        GitDirectoryTrees.outsideTransaction();Path directory=storage.repository(id).getParent();GitDirectoryTrees.safe(directory);
        FileChannel channel=null;
        try {
            Files.createDirectories(directory);GitDirectoryTrees.safe(directory);Path lock=directory.resolve("writeback.lock");GitDirectoryTrees.safe(lock);
            channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            FileLock held;try{held=channel.tryLock();}catch(OverlappingFileLockException busy){held=null;}
            if(held==null){channel.close();return null;}return new Guard(channel,held);
        }catch(IOException|RuntimeException failure){if(channel!=null)try{channel.close();}catch(IOException ignored){/* No project writes have begun. */}throw GitDirectoryTrees.invalid();}
    }
    public static final class Guard implements AutoCloseable {
        private final FileChannel channel;private final FileLock lock;
        private Guard(FileChannel channel,FileLock lock){this.channel=channel;this.lock=lock;}
        @Override public void close(){try{try{lock.release();}finally{channel.close();}}catch(IOException failure){throw GitDirectoryTrees.invalid();}}
    }
    public void prepare(String id,WorkflowWriteback.Intent intent) {
        GitDirectoryTrees.outsideTransaction();requireNoTemporary(id,intent);if(!inspect(id,intent).equals(intent.before()))throw GitDirectoryTrees.invalid();
        if(!transform.obstructions(intent.before(),intent.after()).isEmpty())throw GitDirectoryTrees.invalid();
        storage.copy(id,intent.before());var before=index(intent.before().files());var source=index(intent.source().files());
        storage.importFiles(id,intent.after(),file->{
            if(file.equals(before.get(file.path())))return storage.read(id,file);
            if(!file.equals(source.get(file.path())))throw GitDirectoryTrees.invalid();return storage.read(intent.selection().attempt(),file);
        });
        requireNoTemporary(id,intent);if(!inspect(id,intent).equals(intent.before()))throw GitDirectoryTrees.invalid();
    }
    public void apply(String id,WorkflowWriteback.Intent intent) {
        GitDirectoryTrees.outsideTransaction();
        // A missing/damaged backup must not be silently recaptured from a partially applied directory.
        for(var file:intent.before().files())storage.read(id,file);
        for(var file:intent.after().files())storage.read(id,file);
        transform.apply(id,intent.before(),intent.after(),file->storage.read(id,file),()->inspect(id,intent));
        if(!inspect(id,intent).equals(intent.after()))throw GitDirectoryTrees.invalid();
    }
    public WorkflowDirectorySnapshot inspect(String id,WorkflowWriteback.Intent intent) {
        GitDirectoryTrees.outsideTransaction();var tracked=index(intent.before().files());tracked.putAll(index(intent.after().files()));
        Path root=Path.of(intent.before().canonicalRoot());
        tracked.values().removeIf(file->!Files.exists(root.resolve(file.path()),LinkOption.NOFOLLOW_LINKS)||Files.isDirectory(root.resolve(file.path()),LinkOption.NOFOLLOW_LINKS));
        var result=files.discover(root,Path.of(intent.objectRepository()),storage.dataDirectory(),new ArrayList<>(tracked.values()),WorkflowDirectoryTransform.temporaryPaths(id,intent.after()));
        files.requireIdentity(intent.before());return result;
    }
    private static void requireNoTemporary(String id,WorkflowWriteback.Intent intent) {
        Path root=Path.of(intent.before().canonicalRoot());
        for(String name:WorkflowDirectoryTransform.temporaryPaths(id,intent.after()))if(Files.exists(root.resolve(name),LinkOption.NOFOLLOW_LINKS))throw GitDirectoryTrees.invalid();
    }
    private static Map<String,WorkflowCodeSnapshot.File> index(List<WorkflowCodeSnapshot.File> files){var result=new TreeMap<String,WorkflowCodeSnapshot.File>();files.forEach(file->result.put(file.path(),file));return result;}
}
