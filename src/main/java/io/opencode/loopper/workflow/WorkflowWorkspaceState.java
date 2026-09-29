package io.opencode.loopper.workflow;

public enum WorkflowWorkspaceState implements io.opencode.loopper.domain.DescribedEnum {
    PREPARING("准备节点工作区"), READY("工作区就绪"), CAPTURING("保存工作区"),
    FROZEN("检查点已保存"), RESTORING("恢复源分支"), RESTORED("源分支已恢复"), RELEASED("已交出工作区");
    private final String description;
    WorkflowWorkspaceState(String description) { this.description=description; }
    @Override public String description() { return description; }
}
