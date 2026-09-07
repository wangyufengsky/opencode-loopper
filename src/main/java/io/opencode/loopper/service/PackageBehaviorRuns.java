package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import tools.jackson.databind.ObjectMapper;

/** Frozen run/book binding; reads and integrity checks only, never prepares evidence during candidate validation. */
final class PackageBehaviorRuns {
    static final String WORKFLOW = "PACKAGE_DESIGN_V2_BEHAVIOR_V1";
    private PackageBehaviorRuns() { }
    static PackageBehaviorPreparationRow prepared(LoopperMapper mapper, DesignWorkPackageRow owner, String remote) {
        var row = mapper.behaviorForRemote(owner.id(), remote).orElse(null);
        if (row == null) return null;
        if (!java.util.Set.of("READY", "DESIGN_DISPATCHING", "DISPATCHED").contains(row.state()) || row.bookJson() == null)
            throw new ConflictException("PACKAGE_BEHAVIOR_NOT_REVIEWED", "语义义务尚未完成独立来源复核");
        return row;
    }
    static PackageBehaviorContract load(PackageBehaviorMapper mapper, String run, String original, String owner, String remote, ObjectMapper json) {
        var row = mapper.behaviorForRun(run).orElseThrow(() -> new ConflictException("PACKAGE_BEHAVIOR_NOT_FROZEN", "运行缺少冻结义务绑定"));
        if (!PackageBehaviorPrompts.VERSION.equals(row.promptVersion()) || !owner.equals(row.designWorkPackageId()) || !remote.equals(row.remoteId())
                || !PackageBehaviorSourceReview.hash(original).equals(row.requirementSha256()) || row.bookJson() == null
                || !PackageBehaviorSourceReview.hash(row.bookJson()).equals(row.bookSha256()) || row.reviewJson() == null || row.extraction() == null)
            throw new ConflictException("PACKAGE_BEHAVIOR_SOURCE_MISMATCH", "原文、拥有者、会话或模型哈希不匹配");
        // Full review validation includes the source/extraction hashes and validates the model independently.
        var checked = new PackageBehaviorSourceReview(json).validate(original, row.extraction(), row.contextJson(), row.reviewJson(), false);
        if (!checked.accepted() || !row.bookSha256().equals(checked.bookSha256()))
            throw new ConflictException("PACKAGE_BEHAVIOR_REVIEW_INVALID", "来源复核证据不完整或与冻结模型不匹配");
        return json.readValue(row.bookJson(), PackageBehaviorContract.class);
    }
}
