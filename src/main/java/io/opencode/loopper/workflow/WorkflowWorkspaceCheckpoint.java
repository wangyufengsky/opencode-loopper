package io.opencode.loopper.workflow;

/** Exact workspace object identities, independent of a user checkout or a private directory object store. */
public record WorkflowWorkspaceCheckpoint(String branch,String sourceCommit,String reference,String commit,String tree,String stash) { }
