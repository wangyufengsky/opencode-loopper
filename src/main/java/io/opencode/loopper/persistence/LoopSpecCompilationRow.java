package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** One independent read-only compilation of one immutable Designer revision. */
public record LoopSpecCompilationRow(
        String id, String designerSessionId, int designRevision, String state,
        String externalSessionId, String externalSessionState, int repairCount,
        String sourceDesignMessageId, long sourceDraftVersion,
        String lastErrorCode, String lastErrorDetail,
        String createdAt, String updatedAt, long version,
        String workPackageId, int transportRetryCount, String compiledPackageJson) {
    @AutomapConstructor
    public LoopSpecCompilationRow { }

    public LoopSpecCompilationRow(String id, String designerSessionId, int designRevision, String state,
                                  String externalSessionId, String externalSessionState, int repairCount,
                                  String sourceDesignMessageId, long sourceDraftVersion,
                                  String lastErrorCode, String lastErrorDetail,
                                  String createdAt, String updatedAt, long version) {
        this(id, designerSessionId, designRevision, state, externalSessionId, externalSessionState, repairCount,
                sourceDesignMessageId, sourceDraftVersion, lastErrorCode, lastErrorDetail,
                createdAt, updatedAt, version, null, 0, null);
    }
}
