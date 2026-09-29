package io.opencode.loopper.workflow;

import java.util.List;

public final class WorkflowWriteback {
    private WorkflowWriteback() { }
    public record Selection(int revision,String node,String attempt,String output,String sourceSha256) { }
    public record Request(String requestKey,long expectedVersion,Selection selection,String previewSha256) { }
    public record Intent(Selection selection,Preview preview,String nodeTitle,String outputTitle,String attemptState,String objectRepository,
                         WorkflowDirectorySnapshot before,WorkflowDirectorySnapshot after,WorkflowDirectorySnapshot source) { }
    public record View(String requirementId,String state,long version,String queueState,long queuePosition,
                       Preview preview,String nodeTitle,String outputTitle,String attemptState,String createdAt,String appliedAt,String blocker) { }
    public record Preview(String requirementId,long requirementVersion,int revision,String sourceSha256,String directory,
                          String currentSha256,String targetSha256,String sha256,int added,int modified,int deleted,
                          int preservedChanges,int conflictCount,List<String> conflicts) {
        public Preview {conflicts=List.copyOf(conflicts);}
    }
}
