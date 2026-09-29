package io.opencode.loopper.service.workflow;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.runtime.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowPushJobs {
    private final ImmutableContentStore storage;
    private final GitCredentialProvider credentials;
    public WorkflowPushJobs(LoopperProperties properties,GitCredentialProvider credentials){storage=new ImmutableContentStore(properties.getDataDir().resolve("workflow-pushes"));this.credentials=credentials;}
    public DurableCommandProtocol.Request prepare(GitPushProtocol.Input input) {
        try {
            Path directory=storage.directory(input.id());byte[] bytes=GitPushProtocol.encode(input);DurableCommandProtocol.publish(directory.resolve("input"),bytes);
            Path helper=DurableHelperArchive.write(directory.getParent().resolve("helpers"),GitPushWorker.class,GitPushProtocol.class,GitPublicationTransport.class,
                    GitEvidenceProcess.class,GitEvidenceDiagnostic.class,GitProjectScope.class,GitCredentialProvider.class,GitHttpAuthentication.class,
                    SafeProcessRunner.class,ProcessResult.class,ExecutableResolver.class,ChildProcessEnvironment.class,TaskFailure.class,DurableCommandProtocol.class);
            String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java").toString();
            return new DurableCommandProtocol.Request(input.id(),directory.toString(),List.of(java,"-jar",helper.toString(),directory.toString(),DurableCommandProtocol.hash(bytes)),300);
        }catch(IOException failure){throw new TaskFailure("WORKFLOW_PUSH_EVIDENCE_INVALID","推送记录无法准备，请检查数据目录并恢复原操作。");}
    }
    public Map<String,String> environment(GitPushProtocol.Input input){return credentials.scope(Path.of(input.project())).forWorker();}
}
