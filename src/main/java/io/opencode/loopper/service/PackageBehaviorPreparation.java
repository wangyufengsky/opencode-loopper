package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.domain.SessionFailure;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.ObjectMapper;

/** One extraction and one independent read-only review, both durably claimed before dispatch. */
final class PackageBehaviorPreparation {
    private final LoopperMapper mapper;
    private final DesignerConversationCoordinator conversations;
    private final OpenCodeClient openCode;
    private final DesignerPackageCandidateOrchestrator candidates;
    private final DesignerAttachmentContext attachments;
    private final ObjectMapper json;
    PackageBehaviorPreparation(LoopperMapper mapper, DesignerConversationCoordinator conversations, OpenCodeClient openCode,
            DesignerPackageCandidateOrchestrator candidates, DesignerAttachmentContext attachments, ObjectMapper json) {
        this.mapper = mapper; this.conversations = conversations; this.openCode = openCode;
        this.candidates = candidates; this.attachments = attachments; this.json = json;
    }
    boolean start(DesignWorkPackageRow owner, DesignDiscussionRevisionRow discussion, OpenCodeClient.OpenCodeSession remote,
            String original, String basePrompt) {
        if (!conversations.behaviorV1(remote.id()) || discussion.questionRequired() || reasons(original).isEmpty()) return false;
        var row = new PackageBehaviorPreparationRow(UUID.randomUUID().toString(), owner.id(), discussion.revision(), remote.id(),
                PackageBehaviorSourceReview.hash(original), basePrompt, scope(owner, original), PackageBehaviorPrompts.VERSION, "EXTRACTING", null, null, null, null, null, null, Instant.now().toString(), 0);
        if (mapper.insertBehaviorPreparation(row) != 1) {
            var current = get(owner, discussion);
            if (Set.of("FAILED", "UNCONFIRMED", "STOPPED").contains(current.state())) throw terminalFailure(current, original);
            return !"DISPATCHED".equals(current.state());
        }
        requireOwner(owner);
        var decisions = json.convertValue(json.readTree(row.contextJson()).path("confirmedDecisions"), new tools.jackson.core.type.TypeReference<java.util.Map<String, String>>() { });
        var pending = new PackageDesignSourceGapAssessment().sourceProblems(original, decisions);
        if (!pending.isEmpty()) {
            transition(row, "UNCONFIRMED", null, null, null, null, null, "PACKAGE_GAP_BUSINESS_DECISION");
            throw new SessionFailure("PACKAGE_GAP_BUSINESS_DECISION", pending.getFirst().repairHint());
        }
        conversations.begin(remote, "PACKAGE_SEMANTICS");
        conversations.send(remote, attachments.packagePrompt(owner.designerSessionId(), owner.packageId(),
                PackageBehaviorPrompts.extract(original, row.contextJson())));
        return true;
    }
    boolean present(DesignWorkPackageRow owner, DesignDiscussionRevisionRow discussion) {
        return mapper.findBehaviorPreparation(owner.id(), discussion.revision()).isPresent();
    }
    boolean poll(DesignWorkPackageRow owner, DesignDiscussionRevisionRow discussion, OpenCodeClient.OpenCodeSession remote,
            boolean timedOut, BooleanSupplier consumeBudget) {
        var row = get(owner, discussion);
        if (!PackageBehaviorPrompts.VERSION.equals(row.promptVersion())) throw conflict("冻结 Prompt 版本不受当前实现支持，禁止静默升级");
        if ("DISPATCHED".equals(row.state())) return false;
        var source = mapper.findDesignRequirementRevision(owner.requirementRevisionId()).orElseThrow(() -> conflict("冻结原文不存在"));
        String original = source.requirementText();
        if (!row.remoteId().equals(remote.id()) || !row.requirementSha256().equals(PackageBehaviorSourceReview.hash(original))) throw conflict("会话或原文哈希与冻结整理不一致");
        if (timedOut) {
            stopReview(row, remote.worktree()); openCode.abortWithConfirmation(remote);
            transition(row, "FAILED", null, null, null, null, null, "PACKAGE_BEHAVIOR_TIMEOUT");
            throw new SessionFailure("PACKAGE_BEHAVIOR_TIMEOUT", "语义准备/复核已超时并确认停止，材料保留");
        }
        requireOwner(owner);
        if ("EXTRACTING".equals(row.state())) {
            if (!sent(remote.id(), "PACKAGE_SEMANTICS")) return true;
            var status = openCode.sessionStatus(remote);
            if (status.retrying() || !status.completed() && !status.failed()) return true;
            String output = bounded(openCode.sessionOutput(remote), 32768);
            if (status.failed()) {
                transition(row, "FAILED", output, null, null, null, null, "PACKAGE_BEHAVIOR_EXTRACTION_FAILED");
                throw new SessionFailure("PACKAGE_BEHAVIOR_EXTRACTION_FAILED", "整理会话失败，已保留材料；不再自动增加整理");
            }
            transition(row, "EXTRACT_READY", output, null, null, null, null, null);
            conversations.settle(remote.id()); row = get(owner, discussion);
        }
        if ("EXTRACT_READY".equals(row.state())) {
            transition(row, "REVIEW_DISPATCHING", null, null, null, null, null, null);
            row = get(owner, discussion);
            if (!consumeBudget.getAsBoolean()) return budgetFailed(row, remote);
            requireOwner(owner);
            var parent = mapper.designerConversationForRemote(remote.id()).orElseThrow(() -> conflict("冻结父会话不存在"));
            var review = conversations.acquire(owner.designerSessionId(), reviewScope(row), Path.of(parent.rootPath()),
                    json.readValue(parent.modelJson(), OpenCodeClient.OpenCodeModel.class), false, false, false, false);
            try { transition(row, "REVIEW_DISPATCHING", null, review.id(), null, null, null, null); }
            catch (RuntimeException stale) { openCode.abortWithConfirmation(review); throw stale; }
            requireOwner(owner);
            conversations.begin(review, "PACKAGE_SEMANTICS");
            conversations.send(review, attachments.packagePrompt(owner.designerSessionId(), owner.packageId(),
                    PackageBehaviorPrompts.review(original, row.extraction(), row.contextJson(), json)));
            transition(get(owner, discussion), "REVIEWING", null, null, null, null, null, null);
            return true;
        }
        if ("REVIEW_DISPATCHING".equals(row.state())) {
            if (row.reviewRemoteId() == null) throw conflict("复核发送意图已记录，但绑定前中断；保留材料，不重复扣费或新建会话");
            var review = conversations.remote(row.reviewRemoteId(), remote.worktree());
            if (sent(review.id(), "PACKAGE_SEMANTICS")) transition(row, "REVIEWING", null, null, null, null, null, null);
            return true;
        }
        if ("REVIEWING".equals(row.state())) {
            var review = conversations.remote(row.reviewRemoteId(), remote.worktree());
            if (!sent(review.id(), "PACKAGE_SEMANTICS")) return true;
            var status = openCode.sessionStatus(review);
            if (status.retrying() || !status.completed() && !status.failed()) return true;
            String output = bounded(openCode.sessionOutput(review), 65536);
            var checked = new PackageBehaviorSourceReview(json).validate(original, row.extraction(), row.contextJson(), output);
            boolean accepted = status.completed() && !status.failed() && checked.accepted();
            transition(row, accepted ? "READY" : "UNCONFIRMED", null, null,
                    checked.reviewJson() == null ? output : checked.reviewJson(), checked.bookJson(), checked.bookSha256(),
                    accepted ? null : "PACKAGE_SOURCE_UNCONFIRMED");
            conversations.settle(review.id()); conversations.retire(review.id(), "SOURCE_REVIEW_FINISHED");
            if (!accepted) throw new SessionFailure("PACKAGE_SOURCE_UNCONFIRMED", "来源语义尚未确认，未自动判定用户缺少需求：" + String.join("；", checked.problems()));
            row = get(owner, discussion);
        }
        if ("READY".equals(row.state())) {
            conversations.settle(remote.id());
            transition(row, "DESIGN_DISPATCHING", null, null, null, null, null, null);
            row = get(owner, discussion);
            if (!consumeBudget.getAsBoolean()) return budgetFailed(row, remote);
            requireOwner(owner);
            conversations.begin(remote, "PACKAGE_DESIGN");
            var prepared = candidates.open(owner, remote, row.basePrompt());
            conversations.send(remote, attachments.packagePrompt(owner.designerSessionId(), owner.packageId(), prepared.prompt()));
            transition(get(owner, discussion), "DISPATCHED", null, null, null, null, null, null);
            return true;
        }
        if ("DESIGN_DISPATCHING".equals(row.state())) {
            if (sent(remote.id(), "PACKAGE_DESIGN")) transition(row, "DISPATCHED", null, null, null, null, null, null);
            return true;
        }
        throw conflict("本修订语义准备已停止或尚未确认；通过本地反馈形成新修订，不自动追加调用");
    }
    private boolean budgetFailed(PackageBehaviorPreparationRow row, OpenCodeClient.OpenCodeSession remote) {
        stopReview(row, remote.worktree()); openCode.abortWithConfirmation(remote);
        transition(row, "FAILED", null, null, null, null, null, "WORK_PACKAGE_MODEL_CALL_LIMIT"); return true;
    }
    private void stopReview(PackageBehaviorPreparationRow row, Path root) {
        // Discover even a review created immediately before a crash left review_remote_id unbound.
        var conversation = mapper.latestDesignerConversation(mapper.findDesignWorkPackage(row.designWorkPackageId()).orElseThrow().designerSessionId(), reviewScope(row)).orElse(null);
        String id = row.reviewRemoteId() != null ? row.reviewRemoteId() : conversation == null ? null : conversation.externalSessionId();
        if (id != null) {
            var binding = mapper.designerConversationForRemote(id).orElse(null);
            var target = binding == null ? new OpenCodeClient.OpenCodeSession(id, root)
                    : new OpenCodeClient.OpenCodeSession(id, Path.of(binding.rootPath()), binding.runtimeGenerationId(), binding.internalMcpServer());
            openCode.abortWithConfirmation(target); conversations.retire(id, "BEHAVIOR_PREPARATION_STOPPED");
        }
    }
    private boolean sent(String remote, String phase) {
        var turn = mapper.designerTurnForRemote(remote).orElseThrow(() -> conflict("回合创建前中断，不重复发送"));
        if (!phase.equals(turn.phase())) throw conflict("持久化语义阶段不匹配，不重复扣费或发送");
        return Set.of("SENT", "SETTLED").contains(turn.state());
    }
    private void requireOwner(DesignWorkPackageRow owner) {
        var current = mapper.findDesignerSession(owner.designerSessionId()).orElseThrow(() -> conflict("拥有者不存在"));
        var work = mapper.findDesignWorkPackage(owner.id()).orElseThrow(() -> conflict("工作包不存在"));
        if (!"RUNNING".equals(current.state()) || !"DESIGNING".equals(work.state()) || work.version() != owner.version()) throw conflict("拥有者已停止或工作包版本变化，不能发送模型请求");
    }
    private PackageBehaviorPreparationRow get(DesignWorkPackageRow owner, DesignDiscussionRevisionRow discussion) {
        return mapper.findBehaviorPreparation(owner.id(), discussion.revision()).orElseThrow(() -> conflict("冻结准备记录不存在"));
    }
    private void transition(PackageBehaviorPreparationRow row, String next, String extraction, String remote, String review, String book, String sha, String code) {
        if (mapper.transitionBehavior(row, next, extraction, remote, review, book, sha, code) != 1) throw conflict("语义准备状态已被并发改变；未重复发送");
    }
    private String scope(DesignWorkPackageRow owner, String original) {
        var requirement = mapper.findDesignRequirementRevision(owner.requirementRevisionId()).orElseThrow();
        return json.writeValueAsString(java.util.Map.of("scopeIn", String.valueOf(owner.scopeInJson()), "scopeOut", String.valueOf(owner.scopeOutJson()),
                "deliverables", String.valueOf(owner.deliverablesJson()), "confirmedDecisions", PackageDesignConfirmedDecisions.load(mapper, owner, original, requirement.revision())));
    }
    private static String reviewScope(PackageBehaviorPreparationRow row) { return "BEHAVIOR_REVIEW:" + row.id(); }
    private static String bounded(String output, int limit) { return output == null ? "" : output.getBytes(StandardCharsets.UTF_8).length <= limit ? output : "输出超过有界大小，未截断作为有效语义材料。"; }
    static List<String> reasons(String text) {
        if (text == null) return List.of();
        String signals = text.replaceAll("(?:不需要|无需|不涉及|不要求|无须)(?:新增|设计|实现)?(?:幂等|补偿|跨阶段不变量|状态转换)(?:[或和与及、](?:幂等|补偿|跨阶段不变量|状态转换))*", "");
        var result = new java.util.ArrayList<String>();
        if (PackageSemanticPreparation.reasons(signals).contains("COMBINED_CONDITIONS")) result.add("COMBINED_CONDITIONS");
        if (signals.matches("(?is).*(幂等|补偿|跨阶段|不变量|并发|idempot|compensat|invariant|concurren).*")) result.add("INVARIANT_OR_CONCURRENCY");
        if (signals.matches("(?is).*(管理员.*或|或.*(?:允许|授权|拒绝)|取消.*(?:停止确认|领取|正在停止)|领取.*取消|重复取消|归档.*(?:拒绝|所有角色)|状态转换|state transition|(?:if|when).*(?:unless|otherwise)).*")) result.add("CONDITIONAL_OR_STATE_BEHAVIOR");
        long conditions = java.util.regex.Pattern.compile("当|若|如果|时|(?i)\\bif\\b|(?i)\\bwhen\\b").matcher(signals).results().count();
        if (conditions >= 3 && signals.matches("(?is).*(否则|除非|例外|或|else|unless).*")) result.add("MULTIPLE_BRANCHES");
        return List.copyOf(result);
    }
    private SessionFailure terminalFailure(PackageBehaviorPreparationRow row, String original) {
        String code = row.failureCode() == null ? "PACKAGE_SOURCE_UNCONFIRMED" : row.failureCode();
        String detail = "本修订已收束，不重复整理或复核；原文与已保存材料保留，请通过本地反馈形成新修订。";
        if (row.reviewJson() != null && row.extraction() != null) {
            var checked = new PackageBehaviorSourceReview(json).validate(original, row.extraction(), row.contextJson(), row.reviewJson(), false);
            detail = "来源语义尚未确认：" + String.join("；", checked.problems()) + "；" + detail;
        } else if ("PACKAGE_GAP_BUSINESS_DECISION".equals(code)) {
            var decisions = json.convertValue(json.readTree(row.contextJson()).path("confirmedDecisions"), new tools.jackson.core.type.TypeReference<java.util.Map<String, String>>() { });
            var problems = new PackageDesignSourceGapAssessment().sourceProblems(original, decisions);
            if (!problems.isEmpty()) detail = problems.getFirst().repairHint();
        }
        return new SessionFailure(code, detail);
    }
    private static ConflictException conflict(String message) { return new ConflictException("PACKAGE_BEHAVIOR_STATE_CONFLICT", message); }
}
