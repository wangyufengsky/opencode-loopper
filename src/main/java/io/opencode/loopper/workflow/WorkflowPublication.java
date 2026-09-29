package io.opencode.loopper.workflow;

import io.opencode.loopper.runtime.GitCommitIntent;

public final class WorkflowPublication {
    private WorkflowPublication() { }
    public record Request(String requestKey,long expectedVersion,int revision,String node,String attempt,
                          String output,String previewSha256,String message) { }
    public record Intent(String requirementId,String projectId,WorkflowPublicationPreview preview,String repository,
                         String rootFingerprint,String projectDirectory,String projectPrefix,String branch,
                         GitCommitIntent commit) { }
    /** Public projection deliberately excludes filesystem locations and executable intent. */
    public record View(String requirementId,String state,long version,String nodeTitle,String outputTitle,
                       String attemptState,String branch,String message,String commit,String createdAt,String reasonCode) { }
}
