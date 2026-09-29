package io.opencode.loopper.service.workflow;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.workflow.WorkflowCodeSnapshot;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Materializes an exact accepted tree in a private test directory; no registered checkout is touched. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class WorkflowCommandWorkspace {
    private final ImmutableContentStore directories;
    private final WorkflowCodeSnapshots codes;
    public WorkflowCommandWorkspace(LoopperProperties properties, WorkflowCodeSnapshots codes) {
        directories = new ImmutableContentStore(properties.getDataDir().resolve("workflow-command-workspaces")); this.codes = codes;
    }
    /** Only during PREPARING, before supervisor launch or grant. Never use it to reset an executed workspace. */
    public Path prepare(String attempt, String project, String requirement, String producer, WorkflowCodeSnapshot.Reference reference) {
        var manifest = codes.manifest(project, requirement, producer, reference);
        Path owner = directories.directory(attempt), root = owner.resolve("workspace"), preparing = owner.resolve("preparing");
        try {
            DurableCommandProtocol.check(root); DurableCommandProtocol.check(preparing);
            Files.createDirectories(root); Files.createDirectories(preparing);
            var expected = new HashSet<String>();
            for (var file : manifest.files()) {
                Path path = root.resolve(file.path()).normalize();
                if (!path.startsWith(root) || path.equals(root)) throw invalid();
                DurableCommandProtocol.check(path); Files.createDirectories(path.getParent()); DurableCommandProtocol.check(path);
                byte[] bytes = codes.read(project, requirement, producer, reference, file.path());
                // Atomic and non-overwriting, including a partial prior preparation.
                publish(path, preparing, bytes); expected.add(file.path());
                if (file.mode().equals("100755") && !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                    var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
                    permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE); Files.setPosixFilePermissions(path, permissions);
                }
            }
            try (var files = Files.walk(root)) {
                var found = files.limit(50001).toList(); if (found.size() > 50000) throw invalid();
                for (var path : found) {
                    DurableCommandProtocol.check(path);
                    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !expected.contains(root.relativize(path).toString().replace('\\', '/'))) throw invalid();
                }
            }
            return root;
        } catch (IOException failure) { throw invalid(); }
    }
    /** After proven stop, compare original input files while allowing new native build/dependency outputs. */
    public boolean unchanged(String attempt,String project,String requirement,String producer,WorkflowCodeSnapshot.Reference reference,Path root) {
        var manifest=codes.manifest(project,requirement,producer,reference);
        if(!root.equals(directories.directory(attempt).resolve("workspace")))throw invalid();
        try {
            for(var file:manifest.files()) {
                Path path=root.resolve(file.path()).normalize();if(!path.startsWith(root)||path.equals(root))throw invalid();
                DurableCommandProtocol.check(path);
                if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)!=file.sizeBytes())return false;
                if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")&&Files.isExecutable(path)!=file.mode().equals("100755"))return false;
                var digest=java.security.MessageDigest.getInstance("SHA-256");long total=0;
                try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
                    byte[] buffer=new byte[65536];int read;
                    while((read=input.read(buffer,0,(int)Math.min(buffer.length,file.sizeBytes()-total+1)))!=-1) {
                        total+=read;if(total>file.sizeBytes())return false;digest.update(buffer,0,read);
                    }
                }
                if(total!=file.sizeBytes()||!HexFormat.of().formatHex(digest.digest()).equals(file.sha256()))return false;
            }
            return true;
        }catch(IOException failure){return false;}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void publish(Path path, Path preparing, byte[] bytes) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Path temporary = preparing.resolve(UUID.randomUUID() + ".preparing");
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS);
            try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
            try { Files.createLink(path, temporary); }
            catch (FileAlreadyExistsException raced) { /* Verify the original below. */ }
            finally { Files.deleteIfExists(temporary); }
        }
        DurableCommandProtocol.check(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != bytes.length) throw invalid();
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Arrays.equals(stream.readNBytes(bytes.length + 1), bytes)) throw invalid();
        }
    }
    private static ConflictException invalid() { return new ConflictException("WORKFLOW_COMMAND_WORKSPACE_INVALID", "检查目录与固定交付物不一致，请保留现场处理，不能覆盖后继续。"); }
}
