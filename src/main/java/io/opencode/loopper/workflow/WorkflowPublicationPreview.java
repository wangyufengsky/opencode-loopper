package io.opencode.loopper.workflow;

/** Read-only selection of one fixed CODE result; it is not permission to publish or change a directory. */
public record WorkflowPublicationPreview(String requirementId,long requirementVersion,int planRevision,String requirementState,
        Source source,String workspaceKind,String sourceBranch,WorkflowCodeSnapshot.Reference reference,String deliverySha256,
        String baseTree,String resultTree,int added,int modified,int deleted,long totalBytes,String sha256) {
    public record Source(String nodeKey,String nodeTitle,String attemptId,int ordinal,String attemptState,String outputName,
                         String outputTitle,String createdAt,int changedFiles,int totalFiles){ }
}
