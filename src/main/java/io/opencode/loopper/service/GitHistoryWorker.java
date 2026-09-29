package io.opencode.loopper.service;

import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.runtime.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import static io.opencode.loopper.service.GitSnapshotJobProtocol.*;

/** Runs only after a durable supervisor grant; the selected commit survives failure and source movement. */
public final class GitHistoryWorker {
    private GitHistoryWorker() { }
    public static void main(String[] args) {
        try {
            if(args.length!=2)throw new IllegalArgumentException();
            execute(Path.of(args[0]),args[1]);System.out.println("LOOPPER_GIT_HISTORY_READY");
        }catch(TaskFailure failure){fail(failure.code());}
        catch(BadRequestException failure){fail(failure.code());}
        catch(GitHistoryEvidenceCodec.LimitException failure){fail("TEMPLATE_EVIDENCE_LIMIT");}
        catch(IOException|RuntimeException|LinkageError failure){fail("WORKFLOW_HISTORY_EVIDENCE_INVALID");}
    }
    static void execute(Path directory,String expectedHash)throws IOException {
        DurableCommandProtocol.check(directory);
        if(!directory.isAbsolute()||!directory.equals(directory.toRealPath()))throw new IOException("History directory identity");
        try(var channel=FileChannel.open(directory.resolve("capture.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            var lock=channel.tryLock()) {
            if(lock==null)throw new IOException("History already owned");
            byte[] bytes=read(directory.resolve("input"));var input=GitHistoryJobProtocol.input(bytes);var sourceInput=input.source();
            if(!DurableCommandProtocol.hash(bytes).equals(expectedHash)||!directory.getFileName().toString().equals(input.nodeId()))throw new IOException("History input identity");
            var git=new GitEvidenceProcess(new SafeProcessRunner());var scope=GitCredentialProvider.Scope.fromWorker();
            git.credentialProvider((project,remote)->scope.forRemote(remote));git.requireSupported(directory);
            var reader=new GitCommitReader(git);Binding selected;Path selection=directory.resolve("selection");
            if(Files.exists(selection,LinkOption.NOFOLLOW_LINKS)) {
                selected=binding(read(selection));if(!selected.inputSha256().equals(expectedHash))throw new IOException("History selection differs");
            }else {
                var source=reader.source(Path.of(sourceInput.projectPath()),sourceInput.selection());
                if(sourceInput.remote()==null)reader.requireCompleteHistory(source.repository());
                selected=Binding.from(expectedHash,source,reader.resolve(source));publish(selection,binding(selected));
            }
            Path evidence=directory.resolve("evidence");
            if(Files.exists(evidence,LinkOption.NOFOLLOW_LINKS)) {
                var frozen=GitHistoryEvidenceCodec.read(evidence);GitHistoryJobProtocol.requireBinding(input,expectedHash,frozen);
                if(!frozen.binding().equals(selected))throw new IOException("History selection differs");
            }
            var fixed=selected;Path repository=directory.resolve("history.git");
            reader.captureHistory(selected.source(sourceInput),repository,selected.commit(),()->{
                var source=reader.source(Path.of(sourceInput.projectPath()),sourceInput.selection());
                if(!fixed.matches(source))throw new BadRequestException("DOCUMENT_CODE_SOURCE_INVALID","原项目的 Git 归属已改变，不能替换原历史来源");
                if(sourceInput.remote()==null)reader.requireCompleteHistory(source.repository());
                return source.remote();
            });
            var captured=new GitHistoryReader(git).collect(repository,selected.commit(),selected.prefix(),sourceInput.selection().id(),input.dates());
            GitHistoryEvidenceCodec.publish(evidence,new GitHistoryJobProtocol.Frozen(selected,captured));
        }
    }
    private static void fail(String code) {
        System.out.println("LOOPPER_GIT_HISTORY_FAILURE:"+(code!=null&&code.matches("[A-Z][A-Z0-9_]{0,100}")?code:"WORKFLOW_HISTORY_EVIDENCE_INVALID"));
        System.exit(1);
    }
}
