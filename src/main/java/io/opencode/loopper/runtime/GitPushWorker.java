package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.TaskFailure;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** A durable supervisor owns this helper. Only controlled status codes can enter its captured output. */
public final class GitPushWorker {
    private GitPushWorker() { }
    public static void main(String[] args) {
        try {
            if(args.length!=2)throw new IllegalArgumentException();Path directory=Path.of(args[0]);DurableCommandProtocol.check(directory);
            try(var channel=FileChannel.open(directory.resolve("push.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);var lock=channel.tryLock()) {
                if(lock==null)throw new IllegalStateException();byte[] bytes=DurableCommandProtocol.read(directory.resolve("input"));var input=GitPushProtocol.decode(bytes);
                if(!DurableCommandProtocol.hash(bytes).equals(args[1])||!directory.getFileName().toString().equals(input.id()))throw new IllegalStateException();
                var git=new GitEvidenceProcess(new SafeProcessRunner());var scope=GitCredentialProvider.Scope.fromWorker();git.credentialProvider((project,url)->scope.forRemote(url));
                new GitPublicationTransport(git).publish(input);System.out.println("LOOPPER_GIT_PUSH_CONFIRMED");
            }
        }catch(TaskFailure failure){fail(failure.code());}catch(Exception|LinkageError failure){fail("WORKFLOW_PUSH_EVIDENCE_INVALID");}
    }
    private static void fail(String code){System.out.println("LOOPPER_GIT_PUSH_FAILURE:"+(code!=null&&code.matches("[A-Z][A-Z0-9_]{1,119}")?code:"WORKFLOW_PUSH_FAILED"));System.exit(1);}
}
