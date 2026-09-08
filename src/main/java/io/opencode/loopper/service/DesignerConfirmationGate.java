package io.opencode.loopper.service;

import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.util.Set;

/** Shared persisted-design gate for projections, final draft edits and task creation. */
final class DesignerConfirmationGate {
    private DesignerConfirmationGate() { }

    static Decision assess(LoopperMapper mapper, DesignerSessionRow session, LoopSpec spec) {
        if (!Set.of("FINAL_REVIEW", "COMPLETED").contains(session.workflowPhase())
                || Set.of("STOPPING", "CANCELLED", "FAILED").contains(session.state())) {
            return blocked("DESIGN_WORKFLOW_NOT_COMPLETED", "设计尚未进入总体确认，请先完成需求确认和设计流程。");
        }
        if (session.currentRequirementRevision() != null) {
            var revision = mapper.findCurrentDesignRequirementRevision(session.id()).orElse(null);
            var packages = revision == null ? java.util.List.<io.opencode.loopper.persistence.DesignWorkPackageRow>of()
                    : mapper.listDesignWorkPackages(revision.id());
            return !packages.isEmpty() && packages.stream().allMatch(row -> "APPROVED".equals(row.state())
                    && row.approvedDesignRevision() != null) ? allowed()
                    : blocked("DESIGN_PACKAGES_NOT_APPROVED", "尚有工作包未批准，请完成全部工作包的设计确认。");
        }
        var profile = mapper.findCurrentDesignerTaskProfile(session.id()).orElse(null);
        if (profile == null || !"FROZEN".equals(profile.state())) {
            return blocked("TASK_PROFILE_NOT_FROZEN", "任务设置尚未冻结，请先确认需求和任务设置。");
        }
        return DirectArtifactConfirmationPolicy.eligible(profile, spec, planId -> mapper.findArtifactPlan(planId)
                .filter(plan -> session.id().equals(plan.designerSessionId()) && profile.id().equals(plan.taskProfileId()))
                .map(plan -> "FROZEN".equals(plan.state())).orElse(false)) ? allowed()
                : blocked("DESIGN_EXECUTION_CONTRACT_INVALID", contractDetail(profile.intent(), profile.workflowTemplate(), spec));
    }

    private static String contractDetail(String intent, String workflow, LoopSpec spec) {
        if (spec.stages().size() != 1) return "此设计流程只支持一个执行阶段，请恢复单阶段后保存。";
        if ("DOCUMENT_AUTHORING".equals(intent)) {
            var stage = spec.stages().getFirst();
            if (stage.acceptanceCriteria().stream().noneMatch(criterion -> Set.of("JUDGE", "BOTH").contains(criterion.verificationMode())
                    && criterion.judgeRubric() != null && !criterion.judgeRubric().isBlank())) {
                return "阶段 1 缺少正文内容的 AI 评审：请添加 AI 评审或双重验收条件，填写正文完整性与来源准确性的评审准则后保存。";
            }
            return "阶段 1 与冻结的文档执行设置不一致，请保留文档撰写阶段、OpenCode 实施及对应交付文件的文档结构验收后保存。";
        }
        return "LOCAL_MAINTENANCE".equals(workflow)
                ? "维护阶段缺少禁止删除的差异验收，请恢复维护执行设置后保存。"
                : "制品计划尚未冻结或不属于当前设计，请重新确认需求以生成对应计划。";
    }

    private static Decision allowed() { return new Decision(true, null, null); }
    private static Decision blocked(String code, String detail) { return new Decision(false, code, detail); }

    record Decision(boolean eligible, String code, String detail) {
        void requireEligible() { if (!eligible) throw new ConflictException(code, detail); }
    }
}
