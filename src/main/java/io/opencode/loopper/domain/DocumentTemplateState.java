package io.opencode.loopper.domain;

/** Document intake and orchestration are independent of executable Task state. */
public enum DocumentTemplateState implements DescribedEnum {
    PREPARING("保存需求文档"), ANALYZING("整理需求"), REVIEWING("复核需求"),
    DESIGNING("设计开发方案"), EXECUTING("执行开发"), ASSESSING("评审代码"),
    VERIFYING("复核评审结果"), REPORTING("生成报告"), WAITING_INPUT("等待处理"),
    STOPPING("正在停止"), CANCELLED("已取消"), COMPLETED("已完成");

    private final String description;
    DocumentTemplateState(String description) { this.description = description; }
    @Override public String description() { return description; }
    public boolean terminal() { return this == CANCELLED || this == COMPLETED; }
}
