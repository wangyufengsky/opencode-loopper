package io.opencode.loopper.workflow;
import io.opencode.loopper.domain.DescribedEnum;
public enum WorkflowCommandState implements DescribedEnum {
    PREPARING("准备检查目录"), READY("等待进程登记"), RUNNING("执行检查"), STOPPING("停止检查中"),
    SUCCEEDED("检查完成"), FAILED("检查失败"), CANCELLED("已取消");
    private final String description;
    WorkflowCommandState(String description){this.description=description;}
    @Override public String description(){return description;}
    public boolean terminal(){return this==SUCCEEDED || this==FAILED || this==CANCELLED;}
}
