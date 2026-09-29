package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.runtime.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** Prepares durable supervised capture; its business owner must persist the request before any launch/grant. */
@Component
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class GitSnapshotJobs {
    private final ImmutableContentStore storage;
    private final GitCredentialProvider credentials;
    public GitSnapshotJobs(LoopperProperties properties,GitCredentialProvider credentials) {
        storage=new ImmutableContentStore(properties.getDataDir().resolve("workflow-repository-snapshots"));this.credentials=credentials;
    }
    public record Prepared(DurableCommandProtocol.Request request,String inputSha256) { }
    public Prepared prepare(String attemptId,GitSnapshotJobProtocol.Input input,int timeoutSeconds) {
        try {
            Path directory=storage.directory(input.nodeId());byte[] bytes=GitSnapshotJobProtocol.input(input);
            GitSnapshotJobProtocol.publish(directory.resolve("input"),bytes);String sha=DurableCommandProtocol.hash(bytes);
            Path helper=DurableHelperArchive.write(directory.getParent().resolve("helpers"),GitSnapshotWorker.class,
                    GitSnapshotJobProtocol.class,GitCommitReader.class,GitSnapshotInventory.class,BadRequestException.class,
                    GitEvidenceProcess.class,GitEvidenceDiagnostic.class,GitProjectScope.class,GitCredentialProvider.class,
                    GitHttpAuthentication.class,SafeProcessRunner.class,ProcessResult.class,ExecutableResolver.class,
                    ChildProcessEnvironment.class,TaskFailure.class,DurableCommandProtocol.class);
            String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java").toString();
            return new Prepared(new DurableCommandProtocol.Request(attemptId,directory.toString(),List.of(java,"-jar",helper.toString(),directory.toString(),sha),timeoutSeconds),sha);
        }catch(IOException failure){throw invalid();}
    }
    public Map<String,String> environment(GitSnapshotJobProtocol.Input input) {
        // A completed private capture is reverified without consulting an offline source or decrypting credentials.
        if(Files.exists(storage.location(input.nodeId()).resolve("snapshot"),LinkOption.NOFOLLOW_LINKS)) {
            try{read(input,DurableCommandProtocol.hash(GitSnapshotJobProtocol.input(input)));}
            catch(IOException failure){throw invalid();}
            return new GitCredentialProvider.Scope("",false,Map.of()).forWorker();
        }
        var scope=input.remote()==null?new GitCredentialProvider.Scope("",false,Map.of()):credentials.scope(Path.of(input.projectPath()));
        return scope.forWorker();
    }
    public GitSnapshotJobProtocol.Snapshot read(GitSnapshotJobProtocol.Input input,String expectedHash) {
        try {
            byte[] bytes=GitSnapshotJobProtocol.input(input);
            if(!DurableCommandProtocol.hash(bytes).equals(expectedHash))throw invalid();
            Path directory=storage.location(input.nodeId());
            if(!Arrays.equals(GitSnapshotJobProtocol.read(directory.resolve("input")),bytes))throw invalid();
            var value=GitSnapshotJobProtocol.snapshot(GitSnapshotJobProtocol.read(directory.resolve("snapshot")));
            if(!value.binding().inputSha256().equals(expectedHash))throw invalid();return value;
        }catch(IOException|IllegalArgumentException failure){throw invalid();}
    }
    public Path repository(String nodeId){return storage.location(nodeId).resolve("code.git");}
    private static TaskFailure invalid(){return new TaskFailure("WORKFLOW_REPOSITORY_EVIDENCE_INVALID","原代码采集记录缺失或不一致，请保留现场后恢复原节点");}
}
