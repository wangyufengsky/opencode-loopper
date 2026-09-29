package io.opencode.loopper.workflow;

public final class WorkflowPush {
    private WorkflowPush() { }
    public record Request(String requestKey,long expectedVersion,String remote,String previewSha256) { }
    public record Preview(String requirementId,long publicationVersion,String remote,String url,String branch,String commit,String remoteCommit,String sha256) { }
    public record View(String requirementId,String state,long version,String remote,String url,String branch,String commit,int ordinal,String reasonCode) { }
}
