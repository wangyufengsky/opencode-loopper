package io.opencode.loopper.domain;

public enum LifecycleMachineType implements DescribedEnum {
    TASK("任务状态机"), STAGE("阶段状态机"), ATTEMPT("执行尝试状态机"),
    EXECUTION_SESSION("执行会话状态机"), JUDGE_RUN("评审运行状态机"),
    JUDGE_REVIEW_BATCH("双评审批次状态机"),
    LOOP_DRAFT("流程草稿状态机"), DESIGNER_SESSION("设计会话状态机"),
    DESIGNER_AUTO_MODE("设计全自动模式状态机"),
    LOOPSPEC_COMPILATION("LoopSpec 编译状态机"),
    DESIGN_REQUIREMENT_REVISION("设计需求版本状态机"), TASK_DECOMPOSITION("任务拆解状态机"),
    DESIGN_WORK_PACKAGE("设计工作包状态机"),
    PROJECT_CONVENTION("项目约定生成状态机"), INTERACTION("交互请求状态机"),
    WORKSPACE_LEASE("工作区租约状态机"), TASK_QUEUE("任务队列状态机"),
    LOOPSPEC_TEMPLATE("LoopSpec 模板状态机"), AUTOMATION_RULE("自动化规则状态机"),
    AUTOMATION_RUN("自动化运行状态机"), TASK_PUBLICATION("任务发布状态机"),
    TASK_EXECUTION_CYCLE("任务执行轮次状态机"), WORKSPACE_CHECKPOINT("工作区冻结点状态机"),
    TASK_PACKAGE_RUN("任务工作包状态机"), PACKAGE_PLAN_REVISION("工作包计划修订状态机"),
    CANDIDATE_SUBMISSION_RUN("候选提交运行状态机"),
    CANDIDATE_PROMPT_DISPATCH("候选提示派发状态机"),
    ACCEPTANCE_CANDIDATE_HANDOFF("验收候选兼容交接状态机"),
    ACCEPTANCE_CANDIDATE_HANDOFF_CLEANUP("验收候选兼容交接清理状态机"),
    ACCEPTANCE_CANDIDATE_INTERNAL_LAUNCH("验收候选内部启动状态机"),
    ACCEPTANCE_CANDIDATE_INTERNAL_LAUNCH_CLEANUP("验收候选内部启动清理状态机"),
    ACCEPTANCE_CANDIDATE_INTERNAL_TERMINATION_INTENT("验收候选内部终止意图状态机"),
    GENERIC_CANDIDATE_INTERNAL_LAUNCH("通用候选内部启动状态机"),
    GENERIC_CANDIDATE_INTERNAL_LAUNCH_CLEANUP("通用候选内部启动清理状态机"),
    GENERIC_CANDIDATE_INTERNAL_TERMINATION_INTENT("通用候选内部终止意图状态机");

    private final String description;
    LifecycleMachineType(String description) { this.description = description; }
    @Override public String description() { return description; }
}
