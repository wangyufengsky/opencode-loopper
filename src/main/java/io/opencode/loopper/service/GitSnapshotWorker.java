package io.opencode.loopper.service;

import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.runtime.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import static io.opencode.loopper.service.GitSnapshotJobProtocol.*;

/** Supervised helper: preserve selection before fetch; expose only typed failures, never remote URLs or stderr. */
public final class GitSnapshotWorker {
    private GitSnapshotWorker() { }
    public static void main(String[] args) {
        try {
            if(args.length!=2)throw new IllegalArgumentException();
            execute(Path.of(args[0]),args[1]);System.out.println("LOOPPER_GIT_SNAPSHOT_READY");
        } catch(TaskFailure failure){fail(failure.code());}
        catch(BadRequestException failure){fail(failure.code());}
        catch(IOException|RuntimeException|LinkageError failure){fail("WORKFLOW_REPOSITORY_EVIDENCE_INVALID");}
    }
    static void execute(Path directory,String expectedHash)throws IOException {
        DurableCommandProtocol.check(directory);
        if(!directory.isAbsolute()||!directory.equals(directory.toRealPath()))throw new IOException("Snapshot directory identity");
        try(var channel=FileChannel.open(directory.resolve("capture.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            var lock=channel.tryLock()) {
            if(lock==null)throw new IOException("Snapshot already owned");
            byte[] bytes=read(directory.resolve("input"));var input=input(bytes);
            if(!DurableCommandProtocol.hash(bytes).equals(expectedHash)||!directory.getFileName().toString().equals(input.nodeId()))throw new IOException("Snapshot input identity");
            var git=new GitEvidenceProcess(new SafeProcessRunner());var scope=GitCredentialProvider.Scope.fromWorker();
            git.credentialProvider((project,remote)->scope.forRemote(remote));
            var reader=new GitCommitReader(git);Binding selected;
            Path selection=directory.resolve("selection");
            if(Files.exists(selection,LinkOption.NOFOLLOW_LINKS)) {
                selected=binding(read(selection));if(!selected.inputSha256().equals(expectedHash))throw new IOException("Snapshot selection differs");
            } else {
                var source=reader.source(Path.of(input.projectPath()),input.selection());
                selected=Binding.from(expectedHash,source,reader.resolve(source));publish(selection,binding(selected));
            }
            Path snapshot=directory.resolve("snapshot");
            if(Files.exists(snapshot,LinkOption.NOFOLLOW_LINKS)) {
                if(!snapshot(read(snapshot)).binding().equals(selected))throw new IOException("Snapshot result differs");
            }
            var fixed=selected;
            var captured=reader.capture(selected.source(input),directory.resolve("code.git"),selected.commit(),()->{
                var source=reader.source(Path.of(input.projectPath()),input.selection());
                if(!fixed.matches(source))throw new BadRequestException("DOCUMENT_CODE_SOURCE_INVALID","原项目的 Git 归属已改变，不能替换原快照来源");
                return source.remote();
            });
            publish(snapshot,snapshot(new Snapshot(selected,captured)));
        }
    }
    private static void fail(String code) {
        System.out.println("LOOPPER_GIT_SNAPSHOT_FAILURE:"+(code!=null&&code.matches("[A-Z][A-Z0-9_]{0,100}")?code:"WORKFLOW_REPOSITORY_EVIDENCE_INVALID"));
        System.exit(1);
    }
}
