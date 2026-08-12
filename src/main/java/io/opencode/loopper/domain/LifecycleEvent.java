package io.opencode.loopper.domain;

/** Stable internal event codes shared by the small aggregate state machines. */
public enum LifecycleEvent implements DescribedEnum {
    CREATED("创建实体"), PREPARE("开始准备"), RETRY_PREPARATION("重新准备执行环境"),
    PREPARATION_SUCCEEDED("准备完成"), START("开始执行"),
    BEGIN_VERIFICATION("开始验证"), SCHEDULE_RETRY("安排重试"), RETRY("执行重试"),
    ADVANCE_STAGE("推进到下一阶段"), BEGIN_FINAL_REVIEW("开始最终评审"),
    RETRY_FINAL_REVIEW("重试最终评审"), REOPEN_FINAL_REVIEW("重新打开最终评审"),
    APPROVE("评审通过"), REQUIRE_INPUT("需要人工输入"), PAUSE("暂停"), RESUME("恢复"),
    CANCEL("取消"), FAIL("标记失败"), RECOVER("执行恢复"), COMPLETE("完成"),
    VERIFICATION_FAIL("验证失败"), SESSION_FAIL("会话失败"), TASK_FAIL("任务失败"),
    DISCONNECT("会话断开"), ABORT("终止"), UPDATE("更新草稿"), CONFIRM("确认"),
    DISPATCH("发起交接"), DEFER("延期交接"), APPLY("应用"), CLAIM("认领处理"),
    RELEASE_CLAIM("释放处理权"), RESOLVE("解决"), REJECT("拒绝"), HARD_DENY("强制拒绝"),
    STALE("标记失效"), ACQUIRE("获取租约"), TRANSFER("转移租约"),
    RELEASE_PENDING("等待释放"), RELEASE("释放"), ENQUEUE("进入队列"), ADMIT("准入"),
    FINISH("结束"), ARCHIVE("归档"), RESTORE("恢复归档"), ENABLE("启用"), DISABLE("停用"),
    REQUIRE_REVIEW("要求人工评审"), QUEUE("加入执行队列"), SUCCEED("标记成功"), SKIP("跳过"),
    RECORD_COMMIT("记录提交"), RECORD_PUSH("记录推送"), OPEN_MERGE_REQUEST("记录合并请求"),
    CLOSE_MERGE_REQUEST("关闭合并请求"), RECORD_MERGE("记录合并"), COMPLETE_LOCAL_PUBLICATION("完成本地交付");

    private final String description;
    LifecycleEvent(String description) { this.description = description; }
    @Override public String description() { return description; }
}
