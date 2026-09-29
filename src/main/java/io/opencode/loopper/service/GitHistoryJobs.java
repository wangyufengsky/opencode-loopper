package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.template.TemplateDateRange;
import io.opencode.loopper.template.TemplateGitEvidence;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** Full-history capture transport; the workflow owner persists intent and proves independent process stop. */
@Component
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class GitHistoryJobs {
    private final ImmutableContentStore storage;
    private final GitCredentialProvider credentials;
    public GitHistoryJobs(LoopperProperties properties,GitCredentialProvider credentials) {
        storage=new ImmutableContentStore(properties.getDataDir().resolve("workflow-git-history"));this.credentials=credentials;
    }
    public record Prepared(DurableCommandProtocol.Request request,String inputSha256) { }
    public Prepared prepare(String attemptId,GitHistoryJobProtocol.Input input,int timeoutSeconds) {
        try {
            Path directory=storage.directory(input.nodeId());byte[] bytes=GitHistoryJobProtocol.input(input);
            GitSnapshotJobProtocol.publish(directory.resolve("input"),bytes);String sha=DurableCommandProtocol.hash(bytes);
            Path helper=DurableHelperArchive.write(directory.getParent().resolve("helpers"),GitHistoryWorker.class,
                    GitHistoryJobProtocol.class,GitHistoryEvidenceCodec.class,GitHistoryReader.class,TemplateGitMergeBaseline.class,
                    TemplateDateRange.class,TemplateGitEvidence.class,GitSnapshotJobProtocol.class,GitCommitReader.class,
                    GitSnapshotInventory.class,BadRequestException.class,GitEvidenceProcess.class,GitEvidenceDiagnostic.class,
                    GitProjectScope.class,GitCredentialProvider.class,GitHttpAuthentication.class,SafeProcessRunner.class,
                    ProcessResult.class,ExecutableResolver.class,ChildProcessEnvironment.class,TaskFailure.class,DurableCommandProtocol.class);
            String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java").toString();
            return new Prepared(new DurableCommandProtocol.Request(attemptId,directory.toString(),List.of(java,"-jar",helper.toString(),directory.toString(),sha),timeoutSeconds),sha);
        }catch(IOException failure){throw invalid();}
    }
    public Map<String,String> environment(GitHistoryJobProtocol.Input input) {
        if(Files.exists(evidence(input.nodeId()),LinkOption.NOFOLLOW_LINKS)) {
            try{read(input,DurableCommandProtocol.hash(GitHistoryJobProtocol.input(input)));}
            catch(IOException failure){throw invalid();}
            return new GitCredentialProvider.Scope("",false,Map.of()).forWorker();
        }
        var scope=input.source().remote()==null?new GitCredentialProvider.Scope("",false,Map.of()):credentials.scope(Path.of(input.source().projectPath()));
        return scope.forWorker();
    }
    public GitHistoryJobProtocol.Frozen read(GitHistoryJobProtocol.Input input,String expectedHash) {
        try {
            byte[] bytes=GitHistoryJobProtocol.input(input);
            if(!DurableCommandProtocol.hash(bytes).equals(expectedHash))throw invalid();
            Path directory=storage.location(input.nodeId());
            if(!Arrays.equals(GitSnapshotJobProtocol.read(directory.resolve("input")),bytes))throw invalid();
            var value=GitHistoryEvidenceCodec.read(evidence(input.nodeId()));GitHistoryJobProtocol.requireBinding(input,expectedHash,value);
            if(!value.binding().equals(GitSnapshotJobProtocol.binding(GitSnapshotJobProtocol.read(directory.resolve("selection")))))throw invalid();
            return value;
        }catch(IOException|RuntimeException failure){throw invalid();}
    }
    public Path repository(String nodeId){return storage.location(nodeId).resolve("history.git");}
    public Path evidence(String nodeId){return storage.location(nodeId).resolve("evidence");}
    private static TaskFailure invalid(){return new TaskFailure("WORKFLOW_HISTORY_EVIDENCE_INVALID","原 Git 历史采集记录缺失或不一致，请保留现场后恢复原节点");}
}
