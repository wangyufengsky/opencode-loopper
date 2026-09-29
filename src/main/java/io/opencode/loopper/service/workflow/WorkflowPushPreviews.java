package io.opencode.loopper.service.workflow;

import io.opencode.loopper.runtime.*;
import io.opencode.loopper.workflow.WorkflowPush;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Local selection and bounded remote advertisement happen outside all database transactions. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowPushPreviews {
    private final WorkflowPublications publications;
    private final GitPublicationTransport transport;
    private final WorkflowEncoding encoding;
    public WorkflowPushPreviews(WorkflowPublications publications,GitPublicationTransport transport,WorkflowEncoding encoding){this.publications=publications;this.transport=transport;this.encoding=encoding;}
    public record Context(WorkflowPublications.Work publication,GitPushProtocol.Input input,WorkflowPush.Preview preview) { }
    public List<String> remotes(String requirement){var source=source(requirement);return transport.remotes(Path.of(source.intent().repository()));}
    public Context inspect(String requirement,String remote) {
        var source=source(requirement);var intent=source.intent();var repository=Path.of(intent.repository());
        var input=new GitPushProtocol.Input(source.row().id(),intent.projectDirectory(),intent.repository(),transport.gitDirectory(repository),remote,
                transport.target(repository,remote),intent.branch(),source.row().commitSha(),intent.commit().tree());
        String current=transport.observe(input);
        if(current!=null&&!current.equals(input.commit()))throw new io.opencode.loopper.service.ConflictException("WORKFLOW_PUSH_REF_CONFLICT","远端成果分支已有不同提交，请检查目标分支或选择其他远端。");
        String hash=WorkflowEncoding.hash(encoding.encode(java.util.Arrays.asList(source.row().id(),source.row().version(),source.row().intentSha256(),input,current)));
        return new Context(source,input,new WorkflowPush.Preview(requirement,source.row().version(),remote,input.url(),input.branch(),input.commit(),current,hash));
    }
    private WorkflowPublications.Work source(String requirement) {
        var work=publications.work(requirement);if(!work.row().state().equals("COMMITTED"))throw WorkflowPublications.changed();
        var identity=DirectWorkspaceLeaseCoordinator.identify(Path.of(work.intent().projectDirectory()));
        if(!identity.canonicalRoot().equals(work.intent().repository())||!identity.rootFingerprint().equals(work.intent().rootFingerprint()))throw WorkflowPublications.changed();return work;
    }
}
