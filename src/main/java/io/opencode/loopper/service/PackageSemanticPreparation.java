package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.domain.SessionFailure;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.ObjectMapper;

/** At most one optional read-only semantic turn per discussion revision; dispatch uncertainty never causes a resend. */
final class PackageSemanticPreparation {
    static final String VERSION = "PACKAGE_SEMANTIC_PREPARATION_V1";
    private final LoopperMapper mapper;
    private final DesignerConversationCoordinator conversations;
    private final OpenCodeClient openCode;
    private final DesignerPackageCandidateOrchestrator candidates;
    private final DesignerAttachmentContext attachments;
    private final ObjectMapper json;
    private final PackageBehaviorPreparation behavior;

    PackageSemanticPreparation(LoopperMapper mapper, DesignerConversationCoordinator conversations,
            OpenCodeClient openCode, DesignerPackageCandidateOrchestrator candidates,
            DesignerAttachmentContext attachments, ObjectMapper json) {
        this.mapper = mapper; this.conversations = conversations; this.openCode = openCode;
        this.candidates = candidates; this.attachments = attachments; this.json = json;
        this.behavior = new PackageBehaviorPreparation(mapper, conversations, openCode, candidates, attachments, json);
    }

    boolean start(DesignWorkPackageRow owner, DesignDiscussionRevisionRow discussion,
            OpenCodeClient.OpenCodeSession remote, String original, String basePrompt) {
        if (conversations.behaviorV1(remote.id())) return behavior.start(owner, discussion, remote, original, basePrompt);
        if (!conversations.packageV2(remote.id()) || discussion.questionRequired()) return false;
        var reasons = reasons(original);
        if (reasons.isEmpty()) return false;
        var row = new PackageSemanticPreparationRow(UUID.randomUUID().toString(), owner.id(), discussion.revision(),
                remote.id(), PackageDesignEvidencePreparation.hash(original.getBytes(StandardCharsets.UTF_8)), VERSION,
                json.writeValueAsString(reasons), basePrompt, "PREPARED", null, null, Instant.now().toString(), 0);
        if (mapper.insertPackageSemanticPreparation(row) != 1) {
            var existing = mapper.findPackageSemanticPreparation(owner.id(), discussion.revision()).orElseThrow();
            if ("FAILED".equals(existing.state())) throw conflict("本修订整理已失败，不再重复；通过本地反馈形成新修订");
            return !"DISPATCHED".equals(existing.state());
        }
        transition(row, "RUNNING", null, null);
        conversations.begin(remote, "PACKAGE_SEMANTICS");
        conversations.send(remote, attachments.packagePrompt(owner.designerSessionId(), owner.packageId(), prompt(original, reasons)));
        return true;
    }

    boolean poll(DesignWorkPackageRow owner, DesignDiscussionRevisionRow discussion,
            OpenCodeClient.OpenCodeSession remote, boolean timedOut, BooleanSupplier consumeBudget) {
        if (behavior.present(owner, discussion)) return behavior.poll(owner, discussion, remote, timedOut, consumeBudget);
        var row = mapper.findPackageSemanticPreparation(owner.id(), discussion.revision()).orElse(null);
        if (row == null || "DISPATCHED".equals(row.state())) return false;
        var original = mapper.findDesignRequirementRevision(owner.requirementRevisionId()).orElseThrow(() -> conflict("冻结原始需求不存在"));
        if (!PackageDesignEvidencePreparation.hash(original.requirementText().getBytes(StandardCharsets.UTF_8)).equals(row.requirementSha256()))
            throw conflict("整理材料的原始需求哈希已变化");
        if (!remote.id().equals(row.remoteId())) throw conflict("整理回合属于其他冻结会话");
        if (timedOut) {
            openCode.abortWithConfirmation(remote);
            transition(row, "FAILED", null, "PACKAGE_SEMANTICS_TIMEOUT");
            throw new SessionFailure("PACKAGE_SEMANTICS_TIMEOUT", "语义整理已超时并确认停止");
        }
        if ("RUNNING".equals(row.state())) {
            var turn = mapper.designerTurnForRemote(remote.id()).orElseThrow(() -> conflict("整理请求准备中断，不能重复发送"));
            if (!"PACKAGE_SEMANTICS".equals(turn.phase())) throw conflict("整理阶段与持久化回合不一致");
            if (!"SENT".equals(turn.state()) && !"SETTLED".equals(turn.state())) return true;
            var status = openCode.sessionStatus(remote);
            if (status.retrying() || !status.completed() && !status.failed()) return true;
            if (status.failed()) {
                transition(row, "FAILED", material(openCode.sessionOutput(remote)), "PACKAGE_SEMANTICS_FAILED");
                throw new SessionFailure("PACKAGE_SEMANTICS_FAILED", "整理会话失败，已保存有界材料；未增加第二轮整理");
            }
            transition(row, "READY", material(openCode.sessionOutput(remote)), null);
            row = mapper.findPackageSemanticPreparation(owner.id(), discussion.revision()).orElseThrow();
        }
        if ("READY".equals(row.state())) {
            conversations.settle(remote.id());
            transition(row, "DISPATCHING", null, null);
            row = mapper.findPackageSemanticPreparation(owner.id(), discussion.revision()).orElseThrow();
            if (!consumeBudget.getAsBoolean()) {
                openCode.abortWithConfirmation(remote);
                transition(row, "FAILED", null, "WORK_PACKAGE_MODEL_CALL_LIMIT");
                return true;
            }
            conversations.begin(remote, "PACKAGE_DESIGN");
            var prepared = candidates.open(owner, remote, row.basePrompt() + "\n\n只读语义整理材料（模型建议，不能覆盖冻结原文）：\n" + row.material());
            conversations.send(remote, attachments.packagePrompt(owner.designerSessionId(), owner.packageId(), prepared.prompt()));
            var current = mapper.findPackageSemanticPreparation(owner.id(), discussion.revision()).orElseThrow();
            transition(current, "DISPATCHED", null, null);
            return true;
        }
        if ("DISPATCHING".equals(row.state())) {
            var turn = mapper.designerTurnForRemote(remote.id()).orElseThrow(() -> conflict("后续设计请求准备中断，不能重复发送"));
            if (!"PACKAGE_DESIGN".equals(turn.phase())) throw conflict("后续设计发送意图已记录，但创建回合前中断；保留材料并停止，不重复扣预算或发送");
            if ("SENT".equals(turn.state()) || "SETTLED".equals(turn.state())) {
                transition(row, "DISPATCHED", null, null); return true;
            }
            return true; // Existing conversation recovery owns PREPARED/SENDING/UNKNOWN; never dispatch twice here.
        }
        throw conflict("语义整理已失败或准备中断；请通过本地反馈形成新修订，历史材料保持可追溯");
    }

    static List<String> reasons(String text) {
        if (text == null) return List.of();
        text = text.replaceAll("(?:不需要|无需|不涉及|不要求|无须)(?:新增|设计|实现)?(?:幂等|补偿|跨阶段不变量)(?:[或和与及、](?:幂等|补偿|跨阶段不变量))*", "");
        List<String> reasons = new ArrayList<>();
        if (text.matches("(?s).*(且|同时|并且).*(或|否则|除非|例外|失败).*")) reasons.add("COMBINED_CONDITIONS");
        if (text.matches("(?s).*(除非|例外|否则|补偿|幂等|跨阶段|不变量|状态转换).*")) reasons.add("BRANCH_OR_INVARIANT");
        if (text.matches("(?s).*(尚未确认|尚未确定|尚未决定|待确认|待建测试).*")) reasons.add("UNRESOLVED_FACT");
        return List.copyOf(reasons);
    }

    static String prompt(String original, List<String> reasons) {
        return VERSION + "；触发原因=" + reasons + "\n本修订仅此一轮语义整理。只读，不调用提交或提问工具，不生成实现。"
                + "按原文逐项列出 branches（前置条件/动作/可观察结果/例外/不变量）、sourceRefs、unresolved、requiredEvidence。"
                + "必须保留否定作用域、所有或分支和跨阶段约束；未知仓库事实与业务未定选择分开；明确行为可规划待建测试。"
                + "不要自行替用户选择；整理只是后续设计建议。输出有界 JSON 对象，四个字段均为数组。\n"
                + PackageRequirementSources.prompt(original);
    }

    static String material(String output) {
        if (output == null || output.isBlank()) return "整理没有产生有效材料；按冻结原文继续完整设计。";
        if (output.getBytes(StandardCharsets.UTF_8).length > 32768) return "整理输出超过32KiB，未截断逻辑作为有效材料；按冻结原文继续。";
        return output;
    }

    private void transition(PackageSemanticPreparationRow row, String next, String material, String code) {
        if (mapper.transitionPackageSemanticPreparation(row.id(), row.version(), row.state(), next, material, code) != 1)
            throw conflict("整理状态已由并发请求改变，请刷新；没有重复发送");
    }
    private static ConflictException conflict(String detail) { return new ConflictException("PACKAGE_SEMANTICS_STATE_CONFLICT", detail); }
}
