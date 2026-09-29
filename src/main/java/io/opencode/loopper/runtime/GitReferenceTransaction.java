package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.TaskFailure;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Holds Git's own prepared reference lock while checking raw/symbolic identity. */
final class GitReferenceTransaction {
    private GitReferenceTransaction() { }
    static GitEvidenceProcess.Result create(GitEvidenceProcess.ProcessScope scope,Duration timeout,
            String ref,String commit,Runnable guard) throws InterruptedException {
        var failure=new AtomicReference<Throwable>();var diagnostic=new ByteArrayOutputStream();
        Thread stderr=Thread.ofVirtual().name("git-reference-errors").start(()->{
            try(var input=scope.process.getErrorStream()) {
                for(int count=0,value;(value=input.read())!=-1;count++) {
                    if(count>=8192)throw new IOException("Reference diagnostic limit");diagnostic.write(value);
                }
            }catch(IOException error){failure.compareAndSet(null,error);scope.stop();}
        });
        Thread dialogue=Thread.ofVirtual().name("git-reference-transaction").start(()->{
            try(var input=scope.process.getInputStream();var output=scope.process.getOutputStream()) {
                send(output,"start\noption no-deref\ncreate "+ref+" "+commit+"\nprepare\n");
                expect(input,"start: ok");expect(input,"prepare: ok");
                // update-ref's zero-old-value check alone accepts an existing dangling symbolic ref.
                // At this point cooperating Git mutations cannot replace the ref under this check.
                try{guard.run();}
                catch(RuntimeException|Error rejected) {
                    try{send(output,"abort\n");expect(input,"abort: ok");}catch(IOException failureToAbort){rejected.addSuppressed(failureToAbort);}
                    throw rejected;
                }
                send(output,"commit\n");expect(input,"commit: ok");output.close();
                if(input.read()!=-1)throw new IOException("Unexpected reference response");
            }catch(Throwable error){failure.compareAndSet(null,error);scope.stop();}
        });
        boolean done=false;
        try{done=scope.process.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS);}
        finally {
            if(!done) {
                dialogue.interrupt();
                // Let an interrupted guard abort its prepared transaction before resorting to a kill.
                try{if(!scope.process.waitFor(2000,TimeUnit.MILLISECONDS))scope.stop();}
                catch(InterruptedException interrupted){scope.stop();Thread.currentThread().interrupt();}
                stderr.interrupt();
            }
            dialogue.join(2000);stderr.join(2000);
        }
        if(!done)throw new TaskFailure("TEMPLATE_GIT_TIMEOUT","保存 Git 引用超时，请重试原操作");
        if(dialogue.isAlive()||stderr.isAlive())throw new TaskFailure("TEMPLATE_GIT_STOP_UNCONFIRMED","Git 引用检查尚未停止，请保留原操作并重新检查");
        Throwable error=failure.get();if(error instanceof RuntimeException runtime)throw runtime;
        if(error instanceof Error fatal)throw fatal;
        var result=new GitEvidenceProcess.Result(scope.process.exitValue(),"",GitEvidenceDiagnostic.classify(diagnostic.toString(StandardCharsets.UTF_8)));
        if(error!=null&&result.exitCode()==0)throw new TaskFailure("GIT_REFERENCE_PROTOCOL_INVALID","Git 引用响应不完整，请重试原操作");
        return result;
    }
    private static void send(OutputStream output,String value)throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));output.flush();
    }
    private static void expect(InputStream input,String expected)throws IOException {
        var line=new ByteArrayOutputStream();
        for(int count=0;count<=64;count++) {
            int value=input.read();if(value==-1)throw new EOFException("Incomplete reference response");
            if(value=='\n') {
                if(!line.toString(StandardCharsets.US_ASCII).equals(expected))throw new IOException("Unexpected reference response");return;
            }
            line.write(value);
        }
        throw new IOException("Reference response limit");
    }
}
