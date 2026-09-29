package io.opencode.loopper.service.workflow;

import io.opencode.loopper.runtime.DurableCommandProtocol;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Routes read-only Git captures outside transactions; all variants use the same durable command lifecycle. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class WorkflowGitCaptures {
    private final GitSnapshotJobs repositories;
    private final WorkflowHistoryCapture histories;
    private final WorkflowReviewCapture reviews;
    public WorkflowGitCaptures(GitSnapshotJobs repositories, WorkflowHistoryCapture histories, WorkflowReviewCapture reviews) {
        this.repositories = repositories; this.histories = histories; this.reviews = reviews;
    }
    public record Captured(GitSnapshotJobProtocol.Snapshot repository, WorkflowHistorySnapshot.Manifest history, WorkflowReviewSource.Manifest review) { }
    public DurableCommandProtocol.Request prepare(WorkflowCommandStore.Context context) {
        if (context.review() != null) return reviews.prepare(context.attempt().id(), context.review()).request();
        if (context.history() != null) return histories.prepare(context.attempt().id(), context.history()).request();
        if (context.repository() == null) return null;
        var source = context.repository(); var prepared = repositories.prepare(context.attempt().id(), source.input(), source.timeoutSeconds());
        if (!prepared.inputSha256().equals(source.row().inputSha256())) throw WorkflowCommands.conflict();
        return prepared.request();
    }
    public Map<String, String> environment(WorkflowCommandStore.Context context) {
        if (context.run().state().equals("STOPPING")) return Map.of();
        if (context.review() != null) return reviews.environment(context.review());
        if (context.history() != null) return histories.environment(context.history());
        return context.repository() == null ? Map.of() : repositories.environment(context.repository().input());
    }
    public Captured read(WorkflowCommandStore.Context context, DurableCommandProtocol.Result result) {
        if (context.run().state().equals("STOPPING") || !result.successful()) return new Captured(null, null, null);
        if (context.review() != null) return new Captured(null, null, reviews.read(context.review()));
        if (context.history() != null) return new Captured(null, histories.read(context.history()), null);
        return new Captured(context.repository() == null ? null : repositories.read(context.repository().input(), context.repository().row().inputSha256()), null, null);
    }
}
