package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** The append-only messages for a DesignerSession.  SYSTEM entries are handoff notices, never model output. */
public record DesignerMessageRow(String id, String designerSessionId, int ordinal, String role, String content,
                                 String deliveryState, String createdAt, String actor,
                                 Integer requirementRevision, String workPackageId) {
    @AutomapConstructor
    public DesignerMessageRow { }

    public DesignerMessageRow(String id, String designerSessionId, int ordinal, String role, String content,
                              String deliveryState, String createdAt, String actor) {
        this(id, designerSessionId, ordinal, role, content, deliveryState, createdAt, actor, null, null);
    }
}
