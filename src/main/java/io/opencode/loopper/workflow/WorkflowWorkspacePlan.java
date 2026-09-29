package io.opencode.loopper.workflow;

/** Server-frozen facts shared by the Git adapter and the independent persistence ledger. */
public record WorkflowWorkspacePlan(String projectDirectory, String canonicalRoot, String rootFingerprint,
        String sourceBranch, String sourceCommit, String baseTree, String branch, String checkpointRef,
        String seedTree, String objectRepository) {
    public WorkflowWorkspacePlan(String projectDirectory,String canonicalRoot,String rootFingerprint,String sourceBranch,
            String sourceCommit,String baseTree,String branch,String checkpointRef,String seedTree) {
        this(projectDirectory,canonicalRoot,rootFingerprint,sourceBranch,sourceCommit,baseTree,branch,checkpointRef,seedTree,null);
    }
}
