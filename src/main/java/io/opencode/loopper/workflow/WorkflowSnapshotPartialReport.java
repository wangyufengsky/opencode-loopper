package io.opencode.loopper.workflow;

/** On-demand observation; it is neither a persisted delivery nor a completion verdict. */
public record WorkflowSnapshotPartialReport(String content,String sha256,String capturedAt,int planRevision,
                                            int analyzedUnits,int pendingUnits,int excludedUnits){ }
