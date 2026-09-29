package io.opencode.loopper.persistence;

public record WorkspaceLeaseRow(String canonicalRoot, String rootFingerprint, String mode,
                                String holderTaskId, String writerSessionId, String state,
                                String acquiredAt, String heartbeatAt, String releasedAt,
                                String releaseReason, long version, String holderWorkflowAttemptId, String holderWritebackId) {
    @org.apache.ibatis.annotations.AutomapConstructor public WorkspaceLeaseRow { }
    public WorkspaceLeaseRow(String canonicalRoot, String rootFingerprint, String mode, String holderTaskId,
            String writerSessionId, String state, String acquiredAt, String heartbeatAt, String releasedAt,
            String releaseReason, long version) {
        this(canonicalRoot,rootFingerprint,mode,holderTaskId,writerSessionId,state,acquiredAt,heartbeatAt,
                releasedAt,releaseReason,version,null,null);
    }
    public WorkspaceLeaseRow(String canonicalRoot, String rootFingerprint, String mode, String holderTaskId,
            String writerSessionId, String state, String acquiredAt, String heartbeatAt, String releasedAt,
            String releaseReason, long version, String holderWorkflowAttemptId) {
        this(canonicalRoot,rootFingerprint,mode,holderTaskId,writerSessionId,state,acquiredAt,heartbeatAt,
                releasedAt,releaseReason,version,holderWorkflowAttemptId,null);
    }
}
